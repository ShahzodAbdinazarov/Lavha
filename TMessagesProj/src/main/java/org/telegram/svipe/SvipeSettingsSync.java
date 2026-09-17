package org.telegram.svipe;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationsController;

import java.util.ArrayList;
import java.util.List;

/**
 * Carries the settings the CLIENT owns to the user's other Svipe installs.
 *
 * Telegram already syncs everything Telegram knows about. What it cannot sync is a rule it has no
 * concept of — the first being "bots are their own notification category, muted except these". That
 * rule is ours, so we move it ourselves, through {@code /v1/settings/notifications}. The same
 * bucket carries the per-chat message-type exceptions ({@link SvipeMessageTypeMute}), which Telegram
 * has no field for either.
 *
 * Conflicts are settled by when the user made the change, not by when a device managed to connect:
 * a phone that was offline for a week must not undo what was decided in the meantime.
 */
public final class SvipeSettingsSync {

    private static final String NAME = "notifications";
    /** Coalesce a burst of toggles into one write — flipping five exceptions is one intent. */
    private static final long PUSH_DEBOUNCE_MS = 1500;

    private static Runnable pending;

    /**
     * The message-type exceptions, as the bucket names them and as the client stores them. A device
     * that predates one of these sends no such key at all, and "not mentioned" is not "none" — a
     * missing list leaves the local one alone rather than wiping it.
     */
    private static final String[] TYPE_LISTS = {
            "muted_forwards", "notified_forwards", "muted_links", "notified_links",
    };
    private static final String[] TYPE_PREFIXES = {
            NotificationsController.MUTE_FORWARDS_PREFIX, NotificationsController.NOTIFY_FORWARDS_PREFIX,
            NotificationsController.MUTE_LINKS_PREFIX, NotificationsController.NOTIFY_LINKS_PREFIX,
    };

    /** The bucket carries two rules; the later of the two is what the bucket is dated by. */
    private static long localUpdatedAt(int account) {
        return Math.max(SvipeBotMute.updatedAt(account), SvipeMessageTypeMute.updatedAt(account));
    }

    private SvipeSettingsSync() {}

    /** Send the local rule up, debounced. Safe to call on every toggle. */
    public static void push(final int account) {
        if (pending != null) {
            AndroidUtilities.cancelRunOnUIThread(pending);
        }
        pending = () -> {
            pending = null;
            pushNow(account);
        };
        AndroidUtilities.runOnUIThread(pending, PUSH_DEBOUNCE_MS);
    }

    private static void pushNow(final int account) {
        try {
            final JSONObject value = new JSONObject();
            value.put("bots_muted", SvipeBotMute.isEnabled(account));
            JSONArray arr = new JSONArray();
            for (Long id : SvipeBotMute.exceptions(account)) arr.put(id);
            value.put("bot_exceptions", arr);
            for (int i = 0; i < TYPE_LISTS.length; i++) {
                JSONArray ids = new JSONArray();
                for (Long id : SvipeMessageTypeMute.dialogsFor(account, TYPE_PREFIXES[i])) ids.put(id);
                value.put(TYPE_LISTS[i], ids);
            }

            final JSONObject body = new JSONObject();
            body.put("value", value);
            body.put("client_updated_at", localUpdatedAt(account));

            SvipeAuth.ensureToken(account, token -> {
                if (token == null) return; // nothing is lost: the next change or launch pushes again
                SvipeApi.put("/v1/settings/" + NAME, body, token, (res, code, err) -> {
                    if (code >= 200 && code < 300) {
                        FileLog.d("svipe: notification settings pushed");
                    } else {
                        FileLog.d("svipe: notification settings push failed (" + code + ")");
                    }
                });
            });
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /**
     * Ask what the other devices know and adopt it if it is newer. Called at app start; cheap
     * enough (one small GET) that it rides along with the rest of the warm-up.
     */
    public static void pull(final int account) {
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                FileLog.d("svipe: notification settings pull skipped, no token");
                return;
            }
            SvipeApi.get("/v1/settings/" + NAME, token, (res, code, err) -> {
                if (res == null || code < 200 || code >= 300) {
                    FileLog.d("svipe: notification settings pull failed (" + code + ") " + err);
                    return;
                }
                try {
                    long remoteAt = res.optLong("client_updated_at", 0);
                    JSONObject value = res.optJSONObject("value");
                    if (value == null || remoteAt <= 0) {
                        // Nothing stored yet. If we hold a rule, this device is the one that knows.
                        if (localUpdatedAt(account) > 0) pushNow(account);
                        return;
                    }
                    if (remoteAt <= localUpdatedAt(account)) {
                        // Ours is the later intent — make sure the server has it.
                        if (remoteAt < localUpdatedAt(account)) pushNow(account);
                        return;
                    }
                    boolean muted = value.optBoolean("bots_muted", false);
                    List<Long> exceptions = new ArrayList<>();
                    JSONArray arr = value.optJSONArray("bot_exceptions");
                    for (int i = 0; arr != null && i < arr.length(); i++) {
                        exceptions.add(arr.optLong(i));
                    }
                    FileLog.d("svipe: adopting notification settings from another device (muted="
                            + muted + ", exceptions=" + exceptions.size() + ")");
                    SvipeBotMute.adopt(account, muted, exceptions, remoteAt);
                    // A device that predates message-type exceptions sends no such key at all, and
                    // "not mentioned" is not "none" — leave the local rule alone rather than wipe it.
                    for (int t = 0; t < TYPE_LISTS.length; t++) {
                        JSONArray ids = value.optJSONArray(TYPE_LISTS[t]);
                        if (ids == null) continue;
                        List<Long> dialogs = new ArrayList<>();
                        for (int i = 0; i < ids.length(); i++) dialogs.add(ids.optLong(i));
                        SvipeMessageTypeMute.adoptList(account, TYPE_PREFIXES[t], dialogs, remoteAt);
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
            });
        });
    }
}
