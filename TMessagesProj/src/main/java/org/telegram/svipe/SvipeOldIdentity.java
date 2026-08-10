package org.telegram.svipe;

import android.text.TextUtils;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns {@link SvipeNumberHistory} into the two lists a profile can show about a person:
 *
 *   <b>Old profiles</b> — accounts that used to sit on THIS person's number before them. Each row
 *   shows where that account went (its current number, if we know it) and opens its profile when
 *   Telegram still lets us reach it.
 *
 *   <b>Old numbers</b> — numbers THIS account used before its current one. Each row shows who holds
 *   that number now, and opens them when we can.
 *
 * When the account behind a row is gone — deleted, or simply never visible to us — the row is built
 * from a deleted {@link TLRPC.User}, so the app's own cells render Telegram's "Deleted Account"
 * exactly as they do everywhere else, and it is not openable. That is the honest rendering: we know
 * a number changed hands, we do not know to whom.
 */
public class SvipeOldIdentity {

    /** One row: a person on the other side of a number change. */
    public static class Item {
        /** Real when we could resolve the account, a deleted stand-in when we could not. */
        public final TLRPC.User user;
        /** The number this row is about — where the account went, or which number it left behind. */
        public final String phone;
        /** Whether tapping it can open a profile. False for the deleted stand-ins. */
        public final boolean openable;

        Item(TLRPC.User user, String phone, boolean openable) {
            this.user = user;
            this.phone = phone;
            this.openable = openable;
        }

        public String displayPhone() {
            return TextUtils.isEmpty(phone) ? "" : "+" + phone;
        }
    }

    /** Accounts that held this person's number before them, oldest first. */
    public static List<Item> oldProfiles(int account, long userId) {
        ArrayList<Item> out = new ArrayList<>();
        String phone = currentNumberOf(account, userId);
        if (TextUtils.isEmpty(phone)) {
            return out;
        }
        for (SvipeNumberHistory.Account seen : SvipeNumberHistory.accountsOnNumber(phone)) {
            if (seen.userId == userId || seen.userId == 0) {
                continue;
            }
            TLRPC.User user = resolve(account, seen.userId);
            // Where they went: their own current number, which is the useful half of "they moved".
            String movedTo = currentNumberOf(account, seen.userId);
            if (user != null && !UserObject.isDeleted(user)) {
                out.add(new Item(user, movedTo, true));
            } else {
                out.add(new Item(deletedStandIn(seen.userId), movedTo, false));
            }
        }
        return out;
    }

    public static List<Item> oldNumbers(int account, long userId) {
        return oldNumbers(account, userId, null);
    }

    /**
     * Numbers this account used before its current one, oldest first.
     *
     * ``onResolved`` is called when a number we had to ask Telegram about comes back with an owner —
     * that answer arrives after this list is built, and without it the row would sit as an unnamed
     * blank until the screen was opened a second time.
     */
    public static List<Item> oldNumbers(int account, long userId, Runnable onResolved) {
        ArrayList<Item> out = new ArrayList<>();
        List<SvipeNumberHistory.Number> numbers = SvipeNumberHistory.numbersOfAccount(userId);
        if (numbers.size() < 2) {
            return out;          // one number is not a history
        }
        for (int i = 0; i < numbers.size() - 1; i++) {   // everything but the current one
            String phone = numbers.get(i).phone;
            SvipeNumberHistory.Account holder = null;
            for (SvipeNumberHistory.Account seen : SvipeNumberHistory.accountsOnNumber(phone)) {
                if (seen.userId == userId || seen.userId == 0) {
                    continue;
                }
                if (holder == null || seen.lastSeen > holder.lastSeen) {
                    holder = seen;      // whoever we saw on it most recently holds it now
                }
            }
            TLRPC.User user = holder != null ? resolve(account, holder.userId) : null;
            if (user == null) {
                // Nobody we have seen holds it. Telegram will say who does — anybody with a number
                // can resolve it to a profile — so ask, once ever per number, and use the answer as
                // soon as it lands. Until then the row is honestly a blank.
                long resolved = SvipePhoneResolve.cached(phone);
                if (resolved > 0) {
                    user = resolve(account, resolved);
                } else if (resolved == -1) {
                    SvipePhoneResolve.resolve(account, phone, resolvedId -> {
                        if (onResolved != null) {
                            onResolved.run();
                        }
                    });
                }
            }
            if (user != null && !UserObject.isDeleted(user)) {
                out.add(new Item(user, phone, true));
            } else {
                out.add(new Item(deletedStandIn(holder != null ? holder.userId : 0), phone, false));
            }
        }
        return out;
    }

    /**
     * The number an account is on today: what Telegram shows us now, and failing that the last one
     * we wrote down. A contact whose number we can no longer see still has a history.
     */
    private static String currentNumberOf(int account, long userId) {
        TLRPC.User user = resolve(account, userId);
        if (user != null && !TextUtils.isEmpty(user.phone)) {
            return SvipeNumberHistory.normalize(user.phone);
        }
        List<SvipeNumberHistory.Number> numbers = SvipeNumberHistory.numbersOfAccount(userId);
        return numbers.isEmpty() ? "" : numbers.get(numbers.size() - 1).phone;
    }

    /** In memory first, then the local database — an old contact is often only in the latter. */
    private static TLRPC.User resolve(int account, long userId) {
        if (userId == 0) {
            return null;
        }
        TLRPC.User user = MessagesController.getInstance(account).getUser(userId);
        if (user == null) {
            user = MessagesStorage.getInstance(account).getUserSync(userId);
            if (user != null) {
                MessagesController.getInstance(account).putUser(user, true);
            }
        }
        return user;
    }

    /** A user object that is deleted, so every cell in the app draws it as Telegram's own. */
    private static TLRPC.User deletedStandIn(long userId) {
        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = userId;
        user.deleted = true;
        return user;
    }

    private SvipeOldIdentity() {
    }
}
