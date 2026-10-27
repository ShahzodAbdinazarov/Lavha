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
        // Read by some layouts only (svipe.uz/bots/profile-tabs#layouts).
        public String description;  // links
        public String urlText;      // links: the address shown
        public String fileName;     // files
        public long size;           // files, bytes
        public String performer;    // music
        public int duration;        // music, voice, video tiles; seconds
        public String audio;        // music, voice: played in the app, from our server
        public boolean isVideo;     // media, stories, gifs
        public String price;        // gifts
        public String ribbon;       // gifts
    }

    public static class State {
        public long channelId;
        /** "channel" or "supergroup": also svipeInitData's chat_type for the tab's mini apps. */
        public String chatType = "channel";
        // On a person's profile (SvipeProfileTabs): whose profile, and which bot's tab.
        public long ownerId;
        public long botId;
        public boolean active;
        public String botUsername;
        public String title;
        public boolean listLayout;
        /** The bot's layout: grid, list, media, stories, gifs, gifts, files, links, music, voice, people. */
        public String layout = "grid";
        /** "cards", or "web": the bot's page ({@link #webUrl}) fills the tab (SvipeBotTabWebCell). */
        public String mode = "cards";
        public String webUrl;
        public boolean isAttacher;
        public boolean canRename;
        public int total;
        public final ArrayList<Card> cards = new ArrayList<>();
        /** This person's own request for the channel, for the admin screen: bot and pending/active/refused. */
        public boolean isWeb() {
            return "web".equals(mode) && webUrl != null;
        }

        public String myRequestBot;
        public String myRequestState;

        /** The dialog the tab is in, for svipeInitData: a channel or supergroup, a basic group, or a person. */
        public long dialogId() {
            if (ownerId != 0) {
                // On a person's profile or in our private chat the person is the receiver; Saved Messages and
                // a bot's own profile have no one else to name.
                return "saved".equals(chatType) || "bot".equals(chatType) ? 0 : ownerId;
            }
            return "group".equals(chatType) ? channelId : -channelId;
        }
    }

    /** Where a chat's tab lives on the server: a channel, supergroup or basic group (keyed by its negative
     *  id), or a private chat / Saved Messages (svipe.uz/bots/profile-tabs#places). */
    static String path(String chatType, long key) {
        if ("private".equals(chatType)) {
            return "/v1/chat-tabs/private/" + key;
        }
        if ("saved".equals(chatType)) {
            return "/v1/chat-tabs/saved/0";
        }
        return "/v1/channel-tabs/" + key;
    }

    private static boolean isChatOfMany(String chatType) {
        return !"private".equals(chatType) && !"saved".equals(chatType);
    }

    private static final HashMap<Long, State> cache = new HashMap<>();

    /** The last state we saw for a channel, so its profile can show the tab before the network answers. */
    public static State cached(long channelId) {
        return cache.get(channelId);
    }

    public static void load(int account, long channelId, Utilities.Callback<State> done) {
        loadPage(account, "channel", channelId, 0, done);
    }

    public static void load(int account, String chatType, long key, Utilities.Callback<State> done) {
        loadPage(account, chatType, key, 0, done);
    }

    /** The next page of cards, appended to {@code state}. */
    public static void loadMore(int account, State state, Runnable done) {
        if (state == null || state.cards.size() >= state.total) {
            return;
        }
        loadPage(account, state.chatType, state.channelId, state.cards.size(), more -> {
            if (more != null && more.active) {
                state.cards.addAll(more.cards);
            }
            done.run();
        });
    }

    private static void loadPage(int account, String chatType, long channelId, int offset, Utilities.Callback<State> done) {
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                done.run(null);
                return;
            }
            SvipeApi.get(path(chatType, channelId) + (offset > 0 ? "?offset=" + offset : ""), token,
                    (res, code, err) -> {
                if (res == null || code < 200 || code >= 300) {
                    done.run(null);
                    return;
                }
                State state = parse(channelId, res);
                if (offset == 0 && isChatOfMany(chatType)) {
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
        if (bot != null && state.botId == 0) {
            state.botId = bot.optLong("bot_id");
        }
        state.mode = res.optString("mode", "cards");
        state.chatType = res.optString("chat_type", "channel");
        state.webUrl = str(res, "web_url");
        state.title = res.optString("title", state.botUsername);
        state.layout = res.optString("layout", "grid");
        state.listLayout = SvipeBotTabAdapter.isRowLayout(state.layout) || state.isWeb();
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
                card.description = str(o, "description");
                card.urlText = str(o, "url_text");
                card.fileName = str(o, "file_name");
                card.size = o.optLong("size");
                card.performer = str(o, "performer");
                card.duration = o.optInt("duration");
                card.audio = str(o, "audio");
                card.isVideo = o.optBoolean("is_video");
                card.price = str(o, "price");
                card.ribbon = str(o, "ribbon");
                state.cards.add(card);
            }
        }
        return state;
    }

    private static String str(JSONObject o, String key) {
        return o.has(key) && !o.isNull(key) ? o.optString(key) : null;
    }

    /** Result of an admin action: the error code from the server, or null when it worked. */
    public interface Done {
        void run(String error);
    }

    public static void attach(int account, String chatType, long channelId, String botUsername, Done done) {
        withToken(account, done, token -> {
            JSONObject body = new JSONObject();
            try {
                body.put("bot_username", botUsername);
                if (isChatOfMany(chatType)) {
                    body.put("chat_type", chatType);
                }
            } catch (Exception ignore) {
            }
            SvipeApi.put(path(chatType, channelId), body, token, (res, code, err) -> finish(res, code, done));
        });
    }

    public static void detach(int account, String chatType, long channelId, Done done) {
        withToken(account, done, token -> SvipeApi.delete(path(chatType, channelId), token,
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
