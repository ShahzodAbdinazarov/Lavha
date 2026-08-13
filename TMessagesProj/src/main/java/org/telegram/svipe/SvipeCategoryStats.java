package org.telegram.svipe;

import org.json.JSONObject;
import org.telegram.messenger.MessagesController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Which Video-tab shelves this user actually opens, so the app can have them ready BEFORE they ask.
 *
 * <p>The tab has a dozen chips and one of them is always the next tap: somebody who watches serials
 * opens "Seriallar" every session and never touches "Sport". Counting the taps costs one preference
 * write and turns the second shelf of the session from a round-trip into an instant swap
 * ({@code SvipeExploreGrid.prefetchPopular}).
 *
 * <p>Local only. Nothing here is sent anywhere — which shelf a person browses is exactly the kind of
 * thing that has no business leaving the device to make a list load faster.
 *
 * <p>The count decays: on every write the whole table is halved once it gets large, so a taste from
 * six months ago cannot outvote this week's. Without that the first week of use would decide the
 * prefetch order forever.
 */
public final class SvipeCategoryStats {

    private static final String PREF = "svipe_video_cat_clicks";
    /** The synthetic "All" chip has no slug of its own. */
    public static final String ALL = "";
    /** Total taps across the table before every count is halved. */
    private static final int DECAY_AT = 60;

    private SvipeCategoryStats() {
    }

    /** One chip tap. {@code slug} null means the "All" chip. */
    public static void noteClick(int account, String slug) {
        final String key = slug == null ? ALL : slug;
        try {
            final JSONObject table = read(account);
            int total = 0;
            final Iterator<String> it = table.keys();
            while (it.hasNext()) {
                total += table.optInt(it.next());
            }
            table.put(key, table.optInt(key) + 1);
            if (total + 1 >= DECAY_AT) {
                halve(table);
            }
            MessagesController.getMainSettings(account).edit()
                    .putString(PREF, table.toString()).apply();
        } catch (Exception ignore) {
            // best-effort: a lost tap costs a prefetch, never correctness
        }
    }

    /**
     * The most-opened shelves, most first, excluding {@code except}. Slugs only — {@link #ALL} may be
     * among them, and the caller maps that back to "no filter".
     */
    public static List<String> topSlugs(int account, int limit, String except) {
        final ArrayList<String> out = new ArrayList<>();
        try {
            final JSONObject table = read(account);
            final ArrayList<String> keys = new ArrayList<>();
            final Iterator<String> it = table.keys();
            while (it.hasNext()) {
                keys.add(it.next());
            }
            Collections.sort(keys, (a, b) -> table.optInt(b) - table.optInt(a));
            for (String k : keys) {
                if (k.equals(except == null ? ALL : except)) {
                    continue;
                }
                if (table.optInt(k) <= 0) {
                    continue;
                }
                out.add(k);
                if (out.size() >= limit) {
                    break;
                }
            }
        } catch (Exception ignore) {
            // best-effort
        }
        return out;
    }

    private static JSONObject read(int account) {
        try {
            final String raw = MessagesController.getMainSettings(account).getString(PREF, null);
            return raw == null || raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void halve(JSONObject table) throws Exception {
        final ArrayList<String> keys = new ArrayList<>();
        final Iterator<String> it = table.keys();
        while (it.hasNext()) {
            keys.add(it.next());
        }
        for (String k : keys) {
            final int half = table.optInt(k) / 2;
            if (half <= 0) {
                table.remove(k);
            } else {
                table.put(k, half);
            }
        }
    }
}
