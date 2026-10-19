package org.telegram.svipe;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_bots;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Components.AttachBotIntroTopView;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;

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
 *   - t.me/<bot>?startattach[=param], t.me/<chat>?attach=<bot> and ?startattach&choose=… for a bot
 *     Telegram didn't approve: they used to end in "This bot can't be added to the attachment menu";
 *     in Svipe they ask the user instead.
 *   - The mini app itself (Svipe extension, sent like any Telegram.WebView event):
 *       postEvent('web_app_add_to_attach_menu') → event 'attach_menu_added', or 'attach_menu_failed' {error}
 *       postEvent('web_app_check_attach_menu')  → event 'attach_menu_checked' {status: added|missed|declined}
 *     A mini app gets the dialog once: after a decline (or a removal) it is answered "declined" without
 *     asking again, so a bot can't nag. A link, which the user taps themselves, always asks.
 *
 * Opening: a bot with a Main Mini App opens it with messages.requestMainWebView in the current chat
 * (initData carries chat_type and chat_instance). A bot with only a menu button mini app opens it as
 * from its own chat: the server refuses a menu button URL in any other chat (BOT_WEBVIEW_DISABLED,
 * measured on 2026-10-15 with @BotFather and @hamster_kombat_bot).
 *
 * What Telegram keeps on its server for the bots it picked comes from Svipe's (app/api/attach.py):
 *   - the bot's own settings (GET /v1/attach/config): the chat types the button shows in
 *     (peer_types) and whether the add dialog asks to let the bot write to the user. Telegram's side
 *     menu is not offered: a bot gets the attachment menu, not a place in the app's own menu;
 *   - the bot's own menu icon (GET /v1/attach/icon/{bot_id}), one-colour SVG, tinted like Telegram's;
 *   - the same list on every device of the account (/v1/attach/bots);
 *   - who the chat is with: receiver / chat, signed by Svipe and passed to the mini app as the launch
 *     param tgWebAppSvipeData ({@link #signContext}), where Telegram signs them into initData;
 *   - answerWebAppQuery: the mini app hands over a message its bot prepared, and the app sends it to
 *     the chat the menu was opened in ({@link #sendPrepared}).
 */
public class SvipeAttachMenu {

    private static final String PREF = "svipe_attachmenu_";

    /** A bot that gave Svipe no settings shows in every chat but its own. */
    private static final String[] DEFAULT_PEER_TYPES = {"bots", "users", "groups", "channels"};

    /** Marks the bots of this list among Telegram's own in the attachment menu. */
    public static class Bot extends TLRPC.TL_attachMenuBot {
        public String url;
        public String username;
    }

    private static final SvipeAttachMenu[] instances = new SvipeAttachMenu[UserConfig.MAX_ACCOUNT_COUNT];

    private final int currentAccount;
    private final ArrayList<Bot> bots = new ArrayList<>();
    private final ArrayList<Long> declined = new ArrayList<>();
    private final HashMap<Long, JSONObject> configs = new HashMap<>();
    private final HashMap<String, android.graphics.drawable.Drawable> icons = new HashMap<>();
    private final java.util.HashSet<String> iconsLoading = new java.util.HashSet<>();
    private boolean usersLoaded;
    private boolean refreshed;

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

    /**
     * The bots to show, in the order they were added. Their users are loaded from the cache once; the
     * bots' settings and the other devices' answers are fetched once per run.
     */
    public ArrayList<Bot> getBots() {
        loadUsers();
        if (!refreshed) {
            refreshed = true;
            AndroidUtilities.runOnUIThread(() -> {
                refreshConfig(null, null);
                syncFromServer();
            });
        }
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

    /** Telegram's own entry for this bot, if Telegram picked it and the user has it in the menu. */
    public static TLRPC.TL_attachMenuBot telegramBot(int currentAccount, long botId) {
        TLRPC.TL_attachMenuBots telegram = org.telegram.messenger.MediaDataController.getInstance(currentAccount).getAttachMenuBots();
        if (telegram != null && telegram.bots != null) {
            for (TLRPC.TL_attachMenuBot bot : telegram.bots) {
                if (bot.bot_id == botId && !bot.inactive && bot.show_in_attach_menu) {
                    return bot;
                }
            }
        }
        return null;
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
        if (contains(bot.id) || telegramBot(currentAccount, bot.id) != null) {
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
            // The bot's settings decide whether the dialog asks to let it write to the user; a slow
            // answer holds the dialog up for two seconds at most.
            final boolean[] shown = new boolean[1];
            final Runnable show = () -> {
                if (shown[0]) return;
                shown[0] = true;
                showDialog(activity, bot, whenDone);
            };
            ArrayList<Long> ids = new ArrayList<>();
            ids.add(bot.id);
            refreshConfig(ids, show);
            AndroidUtilities.runOnUIThread(show, 2000);
        });
    }

    private void showDialog(Activity activity, TLRPC.User bot, Utilities.Callback2<Boolean, Boolean> whenDone) {
        AttachBotIntroTopView introTopView = new AttachBotIntroTopView(activity);
        introTopView.setColor(Theme.getColor(Theme.key_chat_attachIcon));
        introTopView.setBackgroundColor(Theme.getColor(Theme.key_dialogTopBackground));
        introTopView.setUser(currentAccount, bot);

        final boolean[] answered = new boolean[1];
        final AtomicBoolean allowWrite = new AtomicBoolean(false);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
            .setTopView(introTopView)
            .setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.BotRequestAttachPermission, UserObject.getUserName(bot))))
            .setPositiveButton(LocaleController.getString(R.string.BotAddToMenu), (di, w) -> {
                if (answered[0]) return;
                answered[0] = true;
                add(bot, allowWrite.get());
                whenDone.run(true, true);
            })
            .setNegativeButton(LocaleController.getString(R.string.Cancel), (di, w) -> {
                if (answered[0]) return;
                answered[0] = true;
                decline(bot.id, bot);
                whenDone.run(true, false);
            })
            .setOnDismissListener(di -> {
                if (answered[0]) return;
                answered[0] = true;
                decline(bot.id, bot);
                whenDone.run(true, false);
            });
        // Telegram's request_write_access: the same checkbox its own dialog shows, on by default.
        JSONObject config = configs.get(bot.id);
        if (config != null && config.optBoolean("write_access")) {
            allowWrite.set(true);
            CheckBoxCell cell = new CheckBoxCell(activity, 5, null);
            cell.setBackground(Theme.getSelectorDrawable(false));
            cell.setMultiline(true);
            cell.setText(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.OpenUrlOption2, UserObject.getUserName(bot))), "", true, false);
            cell.setPadding(LocaleController.isRTL ? AndroidUtilities.dp(16) : AndroidUtilities.dp(8), 0, LocaleController.isRTL ? AndroidUtilities.dp(8) : AndroidUtilities.dp(16), 0);
            cell.setOnClickListener(v -> {
                boolean allow = !cell.isChecked();
                cell.setChecked(allow, true);
                allowWrite.set(allow);
            });
            builder.setCustomViewOffset(6);
            builder.setView(cell);
        }
        builder.show();
    }

    private void add(TLRPC.User user, boolean allowWrite) {
        Bot bot = new Bot();
        bot.bot_id = user.id;
        bot.short_name = UserObject.getFirstName(user);
        bot.username = UserObject.getPublicUsername(user);
        bot.show_in_attach_menu = true;
        bot.url = menuUrl(user.id);
        applyConfig(bot, configs.get(user.id));
        bots.add(bot);
        declined.remove(user.id);
        save();
        changed();
        pushState(user.id, "added", bot.username);
        if (allowWrite) {
            TL_bots.allowSendMessage req = new TL_bots.allowSendMessage();
            req.bot = MessagesController.getInstance(currentAccount).getInputUser(user);
            ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> {
                if (res instanceof TLRPC.Updates) {
                    MessagesController.getInstance(currentAccount).processUpdates((TLRPC.Updates) res, false);
                }
            });
        }
    }

    private void decline(long botId, TLRPC.User user) {
        if (!declined.contains(botId)) {
            declined.add(botId);
        }
        save();
        pushState(botId, "declined", user != null ? UserObject.getPublicUsername(user) : null);
    }

    /** Long press in the menu, or ⋮ → Delete bot. A mini app is not asked again after this. */
    public void remove(long botId) {
        Bot bot = find(botId);
        if (bot == null) {
            return;
        }
        bots.remove(bot);
        decline(botId, MessagesController.getInstance(currentAccount).getUser(botId));
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

    // ---------------------------------------------------------------------------------------------
    // The bot's settings

    /** The chat types the bot's button shows in and write access, as the bot set them. */
    private static void applyConfig(Bot bot, JSONObject config) {
        bot.peer_types.clear();
        JSONArray types = config != null ? config.optJSONArray("peer_types") : null;
        if (types == null || types.length() == 0) {
            for (String type : DEFAULT_PEER_TYPES) {
                addPeerType(bot, type);
            }
        } else {
            for (int i = 0; i < types.length(); i++) {
                addPeerType(bot, types.optString(i));
            }
        }
        bot.request_write_access = config != null && config.optBoolean("write_access");
    }

    private static void addPeerType(Bot bot, String type) {
        switch (type) {
            case "same_bot": bot.peer_types.add(new TLRPC.TL_attachMenuPeerTypeSameBotPM()); break;
            case "bots": bot.peer_types.add(new TLRPC.TL_attachMenuPeerTypeBotPM()); break;
            case "users": bot.peer_types.add(new TLRPC.TL_attachMenuPeerTypePM()); break;
            case "groups": bot.peer_types.add(new TLRPC.TL_attachMenuPeerTypeChat()); break;
            case "channels": bot.peer_types.add(new TLRPC.TL_attachMenuPeerTypeBroadcast()); break;
        }
    }

    /**
     * The settings of these bots (of the whole list when null), then {@code whenDone} on the UI thread,
     * whatever the answer. The endpoint is public: no token needed.
     */
    private void refreshConfig(ArrayList<Long> ids, Runnable whenDone) {
        if (ids == null) {
            ids = new ArrayList<>();
            for (Bot bot : bots) {
                ids.add(bot.bot_id);
            }
        }
        if (ids.isEmpty()) {
            if (whenDone != null) whenDone.run();
            return;
        }
        StringBuilder query = new StringBuilder();
        for (Long id : ids) {
            if (query.length() > 0) query.append(',');
            query.append(id);
        }
        SvipeApi.get("/v1/attach/config?ids=" + query, null, (res, code, err) -> AndroidUtilities.runOnUIThread(() -> {
            JSONObject map = res != null && code == 200 ? res.optJSONObject("bots") : null;
            if (map != null) {
                boolean changed = false;
                Iterator<String> keys = map.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    long id;
                    try {
                        id = Long.parseLong(key);
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    JSONObject config = map.optJSONObject(key);
                    configs.put(id, config);
                    Bot bot = find(id);
                    if (bot != null) {
                        applyConfig(bot, config);
                        changed = true;
                    }
                }
                if (changed) {
                    save();
                    changed();
                }
            }
            if (whenDone != null) whenDone.run();
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // The bot's icon

    /**
     * The icon the bot gave Svipe, as a drawable to tint like Telegram's attach bot icons; null when it
     * has none (the menu shows its avatar) or while it is being fetched (the menu is redrawn after).
     */
    public android.graphics.drawable.Drawable icon(long botId) {
        JSONObject config = configs.get(botId);
        String version = config != null && !config.isNull("icon") ? config.optString("icon", "") : "";
        if (TextUtils.isEmpty(version) || !version.matches("[0-9a-f]{1,32}")) {
            return null;
        }
        final String key = botId + "_" + version;
        android.graphics.drawable.Drawable cached = icons.get(key);
        if (cached != null) {
            return cached;
        }
        final File file = new File(new File(ApplicationLoader.applicationContext.getFilesDir(), "svipe_attach_icons"), key + ".svg");
        if (file.exists()) {
            try {
                byte[] bytes = new byte[(int) Math.min(file.length(), 64 * 1024)];
                try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                    int read = 0;
                    while (read < bytes.length) {
                        int n = in.read(bytes, read, bytes.length - read);
                        if (n < 0) break;
                        read += n;
                    }
                }
                String xml = new String(bytes, "UTF-8");
                // Drawn once into a bitmap: SvgHelper's drawable ignores colour filters, and the menu
                // tints its icons with one.
                org.telegram.messenger.SvgHelper.SvgDrawable svg = org.telegram.messenger.SvgHelper.getDrawable(xml);
                if (svg != null) {
                    final int size = AndroidUtilities.dp(24);
                    android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
                    svg.setAspectFill(false);
                    svg.setBounds(0, 0, size, size);
                    svg.draw(new android.graphics.Canvas(bitmap));
                    // The icon is a mask the menu tints, but SvgHelper draws it translucent: its most
                    // opaque pixel is made fully opaque, the rest in proportion.
                    int[] pixels = new int[size * size];
                    bitmap.getPixels(pixels, 0, size, 0, 0, size, size);
                    int maxAlpha = 0;
                    for (int pixel : pixels) {
                        maxAlpha = Math.max(maxAlpha, pixel >>> 24);
                    }
                    if (maxAlpha > 0 && maxAlpha < 255) {
                        for (int i = 0; i < pixels.length; i++) {
                            int alpha = Math.min(255, (pixels[i] >>> 24) * 255 / maxAlpha);
                            pixels[i] = (alpha << 24) | 0xFFFFFF;
                        }
                        bitmap.setPixels(pixels, 0, size, 0, 0, size, size);
                    }
                    android.graphics.drawable.Drawable drawable = new android.graphics.drawable.BitmapDrawable(ApplicationLoader.applicationContext.getResources(), bitmap);
                    icons.put(key, drawable);
                    return drawable;
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            file.delete();
        }
        if (iconsLoading.add(key)) {
            file.getParentFile().mkdirs();
            SvipeApi.getFile(SvipeConfig.baseUrl() + "/v1/attach/icon/" + botId, file, (code, err) -> AndroidUtilities.runOnUIThread(() -> {
                iconsLoading.remove(key);
                if (code == 200 && file.exists()) {
                    changed();
                } else {
                    file.delete();
                }
            }));
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // The same menu on every device

    /** The answer, for the account's other devices. */
    private void pushState(long botId, String state, String username) {
        SvipeAuth.ensureToken(currentAccount, token -> {
            if (token == null) return;
            try {
                JSONObject body = new JSONObject().put("state", state);
                if (!TextUtils.isEmpty(username)) {
                    body.put("bot_username", username);
                }
                SvipeApi.put("/v1/attach/bots/" + botId, body, token, (res, code, err) -> {
                    if (code < 200 || code >= 300) {
                        FileLog.e("svipe attach menu: push " + botId + " " + code + " " + err);
                    }
                });
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    /**
     * The answers given on the account's other devices. A bot added elsewhere is added here without a
     * new dialog (the person already agreed there); one removed elsewhere goes. Bots only this device
     * knows are sent up.
     */
    private void syncFromServer() {
        SvipeAuth.ensureToken(currentAccount, token -> {
            if (token == null) return;
            SvipeApi.get("/v1/attach/bots", token, (res, code, err) -> AndroidUtilities.runOnUIThread(() -> {
                JSONArray list = res != null && code == 200 ? res.optJSONArray("bots") : null;
                if (list == null) return;
                ArrayList<Long> known = new ArrayList<>();
                ArrayList<Long> fresh = new ArrayList<>();
                boolean changed = false;
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.optJSONObject(i);
                    if (item == null) continue;
                    long id = item.optLong("bot_id");
                    if (id <= 0) continue;
                    known.add(id);
                    Bot existing = find(id);
                    if ("added".equals(item.optString("state"))) {
                        if (existing == null) {
                            Bot bot = new Bot();
                            bot.bot_id = id;
                            bot.username = item.isNull("bot_username") ? null : item.optString("bot_username", null);
                            TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(id);
                            bot.short_name = user != null ? UserObject.getFirstName(user) : (bot.username != null ? bot.username : "");
                            bot.show_in_attach_menu = true;
                            applyConfig(bot, configs.get(id));
                            bots.add(bot);
                            declined.remove(id);
                            fresh.add(id);
                            changed = true;
                        }
                    } else {
                        if (existing != null) {
                            bots.remove(existing);
                            changed = true;
                        }
                        if (!declined.contains(id)) {
                            declined.add(id);
                            changed = true;
                        }
                    }
                }
                for (Bot bot : bots) {
                    if (!known.contains(bot.bot_id)) {
                        pushState(bot.bot_id, "added", bot.username);
                    }
                }
                if (changed) {
                    save();
                    usersLoaded = false;
                    loadUsers();
                    resolveMissing(fresh);
                    refreshConfig(fresh, null);
                    changed();
                }
            }));
        });
    }

    /** A bot added on another device may be unknown here: find it by its username, once. */
    private void resolveMissing(ArrayList<Long> ids) {
        for (Long id : ids) {
            final Bot bot = find(id);
            if (bot == null || TextUtils.isEmpty(bot.username) || MessagesController.getInstance(currentAccount).getUser(id) != null) {
                continue;
            }
            MessagesController.getInstance(currentAccount).getUserNameResolver().resolve(bot.username, peerId -> {
                if (peerId == null || peerId != bot.bot_id) {
                    return;
                }
                TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(peerId);
                if (user != null) {
                    bot.short_name = UserObject.getFirstName(user);
                    save();
                    changed();
                }
            });
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Who the chat is with, and answerWebAppQuery

    /**
     * Receiver / chat for the mini app of a bot opened from this menu in {@code peerId}, signed by
     * Svipe. {@code whenDone} gets the URL to load, with the launch param tgWebAppSvipeData added, or
     * the URL as it was if the signature doesn't come in time. Always called once, on the UI thread.
     */
    public void signContext(long botId, long peerId, String url, String startParam, Utilities.Callback<String> whenDone) {
        final boolean[] done = new boolean[1];
        final Utilities.Callback<String> finish = result -> {
            if (done[0]) return;
            done[0] = true;
            whenDone.run(result);
        };
        if (url == null || url.indexOf('#') < 0) {
            finish.run(url);
            return;
        }
        AndroidUtilities.runOnUIThread(() -> finish.run(url), 2500);
        final JSONObject body;
        try {
            body = contextBody(botId, peerId, url, startParam);
        } catch (Exception e) {
            FileLog.e(e);
            finish.run(url);
            return;
        }
        SvipeAuth.ensureToken(currentAccount, token -> {
            if (token == null) {
                AndroidUtilities.runOnUIThread(() -> finish.run(url));
                return;
            }
            SvipeApi.post("/v1/attach/context", body, token, (res, code, err) -> AndroidUtilities.runOnUIThread(() -> {
                String signed = res != null && code == 200 ? res.optString("svipe_init_data", "") : "";
                if (TextUtils.isEmpty(signed)) {
                    finish.run(url);
                    return;
                }
                try {
                    finish.run(url + "&tgWebAppSvipeData=" + URLEncoder.encode(signed, "UTF-8"));
                } catch (Exception e) {
                    finish.run(url);
                }
            }));
        });
    }

    private JSONObject contextBody(long botId, long peerId, String url, String startParam) throws Exception {
        JSONObject body = new JSONObject().put("bot_id", botId);
        // A bot with only a menu button opens as from its own chat, and Telegram leaves the
        // startattach value out of its initData: Svipe signs it instead.
        if (!TextUtils.isEmpty(startParam) && startParam.matches("[A-Za-z0-9_-]{1,64}")) {
            body.put("start_param", startParam);
        }
        // chat_type and chat_instance as Telegram signed them into this launch's initData: the bot
        // matches them against ours, so our signature can't be carried to another chat.
        String initData = SvipeInitData.extract(url);
        if (initData != null) {
            for (String pair : initData.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String key = pair.substring(0, eq);
                if ("chat_type".equals(key) || "chat_instance".equals(key)) {
                    body.put(key, URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
                }
            }
        }
        if (peerId > 0) {
            TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(peerId);
            if (user != null) {
                JSONObject receiver = new JSONObject().put("id", user.id).put("first_name", user.first_name != null ? user.first_name : "");
                if (!TextUtils.isEmpty(user.last_name)) receiver.put("last_name", user.last_name);
                String username = UserObject.getPublicUsername(user);
                if (!TextUtils.isEmpty(username)) receiver.put("username", username);
                if (user.bot) receiver.put("is_bot", true);
                body.put("receiver", receiver);
            }
        } else if (peerId < 0) {
            TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-peerId);
            if (chat != null) {
                // Ids as the Bot API writes them: -100… for channels and supergroups, -… for groups.
                boolean channel = ChatObject.isChannel(chat);
                JSONObject c = new JSONObject()
                    .put("id", channel ? -(1000000000000L + chat.id) : -chat.id)
                    .put("type", ChatObject.isChannelAndNotMegaGroup(chat) ? "channel" : channel ? "supergroup" : "group")
                    .put("title", chat.title != null ? chat.title : "");
                String username = ChatObject.getPublicUsername(chat);
                if (!TextUtils.isEmpty(username)) c.put("username", username);
                body.put("chat", c);
            }
        }
        return body;
    }

    /**
     * Sends a message the bot prepared (Bot API savePreparedInlineMessage) to {@code peerId}, as
     * answerWebAppQuery sends one for Telegram's own attach bots. {@code whenDone} gets null when sent,
     * or the error a mini app is told: MESSAGE_EXPIRED, or MESSAGE_SEND_FAILED when the bot didn't
     * allow this kind of chat for the message.
     */
    public static void sendPrepared(int currentAccount, long botId, String id, long peerId, Utilities.Callback<String> whenDone) {
        TLRPC.TL_messages_getPreparedInlineMessage req = new TLRPC.TL_messages_getPreparedInlineMessage();
        req.bot = MessagesController.getInstance(currentAccount).getInputUser(botId);
        req.id = id;
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            if (!(res instanceof TLRPC.TL_messages_preparedInlineMessage)) {
                whenDone.run("MESSAGE_EXPIRED");
                return;
            }
            TLRPC.TL_messages_preparedInlineMessage message = (TLRPC.TL_messages_preparedInlineMessage) res;
            MessagesController.getInstance(currentAccount).putUsers(message.users, false);
            if (!allows(currentAccount, message.peer_types, botId, peerId)) {
                whenDone.run("MESSAGE_SEND_FAILED");
                return;
            }
            HashMap<String, String> params = new HashMap<>();
            params.put("query_id", "" + message.query_id);
            params.put("id", "" + message.result.id);
            params.put("bot", "" + botId);
            SendMessagesHelper.prepareSendingBotContextResult(LaunchActivity.getLastFragment(), AccountInstance.getInstance(currentAccount), message.result, params, peerId, null, null, null, null, true, 0, 0, null, 0);
            whenDone.run(null);
        }));
    }

    /** Whether the prepared message may go to this chat: the bot chose the kinds of chat it may. */
    private static boolean allows(int currentAccount, ArrayList<TLRPC.InlineQueryPeerType> types, long botId, long peerId) {
        if (types == null || types.isEmpty()) {
            return true;
        }
        boolean user = false, bot = false, sameBot = false, group = false, megagroup = false, channel = false;
        for (TLRPC.InlineQueryPeerType type : types) {
            if (type instanceof TLRPC.TL_inlineQueryPeerTypePM) user = true;
            else if (type instanceof TLRPC.TL_inlineQueryPeerTypeBotPM) bot = true;
            else if (type instanceof TLRPC.TL_inlineQueryPeerTypeSameBotPM) sameBot = true;
            else if (type instanceof TLRPC.TL_inlineQueryPeerTypeChat) group = true;
            else if (type instanceof TLRPC.TL_inlineQueryPeerTypeMegagroup) megagroup = true;
            else if (type instanceof TLRPC.TL_inlineQueryPeerTypeBroadcast) channel = true;
        }
        if (peerId > 0) {
            if (peerId == botId) return sameBot;
            TLRPC.User u = MessagesController.getInstance(currentAccount).getUser(peerId);
            return u != null && u.bot ? bot : user;
        }
        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-peerId);
        if (chat == null) return false;
        if (ChatObject.isChannelAndNotMegaGroup(chat)) return channel;
        return ChatObject.isChannel(chat) ? megagroup : group;
    }

    // ---------------------------------------------------------------------------------------------

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
                bot.username = item.has("username") ? item.getString("username") : null;
                bot.show_in_attach_menu = true;
                JSONObject config = item.optJSONObject("config");
                if (config != null) {
                    configs.put(bot.bot_id, config);
                }
                applyConfig(bot, config);
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
                if (bot.username != null) {
                    item.put("username", bot.username);
                }
                JSONObject config = configs.get(bot.bot_id);
                if (config != null) {
                    item.put("config", config);
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
