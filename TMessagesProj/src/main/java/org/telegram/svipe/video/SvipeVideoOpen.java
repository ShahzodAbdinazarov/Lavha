package org.telegram.svipe.video;

import android.graphics.Rect;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.svipe.SvipeDiscover;
import org.telegram.svipe.SvipeVideoIndex;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.PhotoViewer;
import org.telegram.ui.SvipeWatchActivity;

/**
 * Open ANY video the user runs into anywhere in the app in our long-form watch page.
 *
 * <p>Every entry point (the chat long-press menu, the photo viewer's ⋮, whatever comes next) goes
 * through here, so the two decisions that must never disagree are made exactly once:
 *
 * <ul>
 *   <li><b>Can this be played at all</b> — {@link #canWatch}, the same test
 *       {@link SvipeRefResolver} applies before it hands a message to the player;</li>
 *   <li><b>Is this video PUBLIC</b> — {@link #sourceOf}. A public channel post is one anyone can
 *       open at {@code t.me/<handle>/<id>}: it is submitted for indexing and measured like any
 *       video from the Video tab. Anything else is LOCAL — a private chat, a group, a private
 *       channel, saved messages — and nothing that identifies it may reach our server. The watch
 *       page enforces that from the {@code local} flag this class sets on the reference.</li>
 * </ul>
 *
 * <p>The page is seeded with the message we already hold, so opening costs no MTProto round-trip:
 * the video the user is looking at is the video that starts playing.
 */
public final class SvipeVideoOpen {

    private SvipeVideoOpen() {
    }

    /**
     * Whether this message is a video our player can actually stream. Deliberately strict — the menu
     * item exists only where the tap is guaranteed to lead somewhere.
     */
    public static boolean canWatch(MessageObject mo) {
        if (mo == null || mo.messageOwner == null) {
            return false;
        }
        // Still on its way out: a message that has not been sent has no server-side document to
        // stream, and its id is negative, so nothing could address it afterwards either.
        if (mo.getId() <= 0 || mo.scheduled || mo.isSending() || mo.isSendError()
                || mo.messageOwner.video_processing_pending) {
            return false;
        }
        // Secret media is excluded for a hard reason rather than a squeamish one: the playback ladder
        // resolves a file reference on an ordinary cloud document (SvipeVideoLadder), which a
        // TL_documentEncrypted is not, and a self-destructing video must not outlive its viewer.
        if (mo.isSecret() || mo.isSecretMedia() || mo.isStoryMedia()) {
            return false;
        }
        if (mo.isRoundVideo() || mo.isGif()) {
            return false;   // a round message and a soundless animation are not what this page is for
        }
        final TLRPC.Document doc = mo.getDocument();
        return mo.isVideo() && doc instanceof TLRPC.TL_document && MessageObject.isVideoDocument(doc);
    }

    /**
     * Open {@code mo} in the watch page, growing the picture out of {@code openFromRect} (window
     * coordinates of the thumbnail that was tapped; null is fine — the picture then appears in place).
     */
    public static void open(BaseFragment from, MessageObject mo, Rect openFromRect) {
        if (!canWatch(mo)) {
            return;
        }
        final int account = mo.currentAccount;
        final Source source = sourceOf(account, mo);
        final SvipeDiscover.Item item = itemOf(mo, source);
        final SvipeWatchActivity page =
                SvipeWatchActivity.seeded(item, mo, source.chat, source.username == null);
        if (openFromRect != null && !openFromRect.isEmpty()) {
            page.setOpenFromRect(openFromRect);
        }
        final SvipeVideoPlayerController controller = SvipeVideoPlayerController.getInstance();
        if (controller.getWatchPage() != null) {
            // Opening over a live watch page is a HANDOVER, not that page being hidden — without this
            // the player drops to the mini bar on the way to the page that is about to host it.
            controller.expectHandover();
        }
        if (from != null && from.getParentActivity() != null) {
            from.presentFragment(page);
        } else if (LaunchActivity.instance != null) {
            LaunchActivity.instance.presentFragment(page);
        } else {
            return;   // nowhere to present it: do not submit a video nobody is about to watch
        }
        if (source.username != null) {
            // The other half of the feature: a video anyone can open is a video our server can index,
            // so running into one anywhere in the app is what grows the catalogue.
            SvipeVideoIndex.submit(account, item.channelId, item.messageId, source.username);
        }
    }

    /**
     * The same open for a caller with no fragment in hand. The photo viewer owns its own window ABOVE
     * the fragment stack, so it has to be taken down first or the watch page (and the player overlay
     * with it) opens underneath it and the tap looks like it did nothing.
     */
    public static void openFromPhotoViewer(MessageObject mo, Rect openFromRect) {
        if (!canWatch(mo)) {
            return;
        }
        final Rect from = openFromRect == null ? null : new Rect(openFromRect);
        if (PhotoViewer.hasInstance()) {
            PhotoViewer.getInstance().closePhoto(false, false);
        }
        // One frame later: closePhoto tears its window down synchronously, and presenting a fragment
        // inside that teardown makes the navigation layout animate against a window that is going away.
        AndroidUtilities.runOnUIThread(() -> open(null, mo, from));
    }

    // ---------------- public channel or not: the one decision both entry points share ----------------

    /** Where the video really lives. {@code username == null} means LOCAL — see the class doc. */
    private static class Source {
        long channelId;
        int messageId;
        String username;
        /**
         * The chat that posted it, or null for a user dialog. Never a user: the watch page passes
         * this id to ChatActivity as a {@code chat_id}, and chat ids and user ids share one number
         * space, so a user id there would open an unrelated chat.
         */
        TLRPC.Chat chat;
    }

    private static Source sourceOf(int account, MessageObject mo) {
        final Source out = new Source();
        final MessagesController controller = MessagesController.getInstance(account);
        final TLRPC.Message message = mo.messageOwner;
        // A forward is resolved to the ORIGINAL post rather than to the chat it landed in: that is the
        // same video, and it is the copy the server can address, index and serve to everyone else.
        // Without channel_post there is no original post id to point at, so there is nothing to prefer.
        if (message.fwd_from != null && message.fwd_from.from_id instanceof TLRPC.TL_peerChannel
                && message.fwd_from.channel_post != 0) {
            final TLRPC.Chat origin = controller.getChat(message.fwd_from.from_id.channel_id);
            final String username = publicUsername(origin);
            if (username != null) {
                out.channelId = origin.id;
                out.messageId = message.fwd_from.channel_post;
                out.username = username;
                out.chat = origin;
                return out;
            }
        }
        // Otherwise the message where the user found it. A post sitting in a public channel is public
        // whatever it was forwarded from — it is exactly the row our own crawler stores for it.
        final long chatId = MessageObject.getChatId(message);
        final TLRPC.Chat chat = chatId != 0 ? controller.getChat(chatId) : null;
        out.chat = chat;
        out.messageId = mo.getId();
        // channelId IS chat.id here, not the negated dialog id — the convention the whole reference
        // model follows. A user dialog has no chat, so the dialog stands in as the identity; it is
        // never sent anywhere, it only has to tell two open videos apart.
        out.channelId = chat != null ? chat.id : Math.abs(mo.getDialogId());
        out.username = publicUsername(chat);
        return out;
    }

    /**
     * The handle a video can be served under, or null. Broadcast channels only: a group is not a
     * content source (the same rule {@code SvipeChannelSync} submits channels by), and a channel with
     * no public handle is one nobody outside it could open anyway.
     */
    private static String publicUsername(TLRPC.Chat chat) {
        if (chat == null || !ChatObject.isChannel(chat) || chat.megagroup) {
            return null;
        }
        final String username = ChatObject.getPublicUsername(chat);
        return username == null || username.isEmpty() ? null : username;
    }

    private static SvipeDiscover.Item itemOf(MessageObject mo, Source source) {
        final SvipeDiscover.Item item = new SvipeDiscover.Item();
        item.channelId = source.channelId;
        item.messageId = source.messageId;
        item.username = source.username;
        item.local = source.username == null;
        // Pixel size and length come off the document, exactly the fields a server-sent reference
        // carries, so the page sizes its picture BEFORE the first frame: a phone-shot 9:16 clip opens
        // as a portrait picture instead of a stamp between two pillarbox bars.
        final TLRPC.Document doc = mo.getDocument();
        if (doc != null) {
            for (int i = 0; i < doc.attributes.size(); i++) {
                final TLRPC.DocumentAttribute a = doc.attributes.get(i);
                if (a instanceof TLRPC.TL_documentAttributeVideo) {
                    item.width = a.w;
                    item.height = a.h;
                    item.durationMs = (int) Math.max(0, a.duration * 1000.0);
                    break;
                }
            }
        }
        return item;
    }
}
