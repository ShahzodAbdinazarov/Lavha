package org.telegram.svipe;

import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * One place for the question every Svipe surface asks before it can play anything: <b>how do I
 * address the channel this post lives in, without spending a {@code contacts.resolveUsername}?</b>
 *
 * <p><b>Why this exists.</b> Music, reels and the long-form player all take a reference of
 * (channel_id, message_id, username) from our backend and have to turn it into a real Telegram
 * message. Each of them used to go straight to {@code contacts.resolveUsername} and keep the answer
 * in a static map — which is empty again on the next cold start. So every launch re-resolved every
 * channel the user's favourites, feed and search results mentioned, in parallel: measured on a test
 * account, 20 resolves inside the same 100 ms and 205 in one session.
 *
 * <p>{@code contacts.resolveUsername} is one of the most rate-limited calls Telegram has. That
 * traffic earns a FLOOD_WAIT measured in HOURS (measured: FLOOD_WAIT_13101 — three and a half), and
 * while it is open nothing resolves, so favourites cannot be turned into playable messages and every
 * tap on them dies silently. It is not an account going bad; any account browsing normally gets
 * there, which is why it reproduced on two different accounts against two different backends.
 *
 * <p>Three things fix it, and all three live here so no surface can forget one:
 * <ol>
 *   <li>ask what we ALREADY know first — the reference carries the channel id, and a chat we have
 *       ever seen is addressable from its cached {@code access_hash} with no RPC at all;</li>
 *   <li>PERSIST a resolve that did happen, so it is paid once ever instead of once per launch —
 *       {@code putChats} alone only fills the in-memory map and is gone with the process;</li>
 *   <li>remember the wait. Calling a flood-limited method inside its own window is what makes
 *       Telegram EXTEND the window, so a blocked account must stop asking, not retry.</li>
 * </ol>
 */
public final class SvipeChannelResolve {

    private SvipeChannelResolve() {
    }

    private static final String PREFS = "svipe_resolve_guard";
    private static final String KEY_BLOCKED_UNTIL = "resolve_blocked_until_";

    /**
     * Ceiling on a remembered wait. Telegram has answered with five-figure second counts; trusting
     * one blindly would leave the app refusing to resolve for half a day even after the server had
     * long since let go. A day is well past any real window and still bounded.
     */
    private static final long MAX_WAIT_MS = 24 * 60 * 60 * 1000L;

    public interface ChatCallback {
        /** chat==null means nothing local could address it — the caller must resolve or give up. */
        void run(TLRPC.Chat chat);
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, 0);
    }

    /** A chat is addressable only with an access_hash; a "min" chat carries none. */
    public static boolean usable(TLRPC.Chat chat) {
        return chat != null && chat.access_hash != 0;
    }

    /**
     * The chat for this channel from memory, or null. Never blocks — safe on the UI thread, and the
     * common case once anything has touched the channel this session.
     */
    public static TLRPC.Chat known(int account, long channelId) {
        if (channelId == 0) {
            return null;
        }
        TLRPC.Chat chat = MessagesController.getInstance(account).getChat(channelId);
        return usable(chat) ? chat : null;
    }

    /**
     * Memory, then the local database, then give up — {@code cb} runs on the UI thread either way.
     *
     * <p>The database step is the one that ends the per-launch re-resolve: a channel resolved in any
     * previous session is stored, and reading it costs no network. It runs off the UI thread because
     * {@link MessagesStorage#getChatSync} blocks on the storage queue.
     */
    public static void lookup(int account, long channelId, ChatCallback cb) {
        final TLRPC.Chat inMemory = known(account, channelId);
        if (inMemory != null) {
            cb.run(inMemory);
            return;
        }
        if (channelId == 0) {
            cb.run(null);
            return;
        }
        Utilities.stageQueue.postRunnable(() -> {
            TLRPC.Chat stored = null;
            try {
                stored = MessagesStorage.getInstance(account).getChatSync(channelId);
            } catch (Exception ignore) {
            }
            final TLRPC.Chat found = usable(stored) ? stored : null;
            AndroidUtilities.runOnUIThread(() -> {
                if (found != null) {
                    // Back into the in-memory map, so the rest of this session is a plain lookup.
                    MessagesController.getInstance(account).putChat(found, true);
                }
                cb.run(found);
            });
        });
    }

    /**
     * Keep what a resolve just cost us. {@code putUsers/putChats} fill the in-memory maps only; the
     * write to storage is what makes the next cold start free.
     */
    public static void remember(int account, TLRPC.TL_contacts_resolvedPeer peer) {
        if (peer == null) {
            return;
        }
        MessagesController mc = MessagesController.getInstance(account);
        mc.putUsers(peer.users, false);
        mc.putChats(peer.chats, false);
        // Every resolve in the app funnels through here, so this is the one place that sees a fresh
        // channel — its name and its follower count go home with the next background batch, and the
        // next person's grid can draw the card without resolving anything (SvipeObserved).
        if (peer.chats != null) {
            for (int i = 0; i < peer.chats.size(); i++) {
                SvipeObserved.noteChannel(account, peer.chats.get(i));
            }
        }
        final ArrayList<TLRPC.User> users = peer.users == null ? new ArrayList<>() : new ArrayList<>(peer.users);
        final ArrayList<TLRPC.Chat> chats = peer.chats == null ? new ArrayList<>() : new ArrayList<>(peer.chats);
        if (users.isEmpty() && chats.isEmpty()) {
            return;
        }
        MessagesStorage.getInstance(account).putUsersAndChats(users, chats, true, true);
    }

    /** True while Telegram is making this account wait on contacts.resolveUsername. */
    public static boolean blocked(int account) {
        return prefs().getLong(KEY_BLOCKED_UNTIL + account, 0) > System.currentTimeMillis();
    }

    // ---------------------------------------------------------------------------------------------
    // pacing — what actually earns the flood
    // ---------------------------------------------------------------------------------------------

    /**
     * Telegram floods on the BURST, not on the total. Measured on a real account: opening the app and
     * scrolling for forty seconds asked for 48 resolves across ~20 channels, because a page of the
     * feed is thirty different channels and three surfaces (the grid's posters, the pager's
     * read-ahead, the player itself) each want their own the moment they are bound. Spread over a
     * minute the same traffic passes unnoticed; fired together it costs the account FLOOD_WAIT_2222,
     * and then NOTHING in Svipe resolves — the feed draws blank cards and every tap says "Couldn't
     * play this video".
     *
     * <p>So every {@code contacts.resolveUsername} in the app goes through here: one in the air at a
     * time, spaced, urgent callers (something is on screen waiting to play) ahead of background ones
     * (a poster, a read-ahead). This is a queue, not a drop — a paced resolve still happens.
     */
    private static final long MIN_GAP_MS = 1200;

    /**
     * A ceiling per rolling hour, persisted so a restart cannot reset it. The pacing above stops
     * bursts; this stops the slow grind — a very long session still must not walk the account into a
     * window that takes hours to clear. Past the budget we behave exactly like a flood window: the
     * caller is told "no chat", and every surface already knows how to degrade (reels skip, the grid
     * leaves the card blank, the watch page offers Retry).
     */
    private static final int BUDGET_PER_HOUR = 150;
    private static final String KEY_SPENT = "resolve_spent_";
    private static final String KEY_WINDOW = "resolve_window_";

    /**
     * How long a dispatched resolve may hold the lane before it is assumed lost. Every caller is
     * supposed to call {@link #sent()}, but a request whose callback never runs (tgnet can re-queue
     * one behind a flood wait) would otherwise wedge the lane shut and stop the app resolving
     * ANYTHING — a worse failure than the bursts this queue exists to prevent.
     */
    private static final long LANE_WATCHDOG_MS = 12_000;

    private static final java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
    private static boolean inFlight;
    private static long lastSentAt;
    /** Bumped on every dispatch, so a watchdog only frees the lane it was armed for. */
    private static int laneTicket;

    /** True when this account has spent its hourly resolve budget. Treated exactly like a flood. */
    public static boolean exhausted(int account) {
        final long now = System.currentTimeMillis();
        final long window = prefs().getLong(KEY_WINDOW + account, 0);
        if (now - window >= 60 * 60 * 1000L) {
            return false;   // a new hour starts on the next spend
        }
        return prefs().getInt(KEY_SPENT + account, 0) >= BUDGET_PER_HOUR;
    }

    /** Count one resolve against the hourly budget, rolling the window when it has run out. */
    public static void spend(int account) {
        final long now = System.currentTimeMillis();
        final long window = prefs().getLong(KEY_WINDOW + account, 0);
        if (now - window >= 60 * 60 * 1000L) {
            prefs().edit().putLong(KEY_WINDOW + account, now).putInt(KEY_SPENT + account, 1).apply();
            return;
        }
        prefs().edit().putInt(KEY_SPENT + account, prefs().getInt(KEY_SPENT + account, 0) + 1).apply();
    }

    /**
     * Queue one resolve. {@code send} is run on the UI thread when its turn comes, and the caller
     * MUST call {@link #sent()} once the request settles (answer, error or timeout) — otherwise the
     * lane stays occupied and nothing else resolves.
     */
    public static void pace(final boolean urgent, final Runnable send) {
        AndroidUtilities.runOnUIThread(() -> {
            if (urgent) {
                queue.addFirst(send);
            } else {
                queue.addLast(send);
            }
            pump();
        });
    }

    /** One resolve settled: free the lane and let the next one go, no sooner than the gap allows. */
    public static void sent() {
        AndroidUtilities.runOnUIThread(() -> {
            if (!inFlight) {
                return;   // the watchdog already freed this one
            }
            laneTicket++;
            inFlight = false;
            lastSentAt = System.currentTimeMillis();
            pump();
        });
    }

    private static void pump() {
        if (inFlight || queue.isEmpty()) {
            return;
        }
        final long waited = System.currentTimeMillis() - lastSentAt;
        if (waited < MIN_GAP_MS) {
            AndroidUtilities.runOnUIThread(SvipeChannelResolve::pump, MIN_GAP_MS - waited);
            return;
        }
        final Runnable next = queue.pollFirst();
        if (next == null) {
            return;
        }
        inFlight = true;
        lastSentAt = System.currentTimeMillis();
        final int ticket = ++laneTicket;
        AndroidUtilities.runOnUIThread(() -> {
            if (inFlight && laneTicket == ticket) {
                inFlight = false;
                pump();
            }
        }, LANE_WATCHDOG_MS);
        try {
            next.run();
        } catch (Exception e) {
            inFlight = false;
        }
    }

    /** Seconds still to wait, for anything that wants to say so out loud. 0 when not blocked. */
    public static int blockedForSeconds(int account) {
        long until = prefs().getLong(KEY_BLOCKED_UNTIL + account, 0);
        long left = until - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, (left + 999) / 1000);
    }

    /**
     * Record a resolve failure. Only a flood answer arms the gate — an ordinary error (a handle that
     * no longer exists) must not stop the app resolving everything else.
     */
    public static void noteError(int account, TLRPC.TL_error error) {
        if (error == null || error.text == null) {
            return;
        }
        int seconds = SvipeFloodWait.secondsIn(error.text);
        if (seconds <= 0) {
            return;
        }
        long until = System.currentTimeMillis() + Math.min(seconds * 1000L, MAX_WAIT_MS);
        if (until > prefs().getLong(KEY_BLOCKED_UNTIL + account, 0)) {
            prefs().edit().putLong(KEY_BLOCKED_UNTIL + account, until).apply();
        }
    }
}
