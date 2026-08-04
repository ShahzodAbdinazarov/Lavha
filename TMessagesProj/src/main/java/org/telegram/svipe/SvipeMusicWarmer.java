package org.telegram.svipe;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Has "My Vibe" ready before the Music tab is opened.
 *
 * Opening the tab runs the same shape of chain reels used to: token, {@code /v1/music/vibe}, an
 * MTProto resolve per channel in the page, then audio bytes — and the user watches a hero button
 * do nothing until all of it lands. Doing that quietly at app start turns the tap into playback
 * that starts immediately. Mirrors {@link SvipeReelWarmer}; see it for the ground rules.
 *
 * One difference worth stating: this only runs for someone who has opened the Music tab before.
 * Reels is where the app lands, so warming it serves everyone; music is a place you choose to go,
 * and resolving channels for a user who never goes there would spend their data — and their
 * contacts.resolveUsername budget, which is the resource the whole Svipe layer's health rests on
 * (see {@link SvipeAuth}). So the first visit pays, and every visit after it is instant.
 */
public final class SvipeMusicWarmer {

    /** A warmed page is worth using for this long; the vibe is personalised and moves on. */
    private static final long FRESH_FOR_MS = 10 * 60 * 1000L;

    /** What the warm-up prepared, handed over once. */
    public static class Warm {
        public List<SvipeMusic.Track> items;
        public Map<String, TLRPC.Message> resolved;
        public String recommendationId;
        public String cursor;
    }

    private static boolean started;
    private static long warmedAtMs;
    private static Warm warm;

    private SvipeMusicWarmer() {}

    /** Remember that this user actually uses the Music tab — the gate for warming it at all. */
    public static void markUsed(int account) {
        try {
            MessagesController.getMainSettings(account).edit()
                    .putBoolean(SvipeConfig.PREF_MUSIC_USED, true).apply();
        } catch (Exception ignore) {
            // best-effort
        }
    }

    public static boolean isUsed(int account) {
        try {
            return MessagesController.getMainSettings(account).getBoolean(SvipeConfig.PREF_MUSIC_USED, false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Run the warm-up, reporting completion so {@link SvipeWarmup} can move on. Every exit reports;
     * a silent return would hold the queue until its deadline.
     */
    public static void warm(final int account, final Runnable done) {
        try {
            if (started) { done.run(); return; }
            started = true;
            if (!UserConfig.getInstance(account).isClientActivated()) { done.run(); return; }
            if (!isUsed(account)) { done.run(); return; } // never opened Music — spend nothing
            SvipeMusic.vibe(account, null, null, null, (items, recId, cursor, error) -> {
                if (items == null || items.isEmpty()) {
                    FileLog.d("svipe: music warm-up got nothing (" + error + ")");
                    done.run();
                    return;
                }
                // Deliberately NOT sending VIBE_OPEN here: that event starts a listening session and
                // rotates the vibe epoch, and it belongs to the moment the user opens the tab — not
                // to a background fetch they never asked for.
                SvipeMusicResolver.resolve(account, items, resolved -> {
                    Warm w = new Warm();
                    w.items = new ArrayList<>(items);
                    w.resolved = resolved;
                    w.recommendationId = recId;
                    w.cursor = cursor;
                    warm = w;
                    warmedAtMs = System.currentTimeMillis();
                    FileLog.d("svipe: music warm-up holds " + items.size() + " tracks ("
                            + (resolved != null ? resolved.size() : 0) + " resolved)");
                    preloadFirst(account, items, resolved);
                    done.run();
                });
            });
        } catch (Exception e) {
            FileLog.e(e);
            done.run();
        }
    }

    /**
     * Head-preload the first track (Telegram's cacheType 10 — a couple of MB, LOW priority). Audio
     * files are small and the player reuses every preloaded byte when it streams the same file, so
     * this is what turns "tap, wait, hear something" into "tap, hear something".
     */
    private static void preloadFirst(int account, List<SvipeMusic.Track> items,
                                     Map<String, TLRPC.Message> resolved) {
        try {
            if (resolved == null || items.isEmpty()) return;
            TLRPC.Message first = null;
            for (SvipeMusic.Track t : items) {
                first = resolved.get(t.key());
                if (first != null) break;
            }
            if (first == null || first.media == null || first.media.document == null) return;
            FileLoader.getInstance(account).loadFile(first.media.document, first, FileLoader.PRIORITY_LOW, 10);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /**
     * Hand the warmed page over — once. Null when nothing fresh is waiting, which is the normal
     * case on the first visit and after the page has gone stale.
     */
    public static Warm take() {
        Warm w = warm;
        warm = null;
        if (w == null) return null;
        if (System.currentTimeMillis() - warmedAtMs > FRESH_FOR_MS) {
            FileLog.d("svipe: music warm-up page went stale, dropping it");
            return null;
        }
        return w;
    }
}
