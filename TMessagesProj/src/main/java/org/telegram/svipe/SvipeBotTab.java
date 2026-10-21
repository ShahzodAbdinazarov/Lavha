package org.telegram.svipe;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * A bot's tab on a channel profile (svipe.uz/bots/profile-tabs): the channel's admin asks for a
 * registered bot, the bot checks that person is an admin and accepts, and the bot fills the tab with
 * cards. This class is the client for /v1/channel-tabs; everything it returns arrives on the UI thread.
 *
 * Card images come from our server, never from the bot's, so opening the tab tells the bot nothing.
 */
public class SvipeBotTab {

    public static class Card {
        public String id;
        public String title;
        public String subtitle;
        public String badge;
        public String link;
        public String image;
    }

    public static class State {
        public long channelId;
        // On a person's profile (SvipeProfileTabs): whose profile, and which bot's tab.
        public long ownerId;
        public long botId;
        public boolean active;
        public String botUsername;
        public String title;
        public boolean listLayout;
        public boolean isAttacher;
        public boolean canRename;
        public int total;
        public final ArrayList<Card> cards = new ArrayList<>();
        /** This person's own request for the channel, for the admin screen: bot and pending/active/refused. */
        public String myRequestBot;
        public String myRequestState;
    }

    private static final HashMap<Long, State> cache = new HashMap<>();

    /** The last state we saw for a channel, so its profile can show the tab before the network answers. */
    public static State cached(long channelId) {
        return cache.get(channelId);
    }

    public static void load(int account, long channelId, Utilities.Callback<State> done) {
        loadPage(account, channelId, 0, done);
    }

    /** The next page of cards, appended to {@code state}. */
    public static void loadMore(int account, State state, Runnable done) {
        if (state == null || state.cards.size() >= state.total) {
            return;
        }
        loadPage(account, state.channelId, state.cards.size(), more -> {
            if (more != null && more.active) {
                state.cards.addAll(more.cards);
            }
            done.run();
        });
    }

    private static void loadPage(int account, long channelId, int offset, Utilities.Callback<State> done) {
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                done.run(null);
                return;
            }
            SvipeApi.get("/v1/channel-tabs/" + channelId + (offset > 0 ? "?offset=" + offset : ""), token,
                    (res, code, err) -> {
                if (res == null || code < 200 || code >= 300) {
                    done.run(null);
                    return;
                }
                State state = parse(channelId, res);
                if (offset == 0) {
                    cache.put(channelId, state);
                }
                done.run(state);
            });
        });
    }

    static State parse(long channelId, JSONObject res) {
        State state = new State();
        state.channelId = channelId;
        state.active = "active".equals(res.optString("state"));
        JSONObject bot = res.optJSONObject("bot");
        state.botUsername = bot != null ? bot.optString("username", null) : null;
        state.title = res.optString("title", state.botUsername);
        state.listLayout = "list".equals(res.optString("layout"));
        state.isAttacher = res.optBoolean("is_attacher");
        state.canRename = res.optBoolean("can_rename");
        state.total = res.optInt("total");
        JSONObject mine = res.optJSONObject("my_request");
        if (mine != null) {
            state.myRequestBot = mine.isNull("bot_username") ? null : mine.optString("bot_username");
            state.myRequestState = mine.optString("state", null);
        }
        JSONArray items = res.optJSONArray("items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                Card card = new Card();
                card.id = o.optString("id");
                card.title = o.optString("title");
                card.subtitle = o.isNull("subtitle") ? null : o.optString("subtitle");
                card.badge = o.isNull("badge") ? null : o.optString("badge");
                card.link = o.optString("link");
                card.image = o.isNull("image") ? null : o.optString("image");
                state.cards.add(card);
            }
        }
        return state;
    }

    /** Result of an admin action: the error code from the server, or null when it worked. */
    public interface Done {
        void run(String error);
    }

    public static void attach(int account, long channelId, String botUsername, Done done) {
        withToken(account, done, token -> {
            JSONObject body = new JSONObject();
            try {
                body.put("bot_username", botUsername);
            } catch (Exception ignore) {
            }
            SvipeApi.put("/v1/channel-tabs/" + channelId, body, token, (res, code, err) -> finish(res, code, done));
        });
    }

    public static void detach(int account, long channelId, Done done) {
        withToken(account, done, token -> SvipeApi.delete("/v1/channel-tabs/" + channelId, token,
                (res, code, err) -> {
            cache.remove(channelId);
            finish(res, code, done);
        }));
    }

    public static void rename(int account, long channelId, String title, Done done) {
        withToken(account, done, token -> {
            JSONObject body = new JSONObject();
            try {
                body.put("title", title == null || title.trim().isEmpty() ? JSONObject.NULL : title.trim());
            } catch (Exception ignore) {
            }
            SvipeApi.put("/v1/channel-tabs/" + channelId + "/title", body, token, (res, code, err) -> finish(res, code, done));
        });
    }

    public static void report(int account, long channelId, String reason, Done done) {
        withToken(account, done, token -> {
            JSONObject body = new JSONObject();
            try {
                body.put("reason", reason);
            } catch (Exception ignore) {
            }
            SvipeApi.post("/v1/channel-tabs/" + channelId + "/report", body, token, (res, code, err) -> finish(res, code, done));
        });
    }

    private static void withToken(int account, Done done, Utilities.Callback<String> run) {
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                done.run("no_token");
            } else {
                run.run(token);
            }
        });
    }

    private static void finish(JSONObject res, int code, Done done) {
        if (code >= 200 && code < 300) {
            done.run(null);
        } else {
            done.run(res != null ? res.optString("detail", "error") : "network");
        }
    }
}
