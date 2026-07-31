package org.telegram.svipe;

import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * "Saved" as a Telegram channel, not as a server-side list.
 *
 * <p>YouTube keeps Watch Later and playlists on its own servers. We do not have to: the user already
 * owns a place that stores video forever, syncs across their devices, survives a reinstall and costs
 * us nothing — their own Telegram account. So each saved list is a PRIVATE, ARCHIVED channel the app
 * creates on the user's behalf, and saving an item forwards the original post into it.
 *
 * <p>What that buys, in order of importance:
 * <ul>
 *   <li>the saved video keeps working even if the source channel deletes the post or we lose the
 *       reference — the forward is a real copy in a chat the user owns;</li>
 *   <li>no storage, no sync protocol and no privacy surface on our side: we never learn what was
 *       saved, because it never touches our backend;</li>
 *   <li>the user can open, search and share the list in Telegram itself.</li>
 * </ul>
 *
 * <p>ARCHIVED is deliberate: three service channels at the top of the chat list would be a tax on
 * every user for a feature most open occasionally. PRIVATE (no username) is deliberate too — a public
 * username would make a person's watch history findable by anyone.
 */
public final class SvipeSavedChannels {

    /** Which list. The value is also the settings key, so a rename must not change it. */
    public enum Kind {
        WATCH_LATER("watch_later", R.string.SvipeWatchLaterChannel),
        SAVED_REELS("saved_reels", R.string.SvipeSavedReelsChannel),
        SAVED_VIDEOS("saved_videos", R.string.SvipeSavedVideosChannel);

        public final String key;
        public final int titleRes;

        Kind(String key, int titleRes) {
            this.key = key;
            this.titleRes = titleRes;
        }
    }

    public interface Callback {
        /** @param chatId the channel's chat id (positive), or 0 on failure. */
        void onReady(long chatId);
    }

    private static final String PREFS = "svipe_saved_channels";
    /** Creations in flight, so a double tap cannot end up creating two "Watch Later" channels. */
    private static final HashMap<String, ArrayList<Callback>> pending = new HashMap<>();

    private SvipeSavedChannels() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, 0);
    }

    private static String prefKey(int account, Kind kind) {
        return account + ":" + kind.key;
    }

    /** The stored channel id, or 0 — public so a UI can show "open list" only when one exists. */
    public static long getChannelId(int account, Kind kind) {
        return prefs().getLong(prefKey(account, kind), 0L);
    }

    /**
     * Hand back the list's channel, creating it on first use. Idempotent and re-entrant: concurrent
     * callers queue behind one creation.
     *
     * <p>The stored id is re-validated against MessagesController every call, so a channel the user
     * deleted from Telegram is transparently recreated instead of swallowing every later save.
     */
    public static void ensureChannel(int account, Kind kind, BaseFragment fragment, Callback cb) {
        final long stored = getChannelId(account, kind);
        if (stored != 0) {
            TLRPC.Chat chat = MessagesController.getInstance(account).getChat(stored);
            if (chat != null && !ChatObject.isNotInChat(chat)) {
                cb.onReady(stored);
                return;
            }
        }
        final String key = prefKey(account, kind);
        ArrayList<Callback> waiters = pending.get(key);
        if (waiters != null) {
            waiters.add(cb);   // a creation is already in flight — ride along with it
            return;
        }
        waiters = new ArrayList<>();
        waiters.add(cb);
        pending.put(key, waiters);
        create(account, kind, fragment, key);
    }

    private static void create(int account, Kind kind, BaseFragment fragment, String key) {
        final NotificationCenter nc = NotificationCenter.getInstance(account);
        final NotificationCenter.NotificationCenterDelegate[] holder = new NotificationCenter.NotificationCenterDelegate[1];
        holder[0] = (id, acc, args) -> {
            if (id == NotificationCenter.chatDidCreated) {
                final long chatId = (Long) args[0];
                nc.removeObserver(holder[0], NotificationCenter.chatDidCreated);
                nc.removeObserver(holder[0], NotificationCenter.chatDidFailCreate);
                prefs().edit().putLong(key, chatId).apply();
                archive(account, chatId);
                finish(key, chatId);
            } else if (id == NotificationCenter.chatDidFailCreate) {
                nc.removeObserver(holder[0], NotificationCenter.chatDidCreated);
                nc.removeObserver(holder[0], NotificationCenter.chatDidFailCreate);
                finish(key, 0);
            }
        };
        nc.addObserver(holder[0], NotificationCenter.chatDidCreated);
        nc.addObserver(holder[0], NotificationCenter.chatDidFailCreate);
        // CHAT_TYPE_CHANNEL with no username -> a private broadcast channel owned by the user.
        MessagesController.getInstance(account).createChat(
                LocaleController.getString(kind.titleRes), new ArrayList<>(), null,
                ChatObject.CHAT_TYPE_CHANNEL, false, null, null, 0, fragment);
    }

    /** Move the new channel into the Archive folder so it never sits in the main chat list. */
    private static void archive(int account, long chatId) {
        AndroidUtilities.runOnUIThread(() ->
                MessagesController.getInstance(account).addDialogToFolder(-chatId, 1, -1, 0), 400);
    }

    private static void finish(String key, long chatId) {
        final ArrayList<Callback> waiters = pending.remove(key);
        if (waiters == null) {
            return;
        }
        for (Callback cb : waiters) {
            cb.onReady(chatId);
        }
    }

    /**
     * Forward a post into the list. The whole "save" operation, and the reason a list survives the
     * source channel deleting the original.
     *
     * @param mo the RESOLVED message (the app already has it — every surface that offers "save" has
     *           the {@link MessageObject} it is about).
     */
    public static void save(int account, Kind kind, MessageObject mo, BaseFragment fragment, Callback done) {
        if (mo == null) {
            if (done != null) done.onReady(0);
            return;
        }
        ensureChannel(account, kind, fragment, chatId -> {
            if (chatId == 0) {
                if (done != null) done.onReady(0);
                return;
            }
            final ArrayList<MessageObject> one = new ArrayList<>(1);
            one.add(mo);
            AndroidUtilities.runOnUIThread(() -> {
                SendMessagesHelper.getInstance(account).sendMessage(
                        one, -chatId, false, false, true, 0, 0);
                if (done != null) done.onReady(chatId);
            });
        });
    }
}
