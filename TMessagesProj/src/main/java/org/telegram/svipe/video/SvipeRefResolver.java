package org.telegram.svipe.video;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.svipe.SvipeDiscover;
import org.telegram.tgnet.ConnectionsManager;
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
     * Resolve a reference's channel + message over MTProto (2 round-trips) and cache the
     * MessageObject on the Ref. Reused for both the visible item (then play) and read-ahead prefetch
     * (no play). Idempotent: skips if already resolved or a resolve is in flight.
     */
    public static void resolve(final int account, final Ref ref, final Runnable onResolved, final Delegate delegate) {
        if (ref.message() != null && ref.chat() != null) { if (onResolved != null) onResolved.run(); return; }
        // Queue-restored item: we already hold a playable MessageObject, only the chat is missing
        // (needed for the action rail). Fill it with one resolveUsername round-trip — skip getMessages.
        if (ref.message() != null && ref.chat() == null) { resolveChatOnly(account, ref, onResolved); return; }
        // Full resolve (no mo yet). Queue the caller's callback so an already-in-flight resolve (e.g.
        // one started by prefetch/read-ahead) still notifies THIS caller when it completes — otherwise
        // the caller's start-playback intent is silently dropped and the video spins forever until a
        // manual skip-and-back.
        if (onResolved != null) ref.resolveCallbacks().add(onResolved);
        if (ref.isResolving()) return;
        ref.setResolving(true);
        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = ref.username().toLowerCase();
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (error != null || !(response instanceof TLRPC.TL_contacts_resolvedPeer)) {
                delegate.onFailed(ref, true); // transient network failure — retry
                return;
            }
            TLRPC.TL_contacts_resolvedPeer rp = (TLRPC.TL_contacts_resolvedPeer) response;
            MessagesController.getInstance(account).putUsers(rp.users, false);
            MessagesController.getInstance(account).putChats(rp.chats, false);
            TLRPC.Chat chat = null;
            if (rp.chats != null) {
                for (int i = 0; i < rp.chats.size(); i++) {
                    if (rp.chats.get(i).id == ref.channelId()) { chat = rp.chats.get(i); break; }
                }
                if (chat == null && !rp.chats.isEmpty()) chat = rp.chats.get(0);
            }
            if (chat == null) {
                delegate.onFailed(ref, false); // channel not found — give up
                return;
            }
            final TLRPC.Chat fchat = chat;

            TLRPC.TL_inputChannel inputChannel = new TLRPC.TL_inputChannel();
            inputChannel.channel_id = chat.id;
            inputChannel.access_hash = chat.access_hash;
            TLRPC.TL_channels_getMessages gm = new TLRPC.TL_channels_getMessages();
            gm.channel = inputChannel;
            gm.id.add(ref.messageId());
            ConnectionsManager.getInstance(account).sendRequest(gm, (resp2, err2) -> {
                if (err2 != null || !(resp2 instanceof TLRPC.messages_Messages)) {
                    delegate.onFailed(ref, true); // transient network failure — retry
                    return;
                }
                TLRPC.messages_Messages mm = (TLRPC.messages_Messages) resp2;
                MessagesController.getInstance(account).putUsers(mm.users, false);
                MessagesController.getInstance(account).putChats(mm.chats, false);
                if (mm.messages == null || mm.messages.isEmpty()) {
                    delegate.onFailed(ref, false); // message gone — give up
                    return;
                }
                final MessageObject mo = new MessageObject(account, mm.messages.get(0), false, true);
                TLRPC.Document doc = mo.getDocument();
                if (doc == null || !MessageObject.isVideoDocument(doc)) {
                    delegate.onFailed(ref, false); // not a playable video — give up
                    return;
                }
                AndroidUtilities.runOnUIThread(() -> {
                    ref.setResolving(false);
                    ref.setMessage(mo);
                    ref.setChat(fchat);
                    delegate.onResolved(ref);
                    drainCallbacks(ref); // wakes the caller's queued start-playback intent
                });
            });
        });
    }

    /** Fill only the missing {@code chat} on a queue-restored item (one resolveUsername round-trip). */
    public static void resolveChatOnly(final int account, final Ref ref, final Runnable onResolved) {
        if (ref.isResolving()) return;
        ref.setResolving(true);
        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = ref.username().toLowerCase();
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            AndroidUtilities.runOnUIThread(() -> {
                ref.setResolving(false);
                if (error == null && response instanceof TLRPC.TL_contacts_resolvedPeer) {
                    TLRPC.TL_contacts_resolvedPeer rp = (TLRPC.TL_contacts_resolvedPeer) response;
                    MessagesController.getInstance(account).putUsers(rp.users, false);
                    MessagesController.getInstance(account).putChats(rp.chats, false);
                    if (rp.chats != null) {
                        for (int i = 0; i < rp.chats.size(); i++) {
                            if (rp.chats.get(i).id == ref.channelId()) { ref.setChat(rp.chats.get(i)); break; }
                        }
                        if (ref.chat() == null && !rp.chats.isEmpty()) ref.setChat(rp.chats.get(0));
                    }
                }
                if (onResolved != null) onResolved.run();
            });
        });
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
