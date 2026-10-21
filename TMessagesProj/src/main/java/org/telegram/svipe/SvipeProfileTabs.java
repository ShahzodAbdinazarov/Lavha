package org.telegram.svipe;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
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
import java.util.HashMap;
import java.util.HashSet;

/**
 * Bot tabs on a person's own profile (app/api/profile_tabs.py, svipe.uz/bots/profile-tabs#profiles).
 * A registered bot asks, with a {@code svipe.uz/<bot>?profiletab} link or addToProfile() from its mini
 * app; the person agrees here and the tab is live at once, at most three, a fourth only in place of one
 * the person picks. Each is shown to everyone or to its owner only. A card on someone's profile that opens
 * the bot's mini app gets the profile's owner signed into svipeInitData (chat_type "profile").
 */
public final class SvipeProfileTabs {

    public static final int MAX = 3;

    /** One of my tabs, as the server lists them. */
    public static class Tab {
        public long botId;
        public String username;
        public String title;
        public boolean listLayout;
        public boolean everyone;
    }

    private static final ArrayList<Tab> mine = new ArrayList<>();
    private static boolean mineLoaded;
    private static final HashMap<Long, Long> pendingOwner = new HashMap<>();
    private static final HashMap<Long, Long> pendingAt = new HashMap<>();
    private static final HashMap<Long, ArrayList<SvipeBotTab.State>> profileCache = new HashMap<>();

    // ---------------------------------------------------------------------------------------------
    // My own tabs

    static ArrayList<Tab> parseTabs(JSONObject res) {
        ArrayList<Tab> out = new ArrayList<>();
        JSONArray tabs = res != null ? res.optJSONArray("tabs") : null;
        for (int i = 0; tabs != null && i < tabs.length(); i++) {
            JSONObject o = tabs.optJSONObject(i);
            if (o == null) continue;
            Tab t = new Tab();
            t.botId = o.optLong("bot_id");
            t.username = o.optString("username");
            t.title = o.optString("title", t.username);
            t.listLayout = "list".equals(o.optString("layout"));
            t.everyone = !"me".equals(o.optString("visibility"));
            out.add(t);
        }
        return out;
    }

    private static void setMine(JSONObject res) {
        mine.clear();
        mine.addAll(parseTabs(res));
        mineLoaded = true;
    }

    public static ArrayList<Tab> getMine() {
        return new ArrayList<>(mine);
    }

    public static void loadMine(int account, Runnable done) {
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                if (done != null) done.run();
                return;
            }
            SvipeApi.get("/v1/profile-tabs", token, (res, code, err) -> {
                if (res != null && code == 200) setMine(res);
                if (done != null) done.run();
            });
        });
    }

    /** For checkProfileTabStatus(): "added", "declined" or "missed". */
    public static void status(int account, long botId, Utilities.Callback<String> done) {
        Runnable answer = () -> {
            for (Tab t : mine) {
                if (t.botId == botId) {
                    done.run("added");
                    return;
                }
            }
            done.run(declined().contains(String.valueOf(botId)) ? "declined" : "missed");
        };
        if (mineLoaded) answer.run(); else loadMine(account, answer);
    }

    public static void remove(int account, long botId, Runnable done) {
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) return;
            SvipeApi.delete("/v1/profile-tabs/" + botId, token, (res, code, err) -> {
                if (res != null && code == 200) setMine(res);
                profileCache.clear();
                if (done != null) done.run();
            });
        });
    }

    public static void setEveryone(int account, long botId, boolean everyone, Runnable done) {
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) return;
            JSONObject body = new JSONObject();
            try {
                body.put("visibility", everyone ? "everyone" : "me");
            } catch (Exception ignore) {
            }
            SvipeApi.patch("/v1/profile-tabs/" + botId, body, token, (res, code, err) -> {
                if (res != null && code == 200) setMine(res);
                if (done != null) done.run();
            });
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Asking

    /**
     * The bot asks for a tab on my profile. {@code whenDone} gets (whether a dialog was shown, whether
     * the tab is on the profile now). From a mini app a declined bot isn't asked again.
     */
    public static void request(int account, TLRPC.User bot, boolean fromMiniApp, Utilities.Callback2<Boolean, Boolean> whenDone) {
        if (bot == null || !bot.bot) {
            whenDone.run(false, false);
            return;
        }
        status(account, bot.id, status -> {
            if ("added".equals(status)) {
                whenDone.run(false, true);
                return;
            }
            if (fromMiniApp && "declined".equals(status)) {
                whenDone.run(false, false);
                return;
            }
            Activity activity = LaunchActivity.instance;
            if (activity == null) {
                whenDone.run(false, false);
                return;
            }
            if (mine.size() >= MAX) {
                choose(account, activity, bot, whenDone);
            } else {
                ask(account, activity, bot, whenDone);
            }
        });
    }

    private static void ask(int account, Activity activity, TLRPC.User bot, Utilities.Callback2<Boolean, Boolean> whenDone) {
        AttachBotIntroTopView top = new AttachBotIntroTopView(activity);
        top.svipeSetIcon(R.drawable.msg_openprofile);
        top.setColor(Theme.getColor(Theme.key_chat_attachIcon));
        top.setBackgroundColor(Theme.getColor(Theme.key_dialogTopBackground));
        top.setUser(account, bot);
        final boolean[] answered = new boolean[1];
        new AlertDialog.Builder(activity)
                .setTopView(top)
                .setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.SvipeProfileTabRequest, UserObject.getUserName(bot))))
                .setPositiveButton(LocaleController.getString(R.string.SvipeProfileTabAdd), (di, w) -> {
                    if (answered[0]) return;
                    answered[0] = true;
                    add(account, bot, 0, whenDone);
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

    /** Three already: which one this replaces, or cancel. Choosing is agreeing. */
    private static void choose(int account, Activity activity, TLRPC.User bot, Utilities.Callback2<Boolean, Boolean> whenDone) {
        final long[] picked = {mine.get(mine.size() - 1).botId};
        final boolean[] answered = new boolean[1];
        android.widget.LinearLayout list = new android.widget.LinearLayout(activity);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        final ArrayList<RadioColorCell> cells = new ArrayList<>();
        for (Tab t : mine) {
            RadioColorCell cell = new RadioColorCell(activity);
            cell.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
            cell.setCheckColor(Theme.getColor(Theme.key_radioBackground), Theme.getColor(Theme.key_dialogRadioBackgroundChecked));
            cell.setTextAndValue(t.title, t.botId == picked[0]);
            cell.setOnClickListener(v -> {
                picked[0] = t.botId;
                for (RadioColorCell c : cells) c.setChecked(c == v, true);
            });
            cells.add(cell);
            list.addView(cell);
        }
        new AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(R.string.SvipeProfileTabFullTitle))
                .setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.SvipeProfileTabFullText, MAX, UserObject.getUserName(bot))))
                .setView(list)
                .setPositiveButton(LocaleController.getString(R.string.SvipeSideMenuReplace), (di, w) -> {
                    if (answered[0]) return;
                    answered[0] = true;
                    add(account, bot, picked[0], whenDone);
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

    private static void add(int account, TLRPC.User bot, long replace, Utilities.Callback2<Boolean, Boolean> whenDone) {
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                whenDone.run(true, false);
                return;
            }
            JSONObject body = new JSONObject();
            try {
                body.put("bot_username", UserObject.getPublicUsername(bot));
                if (replace != 0) body.put("replace_bot_id", replace);
            } catch (Exception ignore) {
            }
            SvipeApi.post("/v1/profile-tabs", body, token, (res, code, err) -> {
                BaseFragment fragment = LaunchActivity.getSafeLastFragment();
                if (res != null && code == 200) {
                    setMine(res);
                    profileCache.clear();
                    declined().remove(String.valueOf(bot.id));
                    saveDeclined();
                    if (fragment != null) {
                        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check,
                                AndroidUtilities.replaceTags(LocaleController.formatString(R.string.SvipeProfileTabAdded, UserObject.getFirstName(bot)))).show();
                    }
                    whenDone.run(true, true);
                } else {
                    String detail = res != null ? res.optString("detail") : "";
                    if (fragment != null) {
                        BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(
                                "bot_not_registered".equals(detail) ? R.string.SvipeProfileTabNotRegistered : R.string.UnknownError)).show();
                    }
                    whenDone.run(true, false);
                }
            });
        });
    }

    private static HashSet<String> declinedSet;

    private static HashSet<String> declined() {
        if (declinedSet == null) {
            declinedSet = new HashSet<>(prefs().getStringSet("declined", new HashSet<>()));
        }
        return declinedSet;
    }

    private static void decline(long botId) {
        declined().add(String.valueOf(botId));
        saveDeclined();
    }

    private static void saveDeclined() {
        prefs().edit().putStringSet("declined", new HashSet<>(declined())).apply();
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("svipe_profile_tabs", Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------------------------------------
    // Someone's profile

    public static ArrayList<SvipeBotTab.State> cached(long ownerId) {
        return profileCache.get(ownerId);
    }

    /** The tabs this person's profile shows me, each with its first page of cards. */
    public static void load(int account, long ownerId, Utilities.Callback<ArrayList<SvipeBotTab.State>> done) {
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                done.run(null);
                return;
            }
            SvipeApi.get("/v1/users/" + ownerId + "/tabs", token, (res, code, err) -> {
                if (res == null || code != 200) {
                    done.run(null);
                    return;
                }
                ArrayList<Tab> tabs = parseTabs(res);
                ArrayList<SvipeBotTab.State> states = new ArrayList<>();
                if (tabs.isEmpty()) {
                    profileCache.put(ownerId, states);
                    done.run(states);
                    return;
                }
                final int[] left = {tabs.size()};
                final SvipeBotTab.State[] slots = new SvipeBotTab.State[tabs.size()];
                for (int i = 0; i < tabs.size(); i++) {
                    final int at = i;
                    loadPage(token, ownerId, tabs.get(i).botId, 0, state -> {
                        slots[at] = state;
                        if (--left[0] == 0) {
                            for (SvipeBotTab.State s : slots) if (s != null) states.add(s);
                            profileCache.put(ownerId, states);
                            done.run(states);
                        }
                    });
                }
            });
        });
    }

    public static void loadMore(int account, SvipeBotTab.State state, Runnable done) {
        if (state == null || state.cards.size() >= state.total) return;
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) return;
            loadPage(token, state.ownerId, state.botId, state.cards.size(), more -> {
                if (more != null) state.cards.addAll(more.cards);
                done.run();
            });
        });
    }

    private static void loadPage(String token, long ownerId, long botId, int offset, Utilities.Callback<SvipeBotTab.State> done) {
        SvipeApi.get("/v1/users/" + ownerId + "/tabs/" + botId + (offset > 0 ? "?offset=" + offset : ""), token, (res, code, err) -> {
            if (res == null || code != 200) {
                done.run(null);
                return;
            }
            try {
                res.put("state", "active");
            } catch (Exception ignore) {
            }
            SvipeBotTab.State state = SvipeBotTab.parse(0, res);
            state.ownerId = ownerId;
            state.botId = botId;
            done.run(state);
        });
    }

    public static void report(int account, SvipeBotTab.State state, String reason) {
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) return;
            JSONObject body = new JSONObject();
            try {
                body.put("reason", reason);
            } catch (Exception ignore) {
            }
            SvipeApi.post("/v1/users/" + state.ownerId + "/tabs/" + state.botId + "/report", body, token, (res, code, err) -> {});
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Who is looking

    /** A card on {@code ownerId}'s profile is about to open {@code botId}'s mini app. */
    public static void openingFromProfile(long botId, long ownerId) {
        pendingOwner.put(botId, ownerId);
        pendingAt.put(botId, System.currentTimeMillis());
    }

    /**
     * Where a mini app's URL is loaded: if it was opened from a card on someone's profile in the last
     * half minute, the URL gets the owner signed in (chat_type "profile"); otherwise it goes as it was.
     */
    public static void withProfileContext(int account, long botId, String url, Utilities.Callback<String> load) {
        Long owner = pendingOwner.remove(botId);
        Long at = pendingAt.remove(botId);
        if (owner == null || at == null || System.currentTimeMillis() - at > 30_000) {
            load.run(url);
            return;
        }
        SvipeAttachMenu.get(account).signContext(botId, owner, url, null, "profile", load);
    }
}
