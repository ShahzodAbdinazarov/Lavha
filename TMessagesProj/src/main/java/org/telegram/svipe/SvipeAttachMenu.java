package org.telegram.svipe;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_bots;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AttachBotIntroTopView;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;

/**
 * Any bot with a mini app in the attachment menu (📎), with the user's consent: Svipe's version of
 * what Telegram gives only to the bots it picks (the old @wallet among them). Telegram's server
 * refuses toggleBotInAttachMenu for every other bot, so the list lives here, per account, and the
 * attachment menu shows it after Telegram's own bots.
 *
 * The flow copies Telegram's attach bots step by step: the same "requests to be added as an option
 * to your attachments menu" dialog, the same "shortcut added" bulletin as the menu opens on the bot,
 * and the same ways out (long press on the button, or ⋮ → Delete bot in the open mini app).
 *
 * Ways in:
 *   - t.me/<bot>?startattach[=param] for a bot Telegram didn't approve: it used to end in "This bot
 *     can't be added to the attachment menu"; in Svipe it asks the user instead.
 *   - The mini app itself (Svipe extension, sent like any Telegram.WebView event):
 *       postEvent('web_app_add_to_attach_menu') → event 'attach_menu_added', or 'attach_menu_failed' {error}
 *       postEvent('web_app_check_attach_menu')  → event 'attach_menu_checked' {status: added|missed|declined}
 *     A mini app gets the dialog once: after a decline (or a removal) it is answered "declined" without
 *     asking again, so a bot can't nag. A startattach link, which the user taps themselves, always asks.
 *
 * Opening: a bot with a Main Mini App opens it with messages.requestMainWebView in the current chat
 * (initData carries chat_type and chat_instance). A bot with only a menu button mini app opens it as
 * from its own chat: the server refuses a menu button URL in any other chat (BOT_WEBVIEW_DISABLED,
 * measured on 2026-10-15 with @BotFather and @hamster_kombat_bot).
 */
public class SvipeAttachMenu {

    private static final String PREF = "svipe_attachmenu_";

    /** Marks the bots of this list among Telegram's own in the attachment menu. */
    public static class Bot extends TLRPC.TL_attachMenuBot {
        public String url;
    }

    private static final SvipeAttachMenu[] instances = new SvipeAttachMenu[UserConfig.MAX_ACCOUNT_COUNT];

    private final int currentAccount;
    private final ArrayList<Bot> bots = new ArrayList<>();
    private final ArrayList<Long> declined = new ArrayList<>();
    private boolean usersLoaded;

    public static SvipeAttachMenu get(int currentAccount) {
        SvipeAttachMenu instance = instances[currentAccount];
        if (instance == null) {
            instances[currentAccount] = instance = new SvipeAttachMenu(currentAccount);
        }
        return instance;
    }

    private SvipeAttachMenu(int currentAccount) {
        this.currentAccount = currentAccount;
        load();
    }

    public static boolean isSvipeBot(TLRPC.TL_attachMenuBot bot) {
        return bot instanceof Bot;
    }

    /** The bots to show, in the order they were added. Their users are loaded from the cache once. */
    public ArrayList<Bot> getBots() {
        loadUsers();
        return bots;
    }

    public Bot find(long botId) {
        for (Bot bot : bots) {
            if (bot.bot_id == botId) {
                return bot;
            }
        }
        return null;
    }

    public boolean contains(long botId) {
        return find(botId) != null;
    }

    /** 'added', 'declined' (declined or removed: a mini app is not asked again) or 'missed'. */
    public String status(long botId) {
        if (contains(botId)) {
            return "added";
        }
        return declined.contains(botId) ? "declined" : "missed";
    }

    /** Whether Telegram itself already has this bot in the attachment menu. */
    private static boolean inTelegramMenu(int currentAccount, long botId) {
        TLRPC.TL_attachMenuBots telegram = org.telegram.messenger.MediaDataController.getInstance(currentAccount).getAttachMenuBots();
        if (telegram != null && telegram.bots != null) {
            for (TLRPC.TL_attachMenuBot bot : telegram.bots) {
                if (bot.bot_id == botId && !bot.inactive && bot.show_in_attach_menu) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The URL to open: the menu button's mini app, if the bot has one. Null when it only has a Main
     * Mini App (or nothing): {@link #canAdd} tells those apart.
     */
    private String menuUrl(long botId) {
        TLRPC.UserFull full = MessagesController.getInstance(currentAccount).getUserFull(botId);
        if (full != null && full.bot_info != null && full.bot_info.menu_button instanceof TL_bots.TL_botMenuButton) {
            String url = ((TL_bots.TL_botMenuButton) full.bot_info.menu_button).url;
            if (!TextUtils.isEmpty(url)) {
                return url;
            }
        }
        return null;
    }

    /** Only a bot with a mini app can be opened from the menu. Calls back with the full user loaded. */
    public void canAdd(TLRPC.User bot, Utilities.Callback<Boolean> whenDone) {
        if (bot == null || !bot.bot) {
            whenDone.run(false);
            return;
        }
        Runnable decide = () -> whenDone.run(bot.bot_has_main_app || menuUrl(bot.id) != null);
        if (MessagesController.getInstance(currentAccount).getUserFull(bot.id) != null) {
            decide.run();
        } else {
            MessagesController.getInstance(currentAccount).loadFullUser(bot, 0, true, full -> AndroidUtilities.runOnUIThread(decide));
        }
    }

    /**
     * Telegram's attach bot dialog for this bot. {@code whenDone} gets (whether the dialog was shown
     * now, whether the bot is in the menu now). {@code fromMiniApp}: a declined bot is not asked
     * again, and its answer comes back as is.
     */
    public void request(TLRPC.User bot, boolean fromMiniApp, Utilities.Callback2<Boolean, Boolean> whenDone) {
        if (bot == null) {
            whenDone.run(false, false);
            return;
        }
        if (contains(bot.id) || inTelegramMenu(currentAccount, bot.id)) {
            whenDone.run(false, true);
            return;
        }
        if (fromMiniApp && declined.contains(bot.id)) {
            whenDone.run(false, false);
            return;
        }
        final Activity activity = LaunchActivity.instance;
        if (activity == null) {
            whenDone.run(false, false);
            return;
        }
        canAdd(bot, ok -> {
            if (!ok) {
                whenDone.run(false, false);
                return;
            }
            AttachBotIntroTopView introTopView = new AttachBotIntroTopView(activity);
            introTopView.setColor(Theme.getColor(Theme.key_chat_attachIcon));
            introTopView.setBackgroundColor(Theme.getColor(Theme.key_dialogTopBackground));
            introTopView.setUser(currentAccount, bot);

            final boolean[] answered = new boolean[1];
            new AlertDialog.Builder(activity)
                .setTopView(introTopView)
                .setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.BotRequestAttachPermission, UserObject.getUserName(bot))))
                .setPositiveButton(LocaleController.getString(R.string.BotAddToMenu), (di, w) -> {
                    if (answered[0]) return;
                    answered[0] = true;
                    add(bot);
                    whenDone.run(true, true);
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), (di, w) -> {
                    if (answered[0]) return;
                    answered[0] = true;
                    decline(bot.id);
                    whenDone.run(true, false);
                })
                .setOnDismissListener(di -> {
                    if (answered[0]) return;
                    answered[0] = true;
                    decline(bot.id);
                    whenDone.run(true, false);
                })
                .show();
        });
    }

    private void add(TLRPC.User user) {
        Bot bot = new Bot();
        bot.bot_id = user.id;
        bot.short_name = UserObject.getFirstName(user);
        bot.show_in_attach_menu = true;
        bot.url = menuUrl(user.id);
        everyChat(bot);
        bots.add(bot);
        declined.remove(user.id);
        save();
        changed();
    }

    /** Every chat but the bot's own, where its mini app opens from the menu button anyway. */
    private static void everyChat(Bot bot) {
        bot.peer_types.add(new TLRPC.TL_attachMenuPeerTypePM());
        bot.peer_types.add(new TLRPC.TL_attachMenuPeerTypeBotPM());
        bot.peer_types.add(new TLRPC.TL_attachMenuPeerTypeChat());
        bot.peer_types.add(new TLRPC.TL_attachMenuPeerTypeBroadcast());
    }

    private void decline(long botId) {
        if (!declined.contains(botId)) {
            declined.add(botId);
        }
        save();
    }

    /** Long press in the menu, or ⋮ → Delete bot. A mini app is not asked again after this. */
    public void remove(long botId) {
        Bot bot = find(botId);
        if (bot == null) {
            return;
        }
        bots.remove(bot);
        decline(botId);
        changed();
    }

    /** The mini app's URL for this bot now: the menu button may have changed since it was added. */
    public String urlFor(Bot bot) {
        String url = menuUrl(bot.bot_id);
        if (url != null && !url.equals(bot.url)) {
            bot.url = url;
            save();
        }
        return bot.url;
    }

    private void changed() {
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.attachMenuBotsDidLoad);
    }

    /** The bots' users may not be in memory after a restart; the menu needs them to draw a button. */
    private void loadUsers() {
        if (usersLoaded || bots.isEmpty()) {
            return;
        }
        usersLoaded = true;
        final ArrayList<Long> missing = new ArrayList<>();
        for (Bot bot : bots) {
            if (MessagesController.getInstance(currentAccount).getUser(bot.bot_id) == null) {
                missing.add(bot.bot_id);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable(() -> {
            final ArrayList<TLRPC.User> users = new ArrayList<>();
            try {
                MessagesStorage.getInstance(currentAccount).getUsersInternal(missing, users);
            } catch (Exception e) {
                FileLog.e(e);
            }
            AndroidUtilities.runOnUIThread(() -> {
                MessagesController.getInstance(currentAccount).putUsers(users, true);
                changed();
            });
        });
    }

    private SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREF + currentAccount, Context.MODE_PRIVATE);
    }

    private void load() {
        try {
            JSONArray list = new JSONArray(prefs().getString("bots", "[]"));
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.getJSONObject(i);
                Bot bot = new Bot();
                bot.bot_id = item.getLong("id");
                bot.short_name = item.optString("name", "");
                bot.url = item.has("url") ? item.getString("url") : null;
                bot.show_in_attach_menu = true;
                everyChat(bot);
                bots.add(bot);
            }
            JSONArray no = new JSONArray(prefs().getString("declined", "[]"));
            for (int i = 0; i < no.length(); i++) {
                declined.add(no.getLong(i));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void save() {
        try {
            JSONArray list = new JSONArray();
            for (Bot bot : bots) {
                JSONObject item = new JSONObject();
                item.put("id", bot.bot_id);
                item.put("name", bot.short_name);
                if (bot.url != null) {
                    item.put("url", bot.url);
                }
                list.put(item);
            }
            JSONArray no = new JSONArray();
            for (Long id : declined) {
                no.put(id);
            }
            prefs().edit().putString("bots", list.toString()).putString("declined", no.toString()).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
