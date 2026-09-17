package org.telegram.svipe;

import android.content.SharedPreferences;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationsController;

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

    public static boolean isForwardsMuted(int account, long dialogId, long topicId) {
        return notifications(account).getBoolean(key(dialogId, topicId), false);
    }

    public static void setForwardsMuted(int account, long dialogId, long topicId, boolean muted) {
        SharedPreferences.Editor e = notifications(account).edit();
        if (muted) {
            e.putBoolean(key(dialogId, topicId), true);
        } else {
            e.remove(key(dialogId, topicId));
        }
        e.apply();
        touch(account);
        SvipeSettingsSync.push(account);
    }

    /** Every dialog whose forwards are silenced — what the other devices need to know. */
    public static List<Long> mutedForwardDialogs(int account) {
        List<Long> ids = new ArrayList<>();
        for (Map.Entry<String, ?> entry : notifications(account).getAll().entrySet()) {
            String k = entry.getKey();
            if (k == null || !k.startsWith(NotificationsController.MUTE_FORWARDS_PREFIX)) continue;
            if (!(entry.getValue() instanceof Boolean) || !((Boolean) entry.getValue())) continue;
            String id = k.substring(NotificationsController.MUTE_FORWARDS_PREFIX.length());
            // Topics carry a "dialog_topic" key; they are not synced, only whole dialogs are.
            if (id.indexOf('_') >= 0) continue;
            try {
                ids.add(Long.parseLong(id));
            } catch (NumberFormatException ignore) {
            }
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
     * Adopt the list that arrived from another device: whatever is not on it is no longer muted,
     * so a switch turned OFF elsewhere travels just as well as one turned on. Does not push back.
     */
    public static void adopt(int account, List<Long> dialogs, long updatedAt) {
        Set<Long> wanted = new HashSet<>(dialogs);
        SharedPreferences.Editor e = notifications(account).edit();
        for (Long id : mutedForwardDialogs(account)) {
            if (!wanted.contains(id)) {
                e.remove(key(id, 0));
            }
        }
        for (Long id : wanted) {
            e.putBoolean(key(id, 0), true);
        }
        e.apply();
        MessagesController.getMainSettings(account).edit()
                .putLong(SvipeConfig.PREF_TYPE_MUTE_UPDATED, updatedAt).apply();
    }

    private static String key(long dialogId, long topicId) {
        return NotificationsController.MUTE_FORWARDS_PREFIX + NotificationsController.getSharedPrefKey(dialogId, topicId);
    }

    private static SharedPreferences notifications(int account) {
        return MessagesController.getNotificationsSettings(account);
    }
}
