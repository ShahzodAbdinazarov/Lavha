package org.telegram.svipe;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.bots.BotLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * A mini app's access to the user's Telegram contacts, modelled on Telegram's own bot geolocation
 * permission ({@link BotLocation}): the bot asks from its mini app, the user sees one dialog naming
 * the bot, the answer is kept per account and per bot, and the bot's profile page carries a switch
 * that revokes it at any time.
 *
 * What is shared is the Telegram contact list the app already holds (name, username, and the phone
 * number only when Telegram shows it to the user) — not the phone's address book, so no Android
 * permission is involved. Nothing is shared before the user allows it, and a declined or revoked
 * bot gets {@code status: "cancelled"} / {@code available: false}, never data.
 *
 * Mini app side (Svipe extension, sent like any Telegram.WebView event):
 *   postEvent('web_app_request_contacts')  → event 'contacts_requested' {status, contacts?}
 *   postEvent('web_app_check_contacts')    → event 'contacts_checked'   {available, access_requested, access_granted}
 */
public class SvipeBotContacts {

    private static final String PREF = "svipe_botcontacts_";
    private static final HashMap<String, SvipeBotContacts> instances = new HashMap<>();

    private final Context context;
    private final int currentAccount;
    private final long botId;

    private boolean requested;
    private boolean granted;

    private final HashSet<Runnable> listeners = new HashSet<>();

    public static SvipeBotContacts get(Context context, int currentAccount, long botId) {
        final String key = currentAccount + "_" + botId;
        SvipeBotContacts instance = instances.get(key);
        if (instance == null) {
            instances.put(key, instance = new SvipeBotContacts(context, currentAccount, botId));
        }
        return instance;
    }

    private SvipeBotContacts(Context context, int currentAccount, long botId) {
        this.context = context.getApplicationContext();
        this.currentAccount = currentAccount;
        this.botId = botId;
        load();
    }

    public boolean asked() {
        return requested;
    }

    public boolean granted() {
        return granted;
    }

    public void listen(Runnable changed) {
        listeners.add(changed);
    }

    public void unlisten(Runnable changed) {
        listeners.remove(changed);
    }

    /** The switch on the bot's profile page. Turning it on asks again, like geolocation does. */
    public void setGranted(boolean value, Runnable whenDone) {
        if (value && !granted) {
            request(allowed -> {
                if (whenDone != null) {
                    whenDone.run();
                }
            });
            return;
        }
        requested = true;
        granted = value;
        save();
        notifyListeners();
        if (whenDone != null) {
            whenDone.run();
        }
    }

    /** Shows the permission dialog unless the user already allowed this bot; reports the answer. */
    public void request(Utilities.Callback<Boolean> whenDone) {
        if (granted) {
            if (whenDone != null) {
                whenDone.run(true);
            }
            return;
        }
        final Activity activity = LaunchActivity.instance;
        if (activity == null) {
            if (whenDone != null) {
                whenDone.run(false);
            }
            return;
        }
        final TLRPC.User bot = MessagesController.getInstance(currentAccount).getUser(botId);
        final boolean[] answered = new boolean[1];
        final AlertDialog.Builder b = new AlertDialog.Builder(activity, null);
        b.setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.SvipeBotContactsPermissionRequest, UserObject.getUserName(bot), UserObject.getUserName(bot))));
        b.setTopImage(new BotLocation.BotUserLocationDrawable(context, UserConfig.getInstance(currentAccount).getCurrentUser(), bot), Theme.getColor(Theme.key_dialogTopBackground));
        b.setPositiveButton(LocaleController.getString(R.string.BotLocationPermissionAllow), (di, w) -> {
            answered[0] = true;
            answer(true, whenDone);
        });
        b.setNegativeButton(LocaleController.getString(R.string.BotLocationPermissionDecline), (di, w) -> {
            answered[0] = true;
            answer(false, whenDone);
        });
        b.setOnDismissListener(di -> {
            if (!answered[0] && whenDone != null) {
                whenDone.run(false);
            }
        });
        b.show();
    }

    private void answer(boolean allowed, Utilities.Callback<Boolean> whenDone) {
        requested = true;
        granted = allowed;
        save();
        notifyListeners();
        if (whenDone != null) {
            whenDone.run(allowed);
        }
    }

    public JSONObject checkObject() {
        final JSONObject obj = new JSONObject();
        try {
            obj.put("available", true);
            obj.put("access_requested", requested);
            if (requested) {
                obj.put("access_granted", granted);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return obj;
    }

    /** The contact list, only for a bot the user allowed. */
    public JSONObject contactsObject() {
        final JSONObject obj = new JSONObject();
        try {
            if (!granted) {
                obj.put("status", "cancelled");
                return obj;
            }
            final MessagesController messages = MessagesController.getInstance(currentAccount);
            final ArrayList<TLRPC.TL_contact> contacts = new ArrayList<>(ContactsController.getInstance(currentAccount).contacts);
            final JSONArray list = new JSONArray();
            for (TLRPC.TL_contact contact : contacts) {
                final TLRPC.User user = messages.getUser(contact.user_id);
                if (user == null || user.deleted || UserObject.isUserSelf(user)) {
                    continue;
                }
                final JSONObject item = new JSONObject();
                item.put("user_id", user.id);
                item.put("first_name", user.first_name == null ? "" : user.first_name);
                if (user.last_name != null && !user.last_name.isEmpty()) {
                    item.put("last_name", user.last_name);
                }
                final String username = UserObject.getPublicUsername(user);
                if (username != null) {
                    item.put("username", username);
                }
                if (user.phone != null && !user.phone.isEmpty()) {
                    item.put("phone_number", user.phone);
                }
                list.put(item);
            }
            obj.put("status", "allowed");
            obj.put("contacts", list);
        } catch (Exception e) {
            FileLog.e(e);
        }
        return obj;
    }

    private void notifyListeners() {
        for (Runnable listener : new ArrayList<>(listeners)) {
            listener.run();
        }
    }

    private void load() {
        final SharedPreferences prefs = context.getSharedPreferences(PREF + currentAccount, Activity.MODE_PRIVATE);
        requested = prefs.getBoolean(botId + "_requested", false);
        granted = prefs.getBoolean(botId + "_granted", false);
    }

    private void save() {
        context.getSharedPreferences(PREF + currentAccount, Activity.MODE_PRIVATE).edit()
            .putBoolean(botId + "_requested", requested)
            .putBoolean(botId + "_granted", granted)
            .apply();
    }

    /** Called with BotLocation.clear() when the user resets bot permissions. */
    public static void clear() {
        instances.clear();
        final Context context = org.telegram.messenger.ApplicationLoader.applicationContext;
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            context.getSharedPreferences(PREF + i, Activity.MODE_PRIVATE).edit().clear().apply();
        }
    }
}
