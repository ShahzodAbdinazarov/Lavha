package org.telegram.svipe.video;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Play a public channel post WITHOUT resolving its channel.
 *
 * <p><b>Why.</b> The ordinary path to a post is {@code contacts.resolveUsername} -> {@code
 * channels.getMessages}, and the first of those two is the most flood-limited call Telegram has: a
 * channel's {@code access_hash} is issued PER ACCOUNT, so every user must pay for every channel
 * themselves, and a feed page is thirty different channels. Measured on a real account: that traffic
 * earns FLOOD_WAIT_2222, and while the window is open nothing in Svipe plays at all.
 *
 * <p><b>What this does instead.</b> {@code messages.getWebPage("https://t.me/<channel>/<id>")} — the
 * same call the chat list makes for a link preview — returns the post's DOCUMENT, with an
 * access_hash and a file_reference minted for the calling account. That is everything the player
 * needs. Measured against prod, on an account that had never seen any of the channels:
 *
 * <ul>
 *   <li>30 different channels, 30 calls, <b>0.9 s, no flood</b> (the same 30 resolveUsername calls
 *       are what put an account into a 37-minute window);</li>
 *   <li>19 of 20 returned a playable document on the first try; the twentieth was a deleted post,
 *       which is what the resolve path is still kept for;</li>
 *   <li>the file downloads: {@code upload.getFile} on the returned document served bytes.</li>
 * </ul>
 *
 * <p><b>File references.</b> The document is handed to the player with the {@link TLRPC.WebPage} as
 * its parent object, so when a reference expires {@link org.telegram.messenger.FileRefController}
 * refreshes it by calling {@code messages.getWebPage} again — no channel, no resolve, and nothing
 * new to maintain here.
 *
 * <p><b>What it does NOT give.</b> A chat. Views, reactions, "subscribe" and opening the post itself
 * still need the channel, so those stay on the resolve path — but they are now off the critical
 * path: the video plays first and the rail fills in when (and if) the paced resolve lands.
 */
public final class SvipeWebRef {

    private SvipeWebRef() {
    }

    /** The same ceiling the resolve path uses: an unbounded step leaves a reference stuck. */
    private static final long STEP_TIMEOUT_MS = 8_000;
    private static final int FAIL_FAST = ConnectionsManager.RequestFlagFailOnServerErrors;

    public interface Callback {
        /**
         * @param mo   a playable message built around the post's document, or null when the page had
         *             none (a deleted post, or a channel whose previews are off)
         * @param page the WebPage the document came from — the parent object the file loader needs to
         *             refresh an expired reference
         */
        void run(MessageObject mo, TLRPC.WebPage page);
    }

    /**
     * Pages we have fetched, keyed {@code channelId:messageId}, so an expired file_reference can be
     * refreshed through the page it came from no matter who asks. Bounded: a viewer moves through a
     * lot of posts in a session and a WebPage is not free.
     */
    private static final java.util.LinkedHashMap<String, TLRPC.WebPage> pages =
            new java.util.LinkedHashMap<String, TLRPC.WebPage>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, TLRPC.WebPage> eldest) {
                    return size() > 300;
                }
            };

    /**
     * The WebPage that must stand in for {@code parentObject} when refreshing a file reference, or
     * null when this parent has nothing to do with us.
     *
     * <p>Called from {@link org.telegram.messenger.FileRefController#requestReference}, which is the
     * one place every loader passes through — including the music player, which builds its own
     * MessageObject far from any Svipe code.
     */
    public static synchronized Object parentFor(Object parentObject) {
        long channelId = 0;
        int messageId = 0;
        if (parentObject instanceof MessageObject) {
            final MessageObject mo = (MessageObject) parentObject;
            channelId = mo.getChannelId();
            messageId = mo.getRealId();
        } else if (parentObject instanceof TLRPC.Message) {
            final TLRPC.Message m = (TLRPC.Message) parentObject;
            channelId = m.peer_id != null ? m.peer_id.channel_id : 0;
            messageId = m.id;
        }
        if (channelId == 0 || messageId == 0) {
            return null;
        }
        return pages.get(channelId + ":" + messageId);
    }

    /**
     * Channel titles the previews told us, keyed by channel id. A page for a t.me post carries the
     * CHANNEL's name in {@code title} (the caption is in {@code description}), so a page we fetched
     * to play a video also, for free, tells the rail what to call the channel while the real chat is
     * still being fetched — the difference between "@titanlar_hujumi_cloud" and "Titanlar hujumi".
     */
    private static final java.util.concurrent.ConcurrentHashMap<Long, String> titles =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** The channel's name as its own posts advertise it, or null. */
    public static String channelTitle(long channelId) {
        return channelId == 0 ? null : titles.get(channelId);
    }

    private static synchronized void remember(long channelId, int messageId, TLRPC.WebPage page) {
        if (page != null && channelId != 0 && messageId != 0) {
            pages.put(channelId + ":" + messageId, page);
            if (page.title != null && !page.title.isEmpty()) {
                titles.put(channelId, page.title);
            }
        }
    }

    /** t.me link for a public post — the only input this path needs. */
    public static String postUrl(String username, int messageId) {
        return "https://t.me/" + username + "/" + messageId;
    }

    /**
     * Fetch the post's document through its public link.
     *
     * <p>{@code channelId} is used only to stamp the synthetic message's peer, so that everything
     * downstream (telemetry keys, saved playback positions, the "already seen" set) keys the video
     * the same way whether it arrived through this path or through the resolve path.
     */
    public static void fetch(final int account, final String username, final int messageId,
                             final long channelId, final Callback cb) {
        fetch(account, username, messageId, channelId, cb, 0);
    }

    /**
     * A page Telegram has never rendered comes back EMPTY the first time and complete a moment later
     * — it fetches the preview asynchronously. Measured on prod: a cold batch answered 13 of 30 on
     * the first pass and 19 of 20 once warm, and three cold music links all went empty -> full on the
     * retry. So one retry is the difference between this path carrying most posts and carrying half
     * of them, and every miss below it costs a resolveUsername instead.
     */
    private static final long RETRY_MS = 1_500;
    private static final int MAX_ATTEMPTS = 2;

    private static void fetch(final int account, final String username, final int messageId,
                              final long channelId, final Callback cb, final int attempt) {
        if (username == null || username.isEmpty() || messageId <= 0) {
            cb.run(null, null);
            return;
        }
        final TLRPC.TL_messages_getWebPage req = new TLRPC.TL_messages_getWebPage();
        req.url = postUrl(username, messageId);
        req.hash = 0;
        final AtomicBoolean answered = new AtomicBoolean();
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (!answered.compareAndSet(false, true)) return;
            TLRPC.WebPage page = null;
            if (error == null && response instanceof TLRPC.TL_messages_webPage) {
                final TLRPC.TL_messages_webPage res = (TLRPC.TL_messages_webPage) response;
                // The users and chats a preview carries are free knowledge — a chat that arrives here
                // is usually "min" (no usable access_hash), but putting it in the controller still
                // gives the rail a title and an avatar with nothing spent.
                MessagesController.getInstance(account).putUsers(res.users, false);
                MessagesController.getInstance(account).putChats(res.chats, false);
                page = res.webpage;
            } else if (error != null) {
                FileLog.d("svipe: webpage @" + username + "/" + messageId + " failed: " + error.text);
            }
            final MessageObject mo = build(account, page, username, messageId, channelId);
            final TLRPC.WebPage parent = page;
            if (mo == null && attempt + 1 < MAX_ATTEMPTS) {
                AndroidUtilities.runOnUIThread(
                        () -> fetch(account, username, messageId, channelId, cb, attempt + 1),
                        RETRY_MS);
                return;
            }
            if (mo != null) {
                remember(channelId, messageId, parent);
            }
            AndroidUtilities.runOnUIThread(() -> cb.run(mo, mo == null ? null : parent));
        }, FAIL_FAST);
        AndroidUtilities.runOnUIThread(() -> {
            if (!answered.compareAndSet(false, true)) return;
            FileLog.d("svipe: webpage @" + username + "/" + messageId + " timed out");
            cb.run(null, null);   // a timeout is not a cold page: fall through, do not retry
        }, STEP_TIMEOUT_MS);
    }

    /**
     * Wrap the page's document in a message the player already knows how to handle.
     *
     * <p>The media is a plain {@code messageMediaDocument} rather than a webpage media on purpose:
     * every consumer downstream — duration, thumbnails, quality ladder, saved position — reads it
     * through {@link MessageObject#getDocument()}, and a document media is the shape all of them were
     * written against. The WebPage is carried separately, as the file-reference parent.
     */
    private static MessageObject build(int account, TLRPC.WebPage page, String username,
                                       int messageId, long channelId) {
        final TLRPC.Document doc = page == null ? null : page.document;
        if (doc == null
                || !(MessageObject.isVideoDocument(doc) || MessageObject.isMusicDocument(doc))) {
            return null;
        }
        final TLRPC.TL_message msg = new TLRPC.TL_message();
        msg.id = messageId;
        msg.date = (int) (System.currentTimeMillis() / 1000);   // a preview carries no post date
        msg.peer_id = new TLRPC.TL_peerChannel();
        msg.peer_id.channel_id = channelId;
        msg.from_id = new TLRPC.TL_peerChannel();
        msg.from_id.channel_id = channelId;
        msg.post = true;
        msg.out = false;
        msg.unread = false;
        msg.message = page.description != null ? page.description : "";
        final TLRPC.TL_messageMediaDocument media = new TLRPC.TL_messageMediaDocument();
        media.document = doc;
        media.flags |= 1;
        msg.media = media;
        msg.flags |= TLRPC.MESSAGE_FLAG_HAS_MEDIA;
        return new MessageObject(account, msg, false, true);
    }
}
