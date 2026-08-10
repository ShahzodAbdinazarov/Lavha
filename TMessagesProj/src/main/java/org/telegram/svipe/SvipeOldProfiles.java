package org.telegram.svipe;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The bare Telegram ids the server says were on somebody's current number before them.
 *
 * Ids, and nothing else — that is the whole point of the shape. The server never tells us a number
 * anyone is on today, so what arrives here cannot be a disclosure; it is a place to look. The
 * profile tab then looks, using the reader's own Telegram credentials, and shows whatever Telegram
 * is willing to show them. A person Telegram would not reveal stays a deleted-account row.
 *
 * Kept separate from {@link SvipeNumberHistory} deliberately: that ledger is keyed by number, and
 * binding these ids to a number here would mean writing down the very pairing — id and current
 * number — that the server declined to send.
 */
public class SvipeOldProfiles {

    private static final String PREFS = "svipe_old_profiles";
    private static final int MAX_PER_USER = 40;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** @return whether this changed what we had, so a screen can decide to redraw. */
    public static boolean store(long userId, JSONArray ids) {
        if (userId == 0 || ids == null) {
            return false;
        }
        try {
            LinkedHashSet<Long> merged = new LinkedHashSet<>(get(userId));
            boolean changed = false;
            for (int i = 0; i < ids.length(); i++) {
                long id = ids.optLong(i);
                if (id != 0 && id != userId) {
                    changed |= merged.add(id);
                }
            }
            if (!changed) {
                return false;
            }
            StringBuilder joined = new StringBuilder();
            int written = 0;
            for (Long id : merged) {
                if (written++ >= MAX_PER_USER) {
                    break;
                }
                if (joined.length() > 0) {
                    joined.append(',');
                }
                joined.append(id);
            }
            prefs().edit().putString(String.valueOf(userId), joined.toString()).apply();
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    public static List<Long> get(long userId) {
        ArrayList<Long> out = new ArrayList<>();
        String raw = prefs().getString(String.valueOf(userId), null);
        if (raw == null || raw.length() == 0) {
            return out;
        }
        for (String part : raw.split(",")) {
            try {
                out.add(Long.parseLong(part));
            } catch (Exception ignore) {
            }
        }
        return out;
    }

    private SvipeOldProfiles() {
    }
}
