package org.telegram.svipe;

import android.content.SharedPreferences;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-chat exceptions by KIND of message: the chat stays unmuted, one kind of message in it goes
 * silent. Today the only kind is a forward.
 *
 * Unlike {@link SvipeBotMute}, this cannot be pushed down to Telegram's own per-peer mute — the
 * server has no field for "mute forwards", and muting the peer would silence everything. So the
 * rule is enforced in the client ({@link NotificationsController#isMutedMessageType}) and carried
 * to the user's other Svipe installs by us, through {@link SvipeSettingsSync}.
 *
 * The flags themselves live where every other per-dialog notification preference lives, so the
 * settings screen and the notification path read one source of truth.
 */
public final class SvipeMessageTypeMute {

    private SvipeMessageTypeMute() {}

    /** The same switch written for a class of chats rather than one: the scope replaces the id. */
    public static boolean isMutedScope(int account, String prefix, String scope) {
        return notifications(account).getBoolean(prefix + scope, false);
    }

    public static void setMutedScope(int account, String prefix, String scope, boolean on) {
        SharedPreferences.Editor e = notifications(account).edit();
        if (on) {
            e.putBoolean(prefix + scope, true);
        } else {
            e.remove(prefix + scope);
        }
        e.apply();
        touch(account);
        SvipeSettingsSync.push(account);
    }

    /** The name and the icon of a kind, shared by every screen that lists them. */
    public static int labelOf(String kind) {
        switch (kind) {
            case "links": return R.string.SvipeTypeLinks;
            case "media": return R.string.SvipeTypeMedia;
            case "voice": return R.string.SvipeTypeVoice;
            case "stickers": return R.string.SvipeTypeStickers;
            case "files": return R.string.SvipeTypeFiles;
        }
        return R.string.SvipeTypeForwards;
    }

    public static int iconOf(String kind) {
        switch (kind) {
            case "links": return R.drawable.msg_link;
            case "media": return R.drawable.msg_filled_data_photos;
            case "voice": return R.drawable.msg_filled_data_voice;
            case "stickers": return R.drawable.msg_emoji_stickers;
            case "files": return R.drawable.msg_filled_data_files;
        }
        return R.drawable.msg_forward;
    }

    public static boolean isMuted(int account, String prefix, long dialogId, long topicId) {
        return notifications(account).getBoolean(prefixed(prefix, dialogId, topicId), false);
    }

    public static void setMuted(int account, String prefix, long dialogId, long topicId, boolean on) {
        SharedPreferences.Editor e = notifications(account).edit();
        if (on) {
            e.putBoolean(prefixed(prefix, dialogId, topicId), true);
        } else {
            e.remove(prefixed(prefix, dialogId, topicId));
        }
        e.apply();
        touch(account);
        SvipeSettingsSync.push(account);
    }

    public static boolean isForwardsMuted(int account, long dialogId, long topicId) {
        return isMuted(account, NotificationsController.MUTE_FORWARDS_PREFIX, dialogId, topicId);
    }

    public static void setForwardsMuted(int account, long dialogId, long topicId, boolean muted) {
        setMuted(account, NotificationsController.MUTE_FORWARDS_PREFIX, dialogId, topicId, muted);
    }

    public static boolean isForwardsNotified(int account, long dialogId, long topicId) {
        return isMuted(account, NotificationsController.NOTIFY_FORWARDS_PREFIX, dialogId, topicId);
    }

    public static void setForwardsNotified(int account, long dialogId, long topicId, boolean notify) {
        setMuted(account, NotificationsController.NOTIFY_FORWARDS_PREFIX, dialogId, topicId, notify);
    }

    public static List<String> dialogsFor(int account, String prefix) {
        return dialogsWith(account, prefix);
    }

    /**
     * Adopt one list from another device. Whatever is not on it is no longer set, so a switch turned
     * OFF elsewhere travels just as well as one turned on. Does not push back.
     */
    public static void adoptList(int account, String prefix, List<String> dialogs, long updatedAt) {
        Set<String> wanted = new HashSet<>(dialogs);
        SharedPreferences.Editor e = notifications(account).edit();
        for (String id : dialogsWith(account, prefix)) {
            if (!wanted.contains(id)) {
                e.remove(prefix + id);
            }
        }
        for (String id : wanted) {
            if (id.length() == 0) continue;
            e.putBoolean(prefix + id, true);
        }
        e.apply();
        MessagesController.getMainSettings(account).edit()
                .putLong(SvipeConfig.PREF_TYPE_MUTE_UPDATED, updatedAt).apply();
    }

    private static String prefixed(String prefix, long dialogId, long topicId) {
        return prefix + NotificationsController.getSharedPrefKey(dialogId, topicId);
    }


    private static String notifyKey(long dialogId, long topicId) {
        return NotificationsController.NOTIFY_FORWARDS_PREFIX + NotificationsController.getSharedPrefKey(dialogId, topicId);
    }

    /**
     * Everything a rule can be written for, as the key spells it: a dialog id, or one of the scope
     * names. Strings, not ids — a rule written for "all chats" travels between installs exactly like
     * a rule written for one chat, and reading these as numbers is what used to drop them.
     */
    private static List<String> dialogsWith(int account, String prefix) {
        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, ?> entry : notifications(account).getAll().entrySet()) {
            String k = entry.getKey();
            if (k == null || !k.startsWith(prefix)) continue;
            if (!(entry.getValue() instanceof Boolean) || !((Boolean) entry.getValue())) continue;
            String id = k.substring(prefix.length());
            // Topics carry a "dialog_topic" key; they are not synced, only whole dialogs are.
            if (id.indexOf('_') >= 0) continue;
            ids.add(id);
        }
        return ids;
    }

    /** Last local change, epoch ms — the tiebreak when two devices disagree. */
    public static long updatedAt(int account) {
        return MessagesController.getMainSettings(account).getLong(SvipeConfig.PREF_TYPE_MUTE_UPDATED, 0);
    }

    private static void touch(int account) {
        MessagesController.getMainSettings(account).edit()
                .putLong(SvipeConfig.PREF_TYPE_MUTE_UPDATED, System.currentTimeMillis()).apply();
    }


    /**
     * The verdict has to outlive the process. The server re-pushes a message that stays unread, and
     * by then the app may have been killed and restarted — a verdict kept only in memory is gone,
     * the repeat looks like a brand new message with nothing to judge it by, and the forward that
     * was silenced an hour ago rings after all. Keep the last ids judged per dialog on disk.
     */
    private static final int REMEMBERED_IDS = 64;

    public static boolean isKnownMuted(int account, long dialogId, int mid) {
        if (mid == 0) return false;
        String stored = notifications(account).getString(judgedKey(dialogId), null);
        if (stored == null) return false;
        for (String part : stored.split(",")) {
            if (part.equals(Integer.toString(mid))) return true;
        }
        return false;
    }

    public static void rememberMuted(int account, long dialogId, int mid) {
        if (mid == 0 || isKnownMuted(account, dialogId, mid)) return;
        String stored = notifications(account).getString(judgedKey(dialogId), "");
        ArrayList<String> ids = new ArrayList<>();
        if (stored.length() > 0) {
            for (String part : stored.split(",")) ids.add(part);
        }
        ids.add(Integer.toString(mid));
        while (ids.size() > REMEMBERED_IDS) ids.remove(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ids.get(i));
        }
        notifications(account).edit().putString(judgedKey(dialogId), sb.toString()).apply();
    }

    private static String judgedKey(long dialogId) {
        return "svipe_muted_type_mids_" + dialogId;
    }

    private static String key(long dialogId, long topicId) {
        return NotificationsController.MUTE_FORWARDS_PREFIX + NotificationsController.getSharedPrefKey(dialogId, topicId);
    }

    private static SharedPreferences notifications(int account) {
        return MessagesController.getNotificationsSettings(account);
    }
}
