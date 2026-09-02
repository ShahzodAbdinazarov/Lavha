package org.telegram.svipe;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.HashMap;
import java.util.HashSet;

/**
 * Who holds this phone number on Telegram <i>right now</i> — asked of Telegram itself, once.
 *
 * The number-history tabs know which numbers an account used to have, but not who took them over,
 * unless this device happened to witness it. Telegram will answer that directly: anybody holding a
 * number can resolve it to a profile, subject to the other person's "who can find me by my number"
 * setting. That is the same door the app is already allowed through — a number you can see is a
 * profile you could have found by hand — so asking here shows nothing new, it only saves the typing.
 *
 * <p><b>Flood discipline</b>, learned from {@link SvipeChannelResolve}: {@code contacts.resolvePhone}
 * is rate-limited, and calling it inside its own FLOOD_WAIT is what makes Telegram extend the window.
 * So every answer — including "nobody" — is PERSISTED, a number is asked about at most once ever, a
 * wait is remembered across launches, and requests carry
 * {@link ConnectionsManager#RequestFlagFailOnServerErrors} so a 420 arrives as an error we can see
 * instead of being silently re-queued behind the wait.
 */
public final class SvipePhoneResolve {

    private static final String PREFS = "svipe_phone_resolve";
    private static final String KEY_FLOOD_UNTIL = "flood_until";
    private static final String NOBODY = "0";

    /** Numbers being asked about right now, so a list of rows cannot ask the same one twice. */
    private static final HashSet<String> inFlight = new HashSet<>();
    private static final HashMap<String, Long> memory = new HashMap<>();

    public interface Callback {
        void onResolved(long userId);
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** What we already know, without asking: >0 a user id, 0 nobody, -1 never asked. */
    public static long cached(String phone) {
        final String digits = SvipeNumberHistory.normalize(phone);
        if (digits.length() == 0) {
            return 0;
        }
        Long remembered = memory.get(digits);
        if (remembered != null) {
            return remembered;
        }
        String stored = prefs().getString(digits, null);
        if (stored == null) {
            return -1;
        }
        long value = NOBODY.equals(stored) ? 0 : parse(stored);
        memory.put(digits, value);
        return value;
    }

    /**
     * Resolve a number to a Telegram account, at most once ever per number.
     *
     * The callback runs on the UI thread, and only when there is something new to show — a caller
     * that already rendered a "nobody" row does not get told "still nobody".
     */
    public static void resolve(int account, String phone, Callback callback) {
        final String digits = SvipeNumberHistory.normalize(phone);
        if (digits.length() == 0) {
            return;
        }
        if (cached(digits) != -1) {
            return;                         // asked before; the answer is already on disk
        }
        if (System.currentTimeMillis() < prefs().getLong(KEY_FLOOD_UNTIL, 0)) {
            // Billed with NO subject, here and below: the subject would be a phone number, and the
            // ledger records what a call cost the account, never who was on the other end of it.
            SvipeLimitLog.denied(account, SvipeLimitLog.RESOLVE_PHONE, SvipeLimitLog.PHONE_LOOKUP,
                    false, (int) ((prefs().getLong(KEY_FLOOD_UNTIL, 0) - System.currentTimeMillis())
                            / 1000), null, "numbers");
            return;                         // inside a wait: asking again is what extends it
        }
        synchronized (inFlight) {
            if (!inFlight.add(digits)) {
                return;
            }
        }

        TLRPC.TL_contacts_resolvePhone req = new TLRPC.TL_contacts_resolvePhone();
        req.phone = digits;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            synchronized (inFlight) {
                inFlight.remove(digits);
            }
            if (error != null) {
                SvipeLimitLog.failed(account, SvipeLimitLog.RESOLVE_PHONE,
                        SvipeLimitLog.PHONE_LOOKUP, error, null, "numbers");
                if (error.text != null && error.text.startsWith("FLOOD_WAIT")) {
                    // Remember the window rather than discovering it again with the next row.
                    long seconds = Utilities.parseInt(error.text);
                    prefs().edit().putLong(KEY_FLOOD_UNTIL,
                            System.currentTimeMillis() + Math.max(seconds, 60) * 1000L).apply();
                    return;                 // deliberately NOT cached as "nobody" — we never asked
                }
                // PHONE_NOT_OCCUPIED, or a privacy setting that hides them from us. Both are real
                // answers and both are stable, so they are worth remembering.
                store(digits, 0);
                return;
            }
            SvipeLimitLog.ok(account, SvipeLimitLog.RESOLVE_PHONE, SvipeLimitLog.PHONE_LOOKUP,
                    null, "numbers");
            long userId = 0;
            if (response instanceof TLRPC.TL_contacts_resolvedPeer) {
                TLRPC.TL_contacts_resolvedPeer resolved = (TLRPC.TL_contacts_resolvedPeer) response;
                MessagesController.getInstance(account).putUsers(resolved.users, false);
                MessagesController.getInstance(account).putChats(resolved.chats, false);
                if (!resolved.users.isEmpty()) {
                    userId = resolved.users.get(0).id;
                    // The answer is itself an observation: this account is on this number today.
                    SvipeNumberHistory.observe(resolved.users.get(0));
                    SvipeNumberHistory.merge(userId, digits, 0, System.currentTimeMillis());
                }
            }
            store(digits, userId);
            if (userId != 0 && callback != null) {
                callback.onResolved(userId);
            }
        }), ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    private static void store(String phone, long userId) {
        memory.put(phone, userId);
        try {
            prefs().edit().putString(phone, userId == 0 ? NOBODY : String.valueOf(userId)).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static long parse(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return 0;
        }
    }

    private SvipePhoneResolve() {
    }
}
