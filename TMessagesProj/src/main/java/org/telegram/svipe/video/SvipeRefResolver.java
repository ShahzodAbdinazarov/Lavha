package org.telegram.svipe.video;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.svipe.SvipeDiscover;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * Turns a Svipe backend reference (channel username + message id) into a playable Telegram
 * {@link MessageObject} + {@link TLRPC.Chat} over MTProto: resolveUsername -> channels.getMessages.
 *
 * Extracted verbatim from ReelsActivity so the long-form player resolves through the same two
 * round-trips AND the same in-flight-callback-queue discipline: every caller that asks for a
 * reference while a resolve is already in flight is queued and woken on completion. Dropping that
 * queue is exactly how the reels player used to lose a start-playback intent and spin forever until
 * a manual skip-and-back.
 *
 * The RETRY policy stays with the caller ({@link Delegate#onFailed}): reels retries only what a
 * user is actually waiting on, and knows about its pager position — this class does not.
 */
public class SvipeRefResolver {

    /**
     * The mutable resolve state of one reference. Implemented by whatever the caller already uses as
     * its feed item (ReelsActivity.FeedItem, {@link VideoRef}) so extraction needed no data move.
     */
    public interface Ref {
        long channelId();
        int messageId();
        String username();

        MessageObject message();
        void setMessage(MessageObject mo);
        TLRPC.Chat chat();
        void setChat(TLRPC.Chat chat);

        boolean isResolving();
        void setResolving(boolean resolving);

        /** Waiters for an in-flight resolve — never dropped. Must be the same list instance every call. */
        ArrayList<Runnable> resolveCallbacks();
    }

    public interface Delegate {
        /**
         * The reference resolved. Called on the UI thread with message+chat already set and BEFORE
         * queued waiters are drained, so the caller can enrich its own state (like counts, preloads)
         * first.
         */
        void onResolved(Ref ref);

        /**
         * A resolve attempt failed. {@code retryable} marks a transient (network) failure; a data
         * failure (message gone, not a video) is final. Called on the connection thread — the caller
         * owns both the retry policy and the hop to the UI thread.
         */
        void onFailed(Ref ref, boolean retryable);
    }

    /** A standalone reference — the long-form player's item, built from the backend's video list. */
    public static class VideoRef implements Ref {
        public long channelId;
        public int messageId;
        public String username;
        public String shareUrl;     // owned svipe.uz/<code> preview link, supplied by the backend
        public Integer topicId;
        public String recId;        // recommendation_id of the page this item arrived with
        /** Not a public channel post — see {@link SvipeDiscover.Item#local}. Nothing about it is posted. */
        public boolean local;
        public MessageObject mo;    // filled after MTProto resolution
        public TLRPC.Chat chat;
        public boolean resolving;
        public final ArrayList<Runnable> resolveCallbacks = new ArrayList<>();

        public static VideoRef of(SvipeDiscover.Item item) {
            VideoRef r = new VideoRef();
            r.channelId = item.channelId;
            r.messageId = item.messageId;
            r.username = item.username;
            r.shareUrl = item.shareUrl;
            r.topicId = item.topicId;
            r.local = item.local;
            return r;
        }

        public boolean sameAs(Ref other) {
            return other != null && other.channelId() == channelId && other.messageId() == messageId;
        }

        @Override public long channelId() { return channelId; }
        @Override public int messageId() { return messageId; }
        @Override public String username() { return username; }
        @Override public MessageObject message() { return mo; }
        @Override public void setMessage(MessageObject m) { mo = m; }
        @Override public TLRPC.Chat chat() { return chat; }
        @Override public void setChat(TLRPC.Chat c) { chat = c; }
        @Override public boolean isResolving() { return resolving; }
        @Override public void setResolving(boolean r) { resolving = r; }
        @Override public ArrayList<Runnable> resolveCallbacks() { return resolveCallbacks; }
    }

    private SvipeRefResolver() {}

    /**
     * Every MTProto step here is bounded. Two reasons, both measured: tgnet swallows a 420 FLOOD_WAIT
     * unless RequestFlagFailOnServerErrors is set (it re-queues the request behind the wait — seen at
     * 74 minutes — and the callback simply never runs), and contacts.resolveUsername is exactly the
     * call a fresh account floods on first, since the feed resolves one channel per item. An unbounded
     * resolve leaves {@code resolving} true forever, so every later attempt returns early at the
     * in-flight check and the reel spins until the app is restarted.
     */
    private static final long STEP_TIMEOUT_MS = 8_000;
    private static final int FAIL_FAST = ConnectionsManager.RequestFlagFailOnServerErrors;

    // ---- channel cache: one resolveUsername per channel, not per item ----

    /** Channels we have already resolved, per account. Cleared only by an explicit forget. */
    private static final java.util.concurrent.ConcurrentHashMap<Integer, java.util.concurrent.ConcurrentHashMap<String, TLRPC.Chat>>
            chatCache = new java.util.concurrent.ConcurrentHashMap<>();
    /** Callers waiting on a resolveUsername that is already in the air, keyed {@code account:username}. */
    private static final java.util.HashMap<String, ArrayList<ChatCallback>> pendingResolves = new java.util.HashMap<>();

    /** Receives the channel, or null when it could not be resolved this time. */
    public interface ChatCallback {
        void run(TLRPC.Chat chat);
    }

    private static java.util.concurrent.ConcurrentHashMap<String, TLRPC.Chat> cacheFor(int account) {
        java.util.concurrent.ConcurrentHashMap<String, TLRPC.Chat> map = chatCache.get(account);
        if (map == null) {
            map = new java.util.concurrent.ConcurrentHashMap<>();
            java.util.concurrent.ConcurrentHashMap<String, TLRPC.Chat> prev = chatCache.putIfAbsent(account, map);
            if (prev != null) map = prev;
        }
        return map;
    }

    /**
     * A channel we can use without asking the server — ours, or one Telegram already resolved for
     * some other part of the app. A "min" chat is refused: its access_hash is not usable for
     * channels.getMessages, and caching one would turn a cheap win into a permanent failure.
     */
    private static TLRPC.Chat cachedChat(int account, String username) {
        TLRPC.Chat mine = cacheFor(account).get(username);
        if (mine != null) return mine;
        try {
            TLObject known = MessagesController.getInstance(account).getUserOrChat(username);
            if (known instanceof TLRPC.Chat) {
                TLRPC.Chat chat = (TLRPC.Chat) known;
                if (!chat.min && chat.access_hash != 0) {
                    cacheFor(account).put(username, chat);
                    return chat;
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    /**
     * Forget a channel — call this when the server rejects the peer we cached, so the next attempt
     * pays for a fresh resolve instead of repeating a request that can no longer work.
     */
    public static void forgetChat(int account, String username) {
        if (username == null) return;
        cacheFor(account).remove(username.toLowerCase());
    }

    /**
     * The channel behind a username, resolved at most once.
     *
     * Two savings, and the second is the one that matters: repeat resolves of the same channel are
     * served from memory, and callers arriving while a resolve is in the air WAIT for it instead of
     * firing their own. The feed hands out several items per channel and more than one surface asks
     * about the same item (the pager, the read-ahead, a poster binder), so the old code sent the
     * same contacts.resolveUsername twice within milliseconds — measured on a device. That call is
     * the most flood-limited one we make, and a flood on it is what used to take the whole Svipe
     * layer down until the app was restarted (see SvipeAuth).
     */
    private static void withChat(final int account, final String username, final long channelId,
                                 final ChatCallback cb) {
        TLRPC.Chat cached = cachedChat(account, username);
        if (cached != null) {
            cb.run(cached);
            return;
        }
        // Nothing in memory. Before paying for a resolve, ask the local database: a channel resolved
        // in ANY previous session is stored there, and reading it costs no network. Without this the
        // whole cache above is per-process, so every cold start re-resolves every channel the feed
        // mentions — the burst that earns the hours-long FLOOD_WAIT (see SvipeChannelResolve).
        org.telegram.svipe.SvipeChannelResolve.lookup(account, channelId, local -> {
            if (local != null) {
                cacheFor(account).put(username, local);
                cb.run(local);
                return;
            }
            sendResolve(account, username, channelId, cb);
        });
    }

    private static void sendResolve(final int account, final String username, final long channelId,
                                    final ChatCallback cb) {
        if (org.telegram.svipe.SvipeChannelResolve.blocked(account)) {
            // Inside an open flood window. Asking again is what makes Telegram extend it.
            FileLog.d("svipe: resolve @" + username + " skipped, flood window open");
            cb.run(null);
            return;
        }
        final String key = account + ":" + username;
        synchronized (pendingResolves) {
            ArrayList<ChatCallback> waiters = pendingResolves.get(key);
            if (waiters != null) { // someone already asked — ride along on their answer
                waiters.add(cb);
                return;
            }
            waiters = new ArrayList<>();
            waiters.add(cb);
            pendingResolves.put(key, waiters);
        }
        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = username;
        final java.util.concurrent.atomic.AtomicBoolean answered = new java.util.concurrent.atomic.AtomicBoolean();
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (!answered.compareAndSet(false, true)) return; // the timeout already gave up on this one
            TLRPC.Chat chat = null;
            if (error == null && response instanceof TLRPC.TL_contacts_resolvedPeer) {
                TLRPC.TL_contacts_resolvedPeer rp = (TLRPC.TL_contacts_resolvedPeer) response;
                // Persisted as well as cached, so the next launch does not pay for this again.
                org.telegram.svipe.SvipeChannelResolve.remember(account, rp);
                if (rp.chats != null) {
                    for (int i = 0; i < rp.chats.size(); i++) {
                        if (rp.chats.get(i).id == channelId) { chat = rp.chats.get(i); break; }
                    }
                    if (chat == null && !rp.chats.isEmpty()) chat = rp.chats.get(0);
                }
                if (chat != null) cacheFor(account).put(username, chat);
            } else if (error != null) {
                org.telegram.svipe.SvipeChannelResolve.noteError(account, error);
                FileLog.d("svipe: resolve @" + username + " failed: " + error.text);
            }
            drainResolve(key, chat);
        }, FAIL_FAST);
        AndroidUtilities.runOnUIThread(() -> {
            if (!answered.compareAndSet(false, true)) return;
            FileLog.d("svipe: resolve @" + username + " timed out");
            drainResolve(key, null);
        }, STEP_TIMEOUT_MS);
    }

    private static void drainResolve(String key, TLRPC.Chat chat) {
        final ArrayList<ChatCallback> waiters;
        synchronized (pendingResolves) {
            waiters = pendingResolves.remove(key);
        }
        if (waiters == null) return;
        for (int i = 0; i < waiters.size(); i++) {
            try { waiters.get(i).run(chat); } catch (Exception e) { FileLog.e(e); }
        }
    }

    /**
     * Resolve a reference's channel + message over MTProto and cache the MessageObject on the Ref.
     * Reused for both the visible item (then play) and read-ahead prefetch (no play). Idempotent:
     * skips if already resolved or a resolve is in flight.
     *
     * @param delegate optional — pass null when the caller only wants the {@code onResolved} runnable
     *                 (a poster or avatar binder has no retry policy to run). Every dispatch below is
     *                 null-guarded for exactly that case.
     */
    public static void resolve(final int account, final Ref ref, final Runnable onResolved, final Delegate delegate) {
        if (ref.message() != null && ref.chat() != null) { if (onResolved != null) onResolved.run(); return; }
        // Queue-restored item: we already hold a playable MessageObject, only the chat is missing
        // (needed for the action rail). That is a cache lookup now, usually free.
        if (ref.message() != null && ref.chat() == null) { resolveChatOnly(account, ref, onResolved); return; }
        // Full resolve (no mo yet). Queue the caller's callback so an already-in-flight resolve (e.g.
        // one started by prefetch/read-ahead) still notifies THIS caller when it completes — otherwise
        // the caller's start-playback intent is silently dropped and the video spins forever until a
        // manual skip-and-back.
        if (onResolved != null) ref.resolveCallbacks().add(onResolved);
        if (ref.isResolving()) return;
        ref.setResolving(true);
        final String username = ref.username().toLowerCase();
        withChat(account, username, ref.channelId(), chat -> {
            if (chat == null) {
                fail(ref, delegate, true); // network/flood/timeout — the caller's policy decides
                return;
            }
            fetchMessage(account, ref, username, chat, delegate);
        });
    }

    /** Second round-trip: the post itself. */
    private static void fetchMessage(final int account, final Ref ref, final String username,
                                     final TLRPC.Chat chat, final Delegate delegate) {
        TLRPC.TL_inputChannel inputChannel = new TLRPC.TL_inputChannel();
        inputChannel.channel_id = chat.id;
        inputChannel.access_hash = chat.access_hash;
        TLRPC.TL_channels_getMessages gm = new TLRPC.TL_channels_getMessages();
        gm.channel = inputChannel;
        gm.id.add(ref.messageId());
        final java.util.concurrent.atomic.AtomicBoolean fetched = new java.util.concurrent.atomic.AtomicBoolean();
        ConnectionsManager.getInstance(account).sendRequest(gm, (resp2, err2) -> {
            if (!fetched.compareAndSet(false, true)) return; // the timeout already reported this step
            if (err2 != null || !(resp2 instanceof TLRPC.messages_Messages)) {
                if (err2 != null && isStalePeer(err2.text)) {
                    // The peer we cached is no longer usable. Drop it so the retry re-resolves
                    // instead of repeating a request that can only fail the same way.
                    forgetChat(account, username);
                }
                fail(ref, delegate, true); // transient network failure — retry
                return;
            }
            TLRPC.messages_Messages mm = (TLRPC.messages_Messages) resp2;
            MessagesController.getInstance(account).putUsers(mm.users, false);
            MessagesController.getInstance(account).putChats(mm.chats, false);
            if (mm.messages == null || mm.messages.isEmpty()) {
                fail(ref, delegate, false); // message gone — give up
                return;
            }
            final MessageObject mo = new MessageObject(account, mm.messages.get(0), false, true);
            TLRPC.Document doc = mo.getDocument();
            if (doc == null || !MessageObject.isVideoDocument(doc)) {
                fail(ref, delegate, false); // not a playable video — give up
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                ref.setResolving(false);
                ref.setMessage(mo);
                ref.setChat(chat);
                if (delegate != null) delegate.onResolved(ref);
                drainCallbacks(ref); // wakes the caller's queued start-playback intent
            });
        }, FAIL_FAST);
        AndroidUtilities.runOnUIThread(() -> {
            if (!fetched.compareAndSet(false, true)) return;
            FileLog.d("svipe: getMessages timed out for @" + username + "/" + ref.messageId());
            fail(ref, delegate, true);
        }, STEP_TIMEOUT_MS);
    }

    /** Errors that mean "the peer you used is wrong", as opposed to "the network was". */
    private static boolean isStalePeer(String error) {
        if (error == null) return false;
        return error.contains("CHANNEL_INVALID") || error.contains("PEER_ID_INVALID")
                || error.contains("CHANNEL_PRIVATE") || error.contains("USERNAME_NOT_OCCUPIED");
    }

    /**
     * Release the in-flight flag and report. The flag is cleared HERE rather than left to the
     * delegate: a caller that passes none (a poster binder) would otherwise leave the reference
     * marked resolving forever, and every later attempt would return early at that check.
     */
    private static void fail(final Ref ref, final Delegate delegate, final boolean retryable) {
        ref.setResolving(false);
        if (delegate != null) {
            delegate.onFailed(ref, retryable);
        } else {
            drainCallbacks(ref);
        }
    }

    /** Fill only the missing {@code chat} on a queue-restored item — from cache when we have it. */
    public static void resolveChatOnly(final int account, final Ref ref, final Runnable onResolved) {
        if (ref.isResolving()) return;
        ref.setResolving(true);
        withChat(account, ref.username().toLowerCase(), ref.channelId(), chat ->
                AndroidUtilities.runOnUIThread(() -> {
                    ref.setResolving(false);
                    if (chat != null) ref.setChat(chat);
                    if (onResolved != null) onResolved.run();
                }));
    }

    /** Run and clear every queued waiter for this reference (safe on success or on give-up). */
    public static void drainCallbacks(Ref ref) {
        ArrayList<Runnable> queue = ref.resolveCallbacks();
        if (queue.isEmpty()) return;
        ArrayList<Runnable> cbs = new ArrayList<>(queue);
        queue.clear();
        for (int i = 0; i < cbs.size(); i++) {
            try { cbs.get(i).run(); } catch (Exception e) { FileLog.e(e); }
        }
    }
}
