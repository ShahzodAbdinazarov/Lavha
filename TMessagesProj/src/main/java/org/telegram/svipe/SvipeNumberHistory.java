package org.telegram.svipe;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/**
 * Local ledger of which Telegram account sat on which phone number, and when.
 *
 * A number is not an identity. Somebody registers an account on a number, moves that account to a
 * new number, and the old number is free to carry a different account tomorrow — do it a few times
 * and a contact's number tells you nothing about who is behind it. Telegram keeps only the present
 * state: ask it about a number and you get whoever holds it now, with no hint that anybody else
 * ever did. So we write the changes down as they pass, on this device, for the owner of this
 * device to read later.
 *
 * Two questions, one event stream:
 *   - "who has been on THIS number?"  -> {@link #accountsOnNumber} (a recycled number)
 *   - "which numbers has THIS account had?" -> {@link #numbersOfAccount} (a change-number chain)
 *
 * What it can and cannot see, plainly: it records only what Telegram shows us, only from the moment
 * this build is installed. Phone numbers are visible for saved contacts (and only as their privacy
 * settings allow) — for everyone else {@code user.phone} is empty and nothing is recorded. There is
 * no way to recover the past: a number recycled before install looks like a number with one owner.
 *
 * Capture is local and unconditional; SHARING is not. {@link SvipeNumberSync} can pool this with
 * other Svipe apps — which is what lets a phone learn about a change that happened before it was
 * installed — but only when the owner turns it on, because what would be uploaded is other people's
 * phone numbers, belonging to people who never installed this app and cannot be asked. Everything
 * that arrives from the pool comes back in through {@link #merge}, so there is still one ledger.
 */
public class SvipeNumberHistory {

    /** One account seen on a number. */
    public static class Account {
        public final long userId;
        public final String name;
        public final String username;
        public final long firstSeen;   // ms, when this pairing first appeared to us
        public long lastSeen;          // ms, when we last saw it still in place

        Account(long userId, String name, String username, long firstSeen, long lastSeen) {
            this.userId = userId;
            this.name = name;
            this.username = username;
            this.firstSeen = firstSeen;
            this.lastSeen = lastSeen;
        }
    }

    /** One number an account has held. */
    public static class Number {
        public final String phone;
        public final long firstSeen;
        public long lastSeen;

        Number(String phone, long firstSeen, long lastSeen) {
            this.phone = phone;
            this.firstSeen = firstSeen;
            this.lastSeen = lastSeen;
        }
    }

    private static final String PREFS = "svipe_number_history";
    private static final String BY_PHONE = "p_";     // phone  -> accounts that have held it
    private static final String BY_USER = "u_";      // userId -> numbers it has held
    private static final int MAX_PER_KEY = 40;

    /**
     * Last pairing we wrote, per user. putUser runs on nearly every object the app touches, so the
     * common case — a user whose number has not changed — must cost a map lookup and nothing else.
     */
    private static final HashMap<Long, String> lastKnown = new HashMap<>();
    private static volatile boolean warmed;
    private static volatile boolean warming;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Digits only: the same number arrives as "+998 90 123 45 67" and "998901234567". */
    public static String normalize(String phone) {
        if (phone == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(phone.length());
        for (int i = 0; i < phone.length(); i++) {
            char c = phone.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Offer a user object to the ledger. Called from the hot path, so it does as little as possible
     * and hands anything real to a background queue.
     */
    public static void observe(TLRPC.User user) {
        if (user == null || user.id == 0 || UserObject.isDeleted(user)) {
            return;
        }
        final String phone = normalize(user.phone);
        if (phone.length() == 0) {
            return;      // not a contact, or the number is not ours to see
        }
        if (!warmed) {
            warm();      // asynchronous; observations during it are dropped, see warm()
            return;
        }
        synchronized (lastKnown) {
            if (phone.equals(lastKnown.get(user.id))) {
                return;  // unchanged, which is the overwhelmingly common case
            }
            lastKnown.put(user.id, phone);
        }
        final long userId = user.id;
        final String name = UserObject.getUserName(user);
        final String username = UserObject.getPublicUsername(user);
        Utilities.globalQueue.postRunnable(() -> record(userId, phone, name, username, 0, 0));
    }

    /** The explicit signal: Telegram told us this account moved to a different number. */
    public static void observePhoneChange(long userId, String phone, String name, String username) {
        final String digits = normalize(phone);
        if (userId == 0 || digits.length() == 0) {
            return;
        }
        warm();
        synchronized (lastKnown) {
            lastKnown.put(userId, digits);
        }
        Utilities.globalQueue.postRunnable(() -> record(userId, digits, name, username, 0, 0));
    }

    /**
     * Fold in a pairing somebody else observed (see {@link SvipeNumberSync}).
     *
     * Unlike {@link #observe}, this carries the timestamps the pool agreed on rather than "now", and
     * it never touches {@link #lastKnown}: a number another device saw years ago must not be taken
     * for this device's current view of that account. Windows only widen — the earliest first-seen
     * and the latest last-seen win, because between two honest observers the union is the truth.
     *
     * @return whether anything was actually new, so a screen can decide to redraw.
     */
    public static boolean merge(long userId, String phone, long firstSeen, long lastSeen) {
        final String digits = normalize(phone);
        if (userId == 0 || digits.length() == 0) {
            return false;
        }
        return record(userId, digits, null, null, firstSeen, lastSeen);
    }

    /**
     * Load the in-memory "already recorded" map once, so a restart does not rewrite the ledger.
     *
     * Off the main thread, because the caller is {@link #observe} and its caller is putUser, which
     * runs on nearly every user object the app touches — reading a preferences file there would put
     * disk I/O in front of the UI. Observations that arrive before this finishes are dropped rather
     * than queued: the same users come past again within seconds, and the alternative is a write
     * per contact on every cold start.
     */
    private static void warm() {
        if (warmed || warming) {
            return;
        }
        warming = true;
        Utilities.globalQueue.postRunnable(() -> {
            try {
                HashMap<Long, String> loaded = new HashMap<>();
                for (String key : prefs().getAll().keySet()) {
                    if (!key.startsWith(BY_USER)) {
                        continue;
                    }
                    long uid = Long.parseLong(key.substring(BY_USER.length()));
                    List<Number> numbers = numbersOfAccount(uid);
                    if (!numbers.isEmpty()) {
                        loaded.put(uid, numbers.get(numbers.size() - 1).phone);
                    }
                }
                synchronized (lastKnown) {
                    lastKnown.putAll(loaded);
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                warmed = true;
            }
        });
    }

    /**
     * Write one pairing into both indexes.
     *
     * ``firstSeen``/``lastSeen`` of 0 mean "now" — the local observation case. A merge from the pool
     * passes real timestamps, and then the stored window only ever widens: whichever observer saw it
     * earliest owns first-seen, whichever saw it latest owns last-seen.
     */
    private static synchronized boolean record(long userId, String phone, String name, String username,
                                               long firstSeen, long lastSeen) {
        boolean changed = false;
        try {
            final long stamp = System.currentTimeMillis();
            final long first = firstSeen > 0 ? firstSeen : stamp;
            final long now = lastSeen > 0 ? lastSeen : stamp;
            SharedPreferences.Editor editor = prefs().edit();

            // number -> accounts. A second entry here is the interesting case: the number was reused.
            JSONArray accounts = read(BY_PHONE + phone);
            boolean found = false;
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject o = accounts.getJSONObject(i);
                if (o.optLong("uid") == userId) {
                    if (now > o.optLong("last")) {
                        o.put("last", now);
                        changed = true;
                    }
                    if (first < o.optLong("first")) {
                        o.put("first", first);
                        changed = true;
                    }
                    if (!TextUtils.isEmpty(name)) o.put("name", name);
                    if (!TextUtils.isEmpty(username)) o.put("uname", username);
                    found = true;
                    break;
                }
            }
            if (!found) {
                JSONObject o = new JSONObject();
                o.put("uid", userId);
                o.put("first", first);
                o.put("last", now);
                if (!TextUtils.isEmpty(name)) o.put("name", name);
                if (!TextUtils.isEmpty(username)) o.put("uname", username);
                accounts.put(o);
                changed = true;
            }
            editor.putString(BY_PHONE + phone, trim(accounts).toString());

            // account -> numbers. A second entry here is the other case: the account changed number.
            JSONArray numbers = read(BY_USER + userId);
            found = false;
            for (int i = 0; i < numbers.length(); i++) {
                JSONObject o = numbers.getJSONObject(i);
                if (phone.equals(o.optString("phone"))) {
                    if (now > o.optLong("last")) {
                        o.put("last", now);
                        changed = true;
                    }
                    if (first < o.optLong("first")) {
                        o.put("first", first);
                        changed = true;
                    }
                    found = true;
                    break;
                }
            }
            if (!found) {
                JSONObject o = new JSONObject();
                o.put("phone", phone);
                o.put("first", first);
                o.put("last", now);
                numbers.put(o);
                changed = true;
            }
            editor.putString(BY_USER + userId, trim(numbers).toString());
            editor.apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
        return changed;
    }

    /** Every account we have seen on a number, oldest pairing first. */
    public static List<Account> accountsOnNumber(String phone) {
        ArrayList<Account> out = new ArrayList<>();
        try {
            JSONArray arr = read(BY_PHONE + normalize(phone));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Account(o.optLong("uid"), o.optString("name", ""), o.optString("uname", ""),
                        o.optLong("first"), o.optLong("last")));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        Collections.sort(out, Comparator.comparingLong(a -> a.firstSeen));
        return out;
    }

    /** Every number we have seen an account on, oldest first — its change-number chain. */
    public static List<Number> numbersOfAccount(long userId) {
        ArrayList<Number> out = new ArrayList<>();
        try {
            JSONArray arr = read(BY_USER + userId);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Number(o.optString("phone"), o.optLong("first"), o.optLong("last")));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        Collections.sort(out, Comparator.comparingLong(n -> n.firstSeen));
        return out;
    }

    /** Numbers that have carried more than one account — the ones worth showing. */
    public static List<String> recycledNumbers() {
        ArrayList<String> out = new ArrayList<>();
        try {
            for (String key : prefs().getAll().keySet()) {
                if (!key.startsWith(BY_PHONE)) {
                    continue;
                }
                if (read(key).length() > 1) {
                    out.add(key.substring(BY_PHONE.length()));
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return out;
    }

    /** Accounts that have been on more than one number — the change-number chains. */
    public static List<Long> movedAccounts() {
        ArrayList<Long> out = new ArrayList<>();
        try {
            for (String key : prefs().getAll().keySet()) {
                if (!key.startsWith(BY_USER)) {
                    continue;
                }
                if (read(key).length() > 1) {
                    out.add(Long.parseLong(key.substring(BY_USER.length())));
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return out;
    }

    private static JSONArray read(String key) {
        try {
            String raw = prefs().getString(key, null);
            return raw == null ? new JSONArray() : new JSONArray(raw);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** Keep the newest {@link #MAX_PER_KEY} entries; a number with 40 owners has told its story. */
    private static JSONArray trim(JSONArray arr) {
        if (arr.length() <= MAX_PER_KEY) {
            return arr;
        }
        JSONArray out = new JSONArray();
        for (int i = arr.length() - MAX_PER_KEY; i < arr.length(); i++) {
            out.put(arr.optJSONObject(i));
        }
        return out;
    }

    private SvipeNumberHistory() {
    }
}
