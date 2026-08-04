package org.telegram.svipe;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationsController;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Bots as their own notification category.
 *
 * Telegram files a bot under "Private chats", so the only way to silence bots is to silence every
 * private chat — which is not what anyone wants, because the same setting also silences the people
 * you actually talk to. This gives bots their own switch, with its own exceptions.
 *
 * The rule is enforced through Telegram's OWN per-peer mute rather than a filter of our own. That
 * matters: a peer muted this way is muted for the server too, so the phone is not woken for it, the
 * dialog carries the ordinary muted icon, and the mute is visible in every other client the user has
 * — including official Telegram. A client-side filter would have looked identical in our app and
 * done nothing anywhere else.
 *
 * What we own, and therefore what syncs through our backend, is the RULE: "bots are muted, except
 * these" — plus the memory of which peers we muted, so switching the rule off restores exactly what
 * we changed and never touches a bot the user muted by hand.
 */
public final class SvipeBotMute {

    private SvipeBotMute() {}

    public static boolean isEnabled(int account) {
        return prefs(account).getBoolean(SvipeConfig.PREF_BOT_MUTE, false);
    }

    /** Bots that keep their notifications while the rule is on. */
    public static Set<Long> exceptions(int account) {
        return readIds(account, SvipeConfig.PREF_BOT_MUTE_EXCEPTIONS);
    }

    public static boolean isException(int account, long userId) {
        return exceptions(account).contains(userId);
    }

    public static void setException(int account, long userId, boolean except) {
        Set<Long> set = exceptions(account);
        if (except == set.contains(userId)) return;
        if (except) set.add(userId); else set.remove(userId);
        writeIds(account, SvipeConfig.PREF_BOT_MUTE_EXCEPTIONS, set);
        touch(account);
        // Apply immediately to the one bot that changed rather than re-walking every dialog.
        if (isEnabled(account)) {
            applyTo(account, userId, !except);
        } else if (!except) {
            // Rule is off: an exception means nothing, but leaving a mute we applied would.
            applyTo(account, userId, false);
        }
        SvipeSettingsSync.push(account);
    }

    /** Turn the rule on or off and bring every bot dialog in line with it. */
    public static void setEnabled(int account, boolean enabled) {
        prefs(account).edit().putBoolean(SvipeConfig.PREF_BOT_MUTE, enabled).apply();
        touch(account);
        applyAll(account);
        SvipeSettingsSync.push(account);
    }

    /** Last local change, epoch ms — the tiebreak when two devices disagree. */
    public static long updatedAt(int account) {
        return prefs(account).getLong(SvipeConfig.PREF_BOT_MUTE_UPDATED, 0);
    }

    private static void touch(int account) {
        prefs(account).edit().putLong(SvipeConfig.PREF_BOT_MUTE_UPDATED, System.currentTimeMillis()).apply();
    }

    /**
     * Adopt settings that arrived from another device. Does NOT push back — that would bounce two
     * devices off each other forever — and applies the rule locally exactly as a local change would.
     */
    public static void adopt(int account, boolean enabled, List<Long> exceptions, long updatedAt) {
        SharedPreferences.Editor e = prefs(account).edit();
        e.putBoolean(SvipeConfig.PREF_BOT_MUTE, enabled);
        e.putLong(SvipeConfig.PREF_BOT_MUTE_UPDATED, updatedAt);
        e.apply();
        writeIds(account, SvipeConfig.PREF_BOT_MUTE_EXCEPTIONS, new HashSet<>(exceptions));
        applyAll(account);
    }

    /**
     * Bring every bot dialog in line with the rule — and only the ones the rule owns. Muting is our
     * doing and is remembered; unmuting only ever releases a peer we muted ourselves, so a bot the
     * user silenced by hand stays silent when the rule is switched off.
     */
    public static void applyAll(final int account) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                final boolean on = isEnabled(account);
                final Set<Long> except = exceptions(account);
                final Set<Long> applied = readIds(account, SvipeConfig.PREF_BOT_MUTE_APPLIED);
                final MessagesController mc = MessagesController.getInstance(account);
                for (TLRPC.Dialog dialog : new ArrayList<>(mc.getAllDialogs())) {
                    if (dialog == null || !DialogObject.isUserDialog(dialog.id)) continue;
                    TLRPC.User user = mc.getUser(dialog.id);
                    if (user == null || !user.bot) continue;
                    boolean shouldMute = on && !except.contains(dialog.id);
                    // Reconcile against what IS, not against what we remember doing. Telegram drops
                    // a per-peer mute it considers redundant (muting a bot while "Private chats" is
                    // already off writes nothing), so a rule that trusted its own bookkeeping went
                    // quiet the moment private chats were switched back on — the switch would read
                    // "muted" while every bot notified.
                    boolean currentlyMuted = mc.isDialogMuted(dialog.id, 0);
                    if (shouldMute) {
                        if (!currentlyMuted) {
                            NotificationsController.getInstance(account).muteDialog(dialog.id, 0, true);
                        }
                        applied.add(dialog.id);
                    } else if (applied.remove(dialog.id) && currentlyMuted) {
                        NotificationsController.getInstance(account).muteDialog(dialog.id, 0, false);
                    }
                }
                writeIds(account, SvipeConfig.PREF_BOT_MUTE_APPLIED, applied);
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    /** Apply the rule to a single bot (used when one exception changes, or a new bot appears). */
    public static void applyTo(final int account, final long userId, final boolean mute) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                Set<Long> applied = readIds(account, SvipeConfig.PREF_BOT_MUTE_APPLIED);
                MessagesController mc = MessagesController.getInstance(account);
                if (mute) {
                    if (applied.add(userId)) {
                        NotificationsController.getInstance(account).muteDialog(userId, 0, true);
                        writeIds(account, SvipeConfig.PREF_BOT_MUTE_APPLIED, applied);
                    }
                } else if (applied.remove(userId)) {
                    NotificationsController.getInstance(account).muteDialog(userId, 0, false);
                    writeIds(account, SvipeConfig.PREF_BOT_MUTE_APPLIED, applied);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    /**
     * Re-apply when Telegram's own notification settings change.
     *
     * Telegram drops a per-peer mute that is redundant: if "Private chats" is already off, muting a
     * bot writes nothing (NotificationsController.muteUntil). Turning private chats back ON
     * therefore un-silences every bot we thought we had muted — the rule would look on and do
     * nothing. Watching that event is what keeps the switch honest.
     */
    public static void watch(final int account) {
        if (watching) return;
        watching = true;
        AndroidUtilities.runOnUIThread(() -> org.telegram.messenger.NotificationCenter.getInstance(account)
                .addObserver((id, acc, args) -> {
                    if (!isEnabled(account)) return;
                    // Telegram just rewrote its own settings; ours has to be re-asserted on top.
                    prefs(account).edit().remove(SvipeConfig.PREF_BOT_MUTE_APPLIED).apply();
                    applyAll(account);
                }, org.telegram.messenger.NotificationCenter.notificationsSettingsUpdated));
    }

    private static boolean watching;

    /**
     * A new chat with a bot must obey a rule that was set before it existed — otherwise "mute bots"
     * quietly means "mute the bots I had that day", which is the version of this feature that makes
     * people stop trusting it. Cheap enough to call whenever the dialog list changes.
     */
    public static void applyToNewDialogs(int account) {
        if (!isEnabled(account)) return;
        applyAll(account);
    }

    /** Every bot the user has a dialog with — the list the exceptions screen offers. */
    public static ArrayList<TLRPC.User> botDialogs(int account) {
        ArrayList<TLRPC.User> bots = new ArrayList<>();
        try {
            MessagesController mc = MessagesController.getInstance(account);
            for (TLRPC.Dialog dialog : new ArrayList<>(mc.getAllDialogs())) {
                if (dialog == null || !DialogObject.isUserDialog(dialog.id)) continue;
                TLRPC.User user = mc.getUser(dialog.id);
                if (user != null && user.bot && !user.deleted) bots.add(user);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return bots;
    }

    // ---- storage ----

    private static SharedPreferences prefs(int account) {
        return MessagesController.getMainSettings(account);
    }

    private static Set<Long> readIds(int account, String key) {
        Set<Long> out = new HashSet<>();
        try {
            String blob = prefs(account).getString(key, null);
            if (blob == null || blob.isEmpty()) return out;
            JSONArray arr = new JSONArray(blob);
            for (int i = 0; i < arr.length(); i++) out.add(arr.optLong(i));
        } catch (Exception e) {
            FileLog.e(e);
        }
        return out;
    }

    private static void writeIds(int account, String key, Set<Long> ids) {
        try {
            JSONArray arr = new JSONArray();
            for (Long id : ids) arr.put(id);
            prefs(account).edit().putString(key, arr.toString()).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** For the settings row: how the rule reads at a glance. */
    public static String summary(int account) {
        if (!isEnabled(account)) return null;
        int n = exceptions(account).size();
        return n == 0 ? "" : String.valueOf(n);
    }

    /** Notifications enabled for bots? The switch shows the opposite of "muted". */
    public static boolean notificationsEnabled(int account) {
        return !isEnabled(account);
    }
}
