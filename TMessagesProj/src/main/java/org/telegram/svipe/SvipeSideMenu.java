package org.telegram.svipe;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Components.AttachBotIntroTopView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Bots in the app's side menu (the menu on the chat list), each there because the user agreed to it
 * when the bot asked: with a link ({@code svipe.uz/<bot>?sidemenu}) or from its mini app
 * ({@code Telegram.WebApp.addToSideMenu()}, svipe-web-app.js). Apart from the attachment menu and its
 * permission: a bot can have one, the other, or both.
 *
 * <p>At most {@link #MAX} bots. Asked for one more, the user picks which of the three it replaces, or
 * cancels. A long press on a bot in the menu takes it out. Kept on the device, per account.
 */
public final class SvipeSideMenu {

    public static final int MAX = 3;

    private static final SvipeSideMenu[] instances = new SvipeSideMenu[UserConfig.MAX_ACCOUNT_COUNT];

    public static synchronized SvipeSideMenu get(int account) {
        if (instances[account] == null) {
            instances[account] = new SvipeSideMenu(account);
        }
        return instances[account];
    }

    private final int account;
    private final ArrayList<Long> bots = new ArrayList<>();
    private final HashSet<Long> declined = new HashSet<>();

    private SvipeSideMenu(int account) {
        this.account = account;
        read("bots_" + account, bots);
        ArrayList<Long> d = new ArrayList<>();
        read("declined_" + account, d);
        declined.addAll(d);
    }

    /** The bots in the menu, in the order they were added. */
    public ArrayList<Long> getBots() {
        return new ArrayList<>(bots);
    }

    public boolean contains(long botId) {
        return bots.contains(botId);
    }

    /** For checkSideMenuStatus(): "added", "declined" or "missed". */
    public String status(long botId) {
        if (bots.contains(botId)) return "added";
        return declined.contains(botId) ? "declined" : "missed";
    }

    /**
     * Ask the user to put {@code bot} in the side menu. {@code whenDone} gets (whether a dialog was shown
     * now, whether the bot is in the menu now). From a mini app a declined bot is not asked again: a
     * link, which the user taps on purpose, always asks.
     */
    public void request(TLRPC.User bot, boolean fromMiniApp, Utilities.Callback2<Boolean, Boolean> whenDone) {
        if (bot == null || !bot.bot) {
            whenDone.run(false, false);
            return;
        }
        if (bots.contains(bot.id)) {
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
        // The same rule as the attachment menu: something to open, a Main Mini App or a menu button.
        SvipeAttachMenu.get(account).canAdd(bot, ok -> {
            if (!ok) {
                whenDone.run(false, false);
                return;
            }
            if (bots.size() < MAX) {
                ask(activity, bot, -1, whenDone);
            } else {
                choose(activity, bot, whenDone);
            }
        });
    }

    /** The permission, with the bot's avatar on top as in Telegram's attach bot dialog. */
    private void ask(Activity activity, TLRPC.User bot, long replace, Utilities.Callback2<Boolean, Boolean> whenDone) {
        AttachBotIntroTopView top = new AttachBotIntroTopView(activity);
        top.setColor(Theme.getColor(Theme.key_chat_attachIcon));
        top.setBackgroundColor(Theme.getColor(Theme.key_dialogTopBackground));
        top.setUser(account, bot);
        final boolean[] answered = new boolean[1];
        new AlertDialog.Builder(activity)
                .setTopView(top)
                .setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.SvipeSideMenuRequest, UserObject.getUserName(bot))))
                .setPositiveButton(LocaleController.getString(R.string.BotAddToMenu), (di, w) -> {
                    if (answered[0]) return;
                    answered[0] = true;
                    add(bot.id, replace);
                    whenDone.run(true, true);
                    added(bot);
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
    }

    /** The menu is full: which of the three makes room, or cancel. Choosing is agreeing. */
    private void choose(Activity activity, TLRPC.User bot, Utilities.Callback2<Boolean, Boolean> whenDone) {
        final MessagesController mc = MessagesController.getInstance(account);
        final long[] picked = {bots.get(bots.size() - 1)};
        final boolean[] answered = new boolean[1];
        android.widget.LinearLayout list = new android.widget.LinearLayout(activity);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        final ArrayList<RadioColorCell> cells = new ArrayList<>();
        for (long id : bots) {
            TLRPC.User user = mc.getUser(id);
            RadioColorCell cell = new RadioColorCell(activity);
            cell.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
            cell.setCheckColor(Theme.getColor(Theme.key_radioBackground), Theme.getColor(Theme.key_dialogRadioBackgroundChecked));
            cell.setTextAndValue(user != null ? UserObject.getUserName(user) : String.valueOf(id), id == picked[0]);
            cell.setOnClickListener(v -> {
                picked[0] = id;
                for (RadioColorCell c : cells) c.setChecked(c == v, true);
            });
            cells.add(cell);
            list.addView(cell);
        }
        new AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(R.string.SvipeSideMenuFullTitle))
                .setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.SvipeSideMenuFullText, MAX, UserObject.getUserName(bot))))
                .setView(list)
                .setPositiveButton(LocaleController.getString(R.string.SvipeSideMenuReplace), (di, w) -> {
                    if (answered[0]) return;
                    answered[0] = true;
                    add(bot.id, picked[0]);
                    whenDone.run(true, true);
                    added(bot);
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), (di, w) -> {
                    if (answered[0]) return;
                    answered[0] = true;
                    whenDone.run(true, false);
                })
                .setOnDismissListener(di -> {
                    if (answered[0]) return;
                    answered[0] = true;
                    whenDone.run(true, false);
                })
                .show();
    }

    private void added(TLRPC.User bot) {
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment != null) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check,
                    AndroidUtilities.replaceTags(LocaleController.formatString(R.string.BotAttachMenuShortcatAddedSide, UserObject.getFirstName(bot)))).show();
        }
    }

    private void add(long botId, long replace) {
        int at = replace != -1 ? bots.indexOf(replace) : -1;
        if (at >= 0) {
            bots.set(at, botId);
        } else {
            bots.add(botId);
        }
        while (bots.size() > MAX) {
            bots.remove(0);
        }
        declined.remove(botId);
        save();
    }

    private void decline(long botId) {
        declined.add(botId);
        save();
    }

    /** A long press in the menu, after the user confirmed. */
    public void remove(long botId) {
        if (bots.remove(botId)) {
            save();
        }
    }

    /** The long press: confirm, then take the bot out. */
    public void confirmRemove(Activity activity, TLRPC.User bot, Runnable done) {
        new AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(R.string.SvipeSideMenuRemoveTitle))
                .setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.SvipeSideMenuRemoveText, UserObject.getUserName(bot))))
                .setPositiveButton(LocaleController.getString(R.string.Delete), (di, w) -> {
                    remove(bot.id);
                    if (done != null) done.run();
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .makeRed(AlertDialog.BUTTON_POSITIVE)
                .show();
    }

    private void save() {
        prefs().edit()
                .putString("bots_" + account, join(bots))
                .putString("declined_" + account, join(new ArrayList<>(declined)))
                .apply();
    }

    private static String join(ArrayList<Long> ids) {
        JSONArray a = new JSONArray();
        for (Long id : ids) a.put(id);
        return a.toString();
    }

    private static void read(String key, ArrayList<Long> into) {
        String raw = prefs().getString(key, "");
        if (TextUtils.isEmpty(raw)) return;
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) into.add(a.getLong(i));
        } catch (Exception ignore) {
        }
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("svipe_side_menu", Context.MODE_PRIVATE);
    }
}
