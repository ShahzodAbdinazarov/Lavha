package org.telegram.svipe;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Report the PUBLIC channels this account can see, so the server can index them.
 *
 * <p>Discovery used to be one server-side account walking the channel graph every few hours, which
 * is both a narrow pipe and a closed one — it can only reach what it already knows about. Every user
 * is meanwhile already a member of public channels the crawler has never seen. Submitting those
 * handles makes the index grow with the audience instead of with our crawl budget.
 *
 * <p><b>What is sent, and what is not.</b> Only the {@code @username} of PUBLIC broadcast channels.
 * Never a private chat, never a group, never a user, never a channel without a public handle, and
 * never any message content. A channel without a username could not be served to anyone anyway, so
 * there is no reason to know about it.
 *
 * <p>The server deliberately does not record WHO submitted a handle — it keeps the channel and a
 * plain counter. That is worth knowing on this side too, because it is why this can run silently:
 * the payload says "these channels exist", not "this person follows these channels".
 *
 * <p>Two triggers, matching how channels are actually encountered:
 * <ul>
 *   <li>{@link #syncAll} — the whole dialog list, at most once every {@link #FULL_SYNC_INTERVAL_MS},
 *       so a returning user costs one call a week rather than one per launch;</li>
 *   <li>{@link #submitOne} — a single channel the moment the user runs into one anywhere.</li>
 * </ul>
 */
public final class SvipeChannelSync {

    private static final String PREFS = "svipe_channel_sync";
    private static final String KEY_LAST_FULL = "last_full_sync";
    private static final String KEY_SENT = "sent_usernames";

    /** A full dialog sweep more often than this tells the server nothing it did not just learn. */
    private static final long FULL_SYNC_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000;
    /** Handles per request. The server caps its own intake; this keeps a single body small. */
    private static final int BATCH = 200;
    /**
     * Cap on the "already sent" memory. It exists to avoid re-sending the same handles every week,
     * not to be authoritative — the server dedups anyway, so forgetting is harmless.
     */
    private static final int SENT_MEMORY_MAX = 4000;

    private SvipeChannelSync() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, 0);
    }

    /**
     * Sweep the dialog list and submit every public channel found. Cheap to call on every login: it
     * returns immediately unless the interval has elapsed.
     */
    public static void syncAll(int account) {
        syncAll(account, false);
    }

    public static void syncAll(int account, boolean force) {
        final long now = System.currentTimeMillis();
        if (!force && now - prefs().getLong(KEY_LAST_FULL, 0L) < FULL_SYNC_INTERVAL_MS) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            final List<String> usernames = collectPublicChannels(account);
            if (usernames.isEmpty()) {
                return;
            }
            prefs().edit().putLong(KEY_LAST_FULL, now).apply();
            submit(account, usernames);
        });
    }

    /** Report one channel the user just ran into — a share link, a forward, an opened profile. */
    public static void submitOne(int account, String username) {
        if (username == null || username.isEmpty()) {
            return;
        }
        final String clean = username.trim().replace("@", "").toLowerCase();
        if (clean.length() < 5) {
            return;
        }
        Set<String> sent = new HashSet<>(prefs().getStringSet(KEY_SENT, new HashSet<>()));
        if (sent.contains(clean)) {
            return;
        }
        List<String> one = new ArrayList<>(1);
        one.add(clean);
        submit(account, one);
    }

    /** Report the channel behind a chat object, if it is public. */
    public static void submitChat(int account, TLRPC.Chat chat) {
        if (chat == null || !ChatObject.isChannel(chat) || chat.megagroup) {
            return;
        }
        submitOne(account, ChatObject.getPublicUsername(chat));
    }

    // ---------------------------------------------------------------------------------------------

    private static List<String> collectPublicChannels(int account) {
        final List<String> out = new ArrayList<>();
        try {
            final MessagesController controller = MessagesController.getInstance(account);
            for (TLRPC.Dialog dialog : controller.getAllDialogs()) {
                if (dialog == null || dialog.id >= 0) {
                    continue;                       // users and small groups have positive ids
                }
                final TLRPC.Chat chat = controller.getChat(-dialog.id);
                if (chat == null || !ChatObject.isChannel(chat) || chat.megagroup) {
                    continue;                       // groups are not a content source
                }
                final String username = ChatObject.getPublicUsername(chat);
                if (username != null && !username.isEmpty()) {
                    out.add(username.toLowerCase());
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return out;
    }

    private static void submit(int account, List<String> usernames) {
        final Set<String> sent = new HashSet<>(prefs().getStringSet(KEY_SENT, new HashSet<>()));
        final List<String> fresh = new ArrayList<>();
        for (String u : usernames) {
            if (!sent.contains(u)) {
                fresh.add(u);
            }
        }
        if (fresh.isEmpty()) {
            return;
        }
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                return;
            }
            for (int start = 0; start < fresh.size(); start += BATCH) {
                final List<String> slice = fresh.subList(start, Math.min(start + BATCH, fresh.size()));
                postBatch(token, slice, () -> remember(slice));
            }
        });
    }

    private static void postBatch(String token, List<String> slice, Runnable onOk) {
        try {
            JSONObject body = new JSONObject();
            body.put("usernames", new JSONArray(slice));
            SvipeApi.post("/v1/channels/submit", body, token, (res, code, err) -> {
                if (code >= 200 && code < 300) {
                    onOk.run();
                }
            });
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void remember(List<String> slice) {
        Set<String> sent = new HashSet<>(prefs().getStringSet(KEY_SENT, new HashSet<>()));
        sent.addAll(slice);
        if (sent.size() > SENT_MEMORY_MAX) {
            sent = new HashSet<>(new ArrayList<>(sent).subList(0, SENT_MEMORY_MAX));
        }
        prefs().edit().putStringSet(KEY_SENT, sent).apply();
    }
}
