package org.telegram.svipe;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * Bounded, most-recent-first ledger of the RESULTS the user tapped from music search — a recent SONG
 * or a recent ARTIST, stored as the item itself (native-Telegram style) rather than the typed query.
 * The recent list therefore renders with the same cells the live results use, and tapping a recent
 * re-opens that item instead of re-running a search. A search that never yields a tap stores nothing.
 *
 * Deduped by (type, id) — the newest tap floats to the top; the oldest evicts once the cap is hit.
 * Backed by a single JSON-array blob in the account's main settings, serialized + persisted off the
 * UI thread — same shape as {@link SvipeWatchedSet}. Its sibling {@link SvipeVideoSearchHistory} holds
 * video references under a different key.
 */
public class SvipeMusicSearchHistory {

    public static final String TYPE_SONG = "song";
    public static final String TYPE_ARTIST = "artist";

    private static final int MAX_ITEMS = 20;

    /** A tapped result: exactly one of {@link #song} / {@link #artist} is set, per {@link #type}. */
    public static class Item {
        public final String type;
        public final SvipeMusic.Song song;
        public final SvipeMusic.Artist artist;

        private Item(String type, SvipeMusic.Song song, SvipeMusic.Artist artist) {
            this.type = type;
            this.song = song;
            this.artist = artist;
        }

        public static Item ofSong(SvipeMusic.Song s) {
            return new Item(TYPE_SONG, s, null);
        }

        public static Item ofArtist(SvipeMusic.Artist a) {
            return new Item(TYPE_ARTIST, null, a);
        }

        public boolean isSong() {
            return TYPE_SONG.equals(type);
        }

        /** The canonical id used both for dedup and to re-open the item. */
        public long id() {
            if (isSong()) return song != null ? song.id : 0;
            return artist != null ? artist.id : 0;
        }

        boolean sameKey(Item o) {
            return o != null && type.equals(o.type) && id() == o.id();
        }
    }

    private final int account;
    private final LinkedList<Item> items = new LinkedList<>();

    public SvipeMusicSearchHistory(int account) {
        this.account = account;
        load();
    }

    /** The SvipeConfig key this store persists under. */
    private String prefKey() {
        return SvipeConfig.PREF_MUSIC_SEARCH_HISTORY;
    }

    public void add(SvipeMusic.Song song) {
        if (song != null) addItem(Item.ofSong(song));
    }

    public void add(SvipeMusic.Artist artist) {
        if (artist != null) addItem(Item.ofArtist(artist));
    }

    private synchronized void addItem(Item item) {
        if (item == null || item.id() == 0) return;
        for (Iterator<Item> it = items.iterator(); it.hasNext(); ) {
            if (it.next().sameKey(item)) {
                it.remove();
                break;
            }
        }
        items.addFirst(item);
        while (items.size() > MAX_ITEMS) {
            items.removeLast();
        }
        persist();
    }

    /** Most-recent-first snapshot (a copy — safe to iterate without the lock). */
    public synchronized List<Item> getAll() {
        return new ArrayList<>(items);
    }

    public synchronized void remove(Item item) {
        if (item == null) return;
        boolean changed = false;
        for (Iterator<Item> it = items.iterator(); it.hasNext(); ) {
            if (it.next().sameKey(item)) {
                it.remove();
                changed = true;
                break;
            }
        }
        if (changed) persist();
    }

    public synchronized void clear() {
        if (items.isEmpty()) return;
        items.clear();
        persist();
    }

    public synchronized int size() {
        return items.size();
    }

    private SharedPreferences prefs() {
        return MessagesController.getMainSettings(account);
    }

    private void load() {
        items.clear();
        try {
            String blob = prefs().getString(prefKey(), null);
            if (blob == null || blob.isEmpty()) return;
            JSONArray arr = new JSONArray(blob);
            for (int i = 0; i < arr.length(); i++) {
                Item item = fromJson(arr.optJSONObject(i));
                if (item != null) items.add(item);
            }
        } catch (Exception ex) {
            FileLog.e(ex);
        }
    }

    private void persist() {
        // Snapshot under the caller's lock (cheap), then serialize + write off the UI thread.
        final Item[] snapshot = items.toArray(new Item[0]);
        final String key = prefKey();
        Utilities.globalQueue.postRunnable(() -> {
            try {
                JSONArray arr = new JSONArray();
                for (Item item : snapshot) {
                    JSONObject o = toJson(item);
                    if (o != null) arr.put(o);
                }
                prefs().edit().putString(key, arr.toString()).apply();
            } catch (Exception ex) {
                FileLog.e(ex);
            }
        });
    }

    /** {type, ...song|artist fields}. The type sits alongside the model fields (the parsers ignore it). */
    private static JSONObject toJson(Item item) {
        if (item == null) return null;
        try {
            JSONObject o = item.isSong() ? SvipeMusic.songToJson(item.song)
                    : SvipeMusic.artistToJson(item.artist);
            if (o == null) return null;
            o.put("type", item.type);
            return o;
        } catch (Exception ex) {
            FileLog.e(ex);
            return null;
        }
    }

    private static Item fromJson(JSONObject o) {
        if (o == null) return null;
        String type = o.optString("type", TYPE_SONG);
        if (TYPE_ARTIST.equals(type)) {
            SvipeMusic.Artist a = SvipeMusic.artistFromJson(o);
            return a != null ? Item.ofArtist(a) : null;
        }
        SvipeMusic.Song s = SvipeMusic.songFromJson(o);
        return s != null ? Item.ofSong(s) : null;
    }
}
