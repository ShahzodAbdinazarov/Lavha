package org.telegram.svipe;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;

import java.util.HashMap;
import java.util.List;

/**
 * Pools {@link SvipeNumberHistory} across Svipe apps, the way {@link SvipeAvatarSync} pools deleted
 * avatars: one phone only knows the changes it happened to witness, and the interesting ones
 * usually happened before it was installed.
 *
 * Opening a profile does two things, in this order:
 *   1. contributes what this device saw about that person, when sharing is on
 *      (see {@link SvipeConfig#isNumberSyncEnabled});
 *   2. asks for the pooled PAST and merges it into the local ledger, so the profile tabs read from
 *      one place, keep working offline, and survive the server going away.
 *
 * The server answers with two things and only two: the numbers that account held BEFORE its current
 * one, and the bare Telegram ids of accounts that were on its current number earlier. It never sends
 * the number anybody is on today — not for this person, not for the ids — and nothing about the
 * present is synced between devices at all. Who a number belongs to now is Telegram's to disclose,
 * and it already decides that correctly; an id is only a place to look, which this client then looks
 * at with the user's own Telegram credentials. If Telegram will not show them, they stay unshown.
 */
public class SvipeNumberSync {

    /** Profiles synced this process, with when — one round trip per profile per few minutes. */
    private static final HashMap<Long, Long> lastSynced = new HashMap<>();
    private static final long RESYNC_AFTER_MS = 5 * 60 * 1000L;

    public interface Callback {
        void onMerged(boolean changed);
    }

    /**
     * Bring the pooled history for one profile in, and push ours out. Safe to call on every profile
     * open: it throttles itself and never blocks the caller.
     */
    public static void syncProfile(int account, long userId, Callback callback) {
        if (userId <= 0) {
            return;
        }
        final long now = System.currentTimeMillis();
        Long last = lastSynced.get(userId);
        if (last != null && now - last < RESYNC_AFTER_MS) {
            return;
        }
        lastSynced.put(userId, now);

        if (SvipeConfig.isNumberSyncEnabled(account)) {
            contribute(account, userId);
        }
        fetch(account, userId, callback, false);
    }

    /** Send what this device knows about that person, and about whoever else held their numbers. */
    private static void contribute(int account, long userId) {
        try {
            JSONArray bindings = new JSONArray();
            for (SvipeNumberHistory.Number number : SvipeNumberHistory.numbersOfAccount(userId)) {
                bindings.put(binding(userId, number.phone, number.firstSeen, number.lastSeen));
                for (SvipeNumberHistory.Account seen : SvipeNumberHistory.accountsOnNumber(number.phone)) {
                    if (seen.userId != userId) {
                        bindings.put(binding(seen.userId, number.phone, seen.firstSeen, seen.lastSeen));
                    }
                }
            }
            if (bindings.length() == 0) {
                return;
            }
            JSONObject body = new JSONObject();
            body.put("bindings", bindings);
            post(account, body, false);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static JSONObject binding(long userId, String phone, long firstSeen, long lastSeen) throws Exception {
        JSONObject o = new JSONObject();
        o.put("subject_tg_id", userId);
        o.put("phone", phone);
        o.put("first_seen", iso(firstSeen));
        o.put("last_seen", iso(lastSeen));
        return o;
    }

    private static String iso(long millis) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                .format(new java.util.Date(millis <= 0 ? System.currentTimeMillis() : millis));
    }

    private static void post(int account, JSONObject body, boolean retried) {
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                return;
            }
            SvipeApi.post("/v1/numbers/observed", body, token, (res, code, err) -> {
                if (code == 401 && !retried) {
                    SvipeAuth.invalidateAccessToken(account);
                    post(account, body, true);
                }
            });
        });
    }

    /**
     * Ask for the pooled history and write it into the local ledger.
     *
     * A 403 is the expected answer for anyone we are not allowed to ask about, and is not an error
     * worth surfacing: the profile simply shows what this device saw by itself.
     */
    private static void fetch(int account, long userId, Callback callback, boolean retried) {
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                return;
            }
            SvipeApi.get("/v1/numbers/" + userId, token, (res, code, err) -> {
                if (code == 401 && !retried) {
                    SvipeAuth.invalidateAccessToken(account);
                    fetch(account, userId, callback, true);
                    return;
                }
                if (res == null || code < 200 || code >= 300) {
                    lastSynced.remove(userId);   // let the next open try again
                    return;
                }
                boolean changed = mergeOldNumbers(userId, res.optJSONArray("old_numbers"))
                        | SvipeOldProfiles.store(userId, res.optJSONArray("old_profile_ids"));
                if (callback != null) {
                    AndroidUtilities.runOnUIThread(() -> callback.onMerged(changed));
                }
            });
        });
    }

    /** Numbers this account has left behind. The one it is on now is never among them. */
    private static boolean mergeOldNumbers(long userId, JSONArray array) {
        if (array == null) {
            return false;
        }
        boolean changed = false;
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) {
                continue;
            }
            changed |= SvipeNumberHistory.merge(userId, o.optString("phone"),
                    parse(o.optString("first_seen")), parse(o.optString("last_seen")));
        }
        return changed;
    }

    /** The server speaks ISO-8601; anything unparseable becomes "now", which only widens a window. */
    private static long parse(String value) {
        if (value == null || value.length() < 19) {
            return 0;
        }
        try {
            java.text.SimpleDateFormat format =
                    new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
            format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return format.parse(value.substring(0, 19)).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private SvipeNumberSync() {
    }
}
