package org.telegram.svipe;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.MusicSongActivity;
import org.telegram.ui.SvipeWatchActivity;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * The svipe.uz link, both ways: minting one for anything we can share, and OPENING one when the
 * device that taps it already has Svipe installed.
 *
 * <p><b>Why the app has to open its own links.</b> Every share we send out is a {@code svipe.uz/<code>}
 * page whose job is to sell the app to somebody who does not have it. On a phone that already does,
 * that page is exactly the wrong destination: it makes an installed user watch a web preview of a
 * video their own app could play, behind an "install" button they have already pressed. Android's
 * answer is App Links — the intent filter in the manifest plus the {@code /.well-known/assetlinks.json}
 * the backend serves — and this class is what happens after one is caught.
 *
 * <p><b>What a link carries.</b> {@code /<code>} is one opaque code, resolved server-side (public, no
 * auth) to a Telegram post, a song or a show. {@code ?p=<code>} names the PLAYLIST a video was shared
 * from, so an episode shared out of a show opens inside that show — panel, position, running order —
 * rather than as a loose video that happens to be episode seven of something.
 */
public final class SvipeShareLink {

    private SvipeShareLink() {
    }

    /** Hosts whose {@code /<code>} pages are ours to open. Both environments, so a beta build can
     *  still open a prod link (and the other way round) instead of bouncing it to a browser. */
    private static final String[] HOSTS = {
            "svipe.uz", "www.svipe.uz", "lavha-dev.abdinazarov.uz",
    };

    /** Top-level paths that are PAGES, not codes — the backend's own reserved list, mirrored. */
    private static final java.util.Set<String> RESERVED = new java.util.HashSet<>(java.util.Arrays.asList(
            "api", "admin", "health", "dl", "privacy", "terms", "delete", "webapp", "docs", "redoc",
            "openapi.json", "favicon.ico", "robots.txt", "static", "assets", "og.png", "v1", "r", "s",
            "android"));

    public static boolean isOurHost(Uri uri) {
        if (uri == null || uri.getHost() == null) {
            return false;
        }
        final String host = uri.getHost().toLowerCase();
        for (String h : HOSTS) {
            if (h.equals(host)) {
                return true;
            }
        }
        return false;
    }

    /** The share code in {@code https://svipe.uz/<code>}, or null when the path is a page of ours. */
    private static String codeOf(Uri uri) {
        final List<String> segments = uri.getPathSegments();
        if (segments == null || segments.size() != 1) {
            return null;
        }
        final String code = segments.get(0);
        if (code.isEmpty() || code.length() > 16 || RESERVED.contains(code.toLowerCase())) {
            return null;
        }
        for (int i = 0; i < code.length(); i++) {
            final char c = code.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-') {
                return null;
            }
        }
        return code;
    }

    /**
     * Take over a tapped svipe.uz link. Returns true when this class owns the URL — the caller must
     * then stop: a link we own is never also a Telegram link.
     *
     * <p>Anything on our host that is NOT a code (the privacy page, the download page, /admin) is
     * still ours to answer for, because the intent filter is per HOST — Android has no way to say
     * "only these paths" that works on every version we ship to. Those go to a browser, explicitly
     * NOT this app, or the tap would bounce straight back here forever.
     */
    public static boolean handle(int account, Uri uri) {
        if (!isOurHost(uri)) {
            return false;
        }
        final String code = codeOf(uri);
        if (code == null) {
            openOutside(uri);
            return true;
        }
        resolve(uri, code, uri.getQueryParameter("p"), json -> {
            if (json == null) {
                openOutside(uri);   // an unknown code is a page we do not know about; let the web have it
                return;
            }
            open(account, json);
        });
        return true;
    }

    private interface Json {
        void run(JSONObject json);
    }

    /**
     * Resolve the code against the link's OWN origin rather than this build's backend: a prod link
     * tapped on a beta build must still resolve, and the resolver is public precisely so it can.
     */
    private static void resolve(Uri uri, String code, String list, Json cb) {
        final String url = uri.getScheme() + "://" + uri.getHost() + "/v1/share/" + Uri.encode(code)
                + (list != null && !list.isEmpty() ? "?p=" + Uri.encode(list) : "");
        Utilities.globalQueue.postRunnable(() -> {
            JSONObject result = null;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Accept", "application/json");
                if (conn.getResponseCode() == 200) {
                    final InputStream in = conn.getInputStream();
                    final ByteArrayOutputStream out = new ByteArrayOutputStream();
                    final byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                    result = new JSONObject(out.toString("UTF-8"));
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Exception ignore) {}
                }
            }
            final JSONObject answer = result;
            AndroidUtilities.runOnUIThread(() -> cb.run(answer));
        });
    }

    /** Put the resolved thing on screen. */
    private static void open(int account, JSONObject json) {
        final LaunchActivity activity = LaunchActivity.instance;
        if (activity == null) {
            return;
        }
        final String kind = json.optString("kind", "reel");
        if ("song".equals(kind)) {
            activity.presentFragment(new MusicSongActivity(json.optLong("song_id"), null));
            return;
        }
        if ("series".equals(kind)) {
            openSeries(account, json.optLong("series_id"), 0, 0);
            return;
        }
        final SvipeDiscover.Item item = new SvipeDiscover.Item();
        item.channelId = json.optLong("channel_id");
        item.messageId = json.optInt("message_id");
        item.username = json.isNull("username") ? null : json.optString("username", null);
        item.width = json.optInt("width", 16);
        item.height = json.optInt("height", 9);
        item.durationMs = json.optInt("duration_ms");
        if (item.messageId == 0 || item.username == null) {
            return;
        }
        final long seriesId = json.optLong("series_id");
        if (seriesId != 0) {
            // Shared out of a playlist: the video opens INSIDE its show, which is the whole difference
            // between "here is episode 7" and "here is a show, you are on episode 7 of 24".
            openSeries(account, seriesId, item.channelId, item.messageId);
            return;
        }
        activity.presentFragment(new SvipeWatchActivity(item));
    }

    /**
     * Open a show, at the episode the link named when it named one.
     *
     * <p>Falls back to the plain watch page if the show cannot be loaded — a shared link must play the
     * video it promised even when the playlist around it is unavailable.
     */
    private static void openSeries(int account, long seriesId, long channelId, int messageId) {
        SvipeMovies.seriesDetail(account, seriesId, (page, err) -> {
            final LaunchActivity activity = LaunchActivity.instance;
            if (activity == null) {
                return;
            }
            if (page == null || page.isEmpty()) {
                if (messageId != 0) {
                    final SvipeDiscover.Item item = new SvipeDiscover.Item();
                    item.channelId = channelId;
                    item.messageId = messageId;
                    item.width = 16;
                    item.height = 9;
                    activity.presentFragment(new SvipeWatchActivity(item));
                }
                return;
            }
            int at = 0;
            if (messageId != 0) {
                for (int i = 0; i < page.episodes.size(); i++) {
                    final SvipeMovies.Episode e = page.episodes.get(i);
                    if (e.channelId == channelId && e.messageId == messageId) {
                        at = i;
                        break;
                    }
                }
            } else {
                at = Math.max(0, Math.min(SvipeSeriesProgress.lastEpisode(seriesId),
                        page.episodes.size() - 1));
            }
            activity.presentFragment(SvipeWatchActivity.ofSeries(page, at));
        });
    }

    /**
     * Hand a URL on our host that is not a share code to a real browser — with the target package set
     * explicitly, because an ordinary ACTION_VIEW would be caught by our own intent filter and land
     * right back in this method.
     */
    private static void openOutside(Uri uri) {
        final Context context = LaunchActivity.instance != null
                ? LaunchActivity.instance : ApplicationLoader.applicationContext;
        if (context == null) {
            return;
        }
        try {
            final Intent view = new Intent(Intent.ACTION_VIEW, uri);
            view.addCategory(Intent.CATEGORY_BROWSABLE);
            final String self = context.getPackageName();
            // Resolve against a neutral http URL: querying with our own host would list us first.
            final Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com"));
            probe.addCategory(Intent.CATEGORY_BROWSABLE);
            final List<ResolveInfo> browsers = context.getPackageManager().queryIntentActivities(probe, 0);
            for (ResolveInfo info : browsers) {
                if (info.activityInfo != null && !self.equals(info.activityInfo.packageName)) {
                    view.setPackage(info.activityInfo.packageName);
                    break;
                }
            }
            if (view.getPackage() == null) {
                return;   // no browser at all: doing nothing beats bouncing the tap back to ourselves
            }
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(view);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public interface MintCallback {
        /** The {@code svipe.uz/<code>} link, or null when the post is not one we index. */
        void onResult(String shareUrl);
    }

    /**
     * Mint the owned share link for a post that arrived without one.
     *
     * <p>Most references carry a {@code shareUrl} because the pipe that produced them attached one.
     * Some do not — an episode from an older server, a film version, a reference restored from a cold
     * queue — and those used to fall back to a {@code t.me} link, which is the one share that ends
     * outside Svipe. One request fixes that for every pipe at once.
     */
    public static void mint(int account, long channelId, int messageId, MintCallback cb) {
        if (channelId == 0 || messageId == 0) {
            cb.onResult(null);
            return;
        }
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                cb.onResult(null);
                return;
            }
            SvipeApi.get("/v1/share/mint?channel_id=" + channelId + "&message_id=" + messageId,
                    token, (res, code, err) -> {
                        final String url = res == null || res.isNull("share_url")
                                ? null : res.optString("share_url", null);
                        cb.onResult(url != null && !url.isEmpty() ? url : null);
                    });
        });
    }

    /** The {@code <code>} at the end of a {@code svipe.uz/<code>} link, for use as a {@code ?p=}. */
    public static String codeOf(String shareUrl) {
        if (shareUrl == null || shareUrl.isEmpty()) {
            return null;
        }
        try {
            return codeOf(Uri.parse(shareUrl));
        } catch (Exception e) {
            return null;
        }
    }
}
