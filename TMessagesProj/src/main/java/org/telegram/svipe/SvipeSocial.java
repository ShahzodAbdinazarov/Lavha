package org.telegram.svipe;

import org.telegram.messenger.AndroidUtilities;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Svipe's own likes and subscriptions, kept on our server instead of Telegram.
 *
 * A Telegram reaction or join needs the message or the channel, and both need the channel's
 * access_hash — per account, and costing a resolveUsername the first time, the call whose bursts earn
 * hours of FLOOD_WAIT. These need nothing from Telegram: the state is read from
 * {@code GET /v1/videos/state} and changed through the LIKE / UNLIKE / FOLLOW / UNFOLLOW events the
 * apps already send for the recommender, which the backend now also keeps as state.
 *
 * Toggles are optimistic: the UI moves at once, the event follows. A subscription belongs to the
 * channel, so every reel of that channel sees it immediately.
 */
public final class SvipeSocial {

    /** One post's like and its channel's subscription, as this viewer sees them. */
    public static final class State {
        public int likes;
        public boolean liked;
        public int followers;
        public boolean following;
        public boolean loaded;
    }

    public interface Listener {
        void onState(State state);
    }

    private static final class Follow {
        boolean following;
        int followers;
    }

    private static final HashMap<String, State> posts = new HashMap<>();
    private static final HashMap<String, Follow> channels = new HashMap<>();
    private static final HashMap<String, ArrayList<Listener>> pending = new HashMap<>();

    private SvipeSocial() {
    }

    private static String postKey(int account, long channelId, int messageId) {
        return account + ":" + channelId + ":" + messageId;
    }

    private static String channelKey(int account, long channelId) {
        return account + ":" + channelId;
    }

    /** What is known right now, never null; {@code loaded} says whether the server has answered. */
    public static State peek(int account, long channelId, int messageId) {
        State s = posts.get(postKey(account, channelId, messageId));
        State result = new State();
        if (s != null) {
            result.likes = s.likes;
            result.liked = s.liked;
            result.loaded = s.loaded;
        }
        Follow f = channels.get(channelKey(account, channelId));
        if (f != null) {
            result.following = f.following;
            result.followers = f.followers;
        }
        return result;
    }

    /** Load the state once per post; {@code listener} gets it on the UI thread (now, if known). */
    public static void load(int account, long channelId, int messageId, Listener listener) {
        final String key = postKey(account, channelId, messageId);
        State known = posts.get(key);
        if (known != null && known.loaded) {
            if (listener != null) listener.onState(peek(account, channelId, messageId));
            return;
        }
        ArrayList<Listener> waiters = pending.get(key);
        if (waiters != null) {
            if (listener != null) waiters.add(listener);
            return;
        }
        waiters = new ArrayList<>();
        if (listener != null) waiters.add(listener);
        pending.put(key, waiters);
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                AndroidUtilities.runOnUIThread(() -> pending.remove(key));
                return;
            }
            SvipeApi.get("/v1/videos/state?channel_id=" + channelId + "&message_id=" + messageId,
                    token, (result, code, error) -> AndroidUtilities.runOnUIThread(() -> {
                        ArrayList<Listener> ready = pending.remove(key);
                        if (result == null || !result.has("likes")) return;
                        State s = posts.get(key);
                        if (s == null) {
                            s = new State();
                            posts.put(key, s);
                        }
                        // A toggle made while the request was in flight wins over the older answer.
                        if (!s.loaded) {
                            s.likes = result.optInt("likes");
                            s.liked = result.optBoolean("liked");
                        }
                        s.loaded = true;
                        String ck = channelKey(account, channelId);
                        if (!channels.containsKey(ck)) {
                            Follow f = new Follow();
                            f.following = result.optBoolean("following");
                            f.followers = result.optInt("followers");
                            channels.put(ck, f);
                        }
                        if (ready != null) {
                            State snapshot = peek(account, channelId, messageId);
                            for (Listener l : ready) l.onState(snapshot);
                        }
                    }));
        });
    }

    /**
     * Record this viewer's like locally; true when it changed. The caller sends LIKE / UNLIKE through
     * its own event path (the reels fragment batches and attributes its events).
     */
    public static boolean applyLiked(int account, long channelId, int messageId, boolean liked) {
        final String key = postKey(account, channelId, messageId);
        State s = posts.get(key);
        if (s == null) {
            s = new State();
            posts.put(key, s);
        }
        if (s.liked == liked) return false;
        s.liked = liked;
        s.likes = Math.max(0, s.likes + (liked ? 1 : -1));
        s.loaded = true;
        return true;
    }

    /** Record this viewer's subscription locally; true when it changed. The caller sends the event. */
    public static boolean applyFollowing(int account, long channelId, boolean following) {
        final String ck = channelKey(account, channelId);
        Follow f = channels.get(ck);
        if (f == null) {
            f = new Follow();
            channels.put(ck, f);
        }
        if (f.following == following) return false;
        f.following = following;
        f.followers = Math.max(0, f.followers + (following ? 1 : -1));
        return true;
    }

    /** {@link #applyLiked} and its event in one, for surfaces without an event path of their own. */
    public static State setLiked(int account, long channelId, int messageId, boolean liked, String recId) {
        if (applyLiked(account, channelId, messageId, liked)) {
            SvipeDiscover.sendEvent(account, channelId, messageId, liked ? "LIKE" : "UNLIKE", null, recId, null);
        }
        return peek(account, channelId, messageId);
    }

    /** {@link #applyFollowing} and its event in one. */
    public static State setFollowing(int account, long channelId, int messageId, boolean following, String recId) {
        if (applyFollowing(account, channelId, following)) {
            SvipeDiscover.sendEvent(account, channelId, messageId, following ? "FOLLOW" : "UNFOLLOW", null, recId, null);
        }
        return peek(account, channelId, messageId);
    }
}
