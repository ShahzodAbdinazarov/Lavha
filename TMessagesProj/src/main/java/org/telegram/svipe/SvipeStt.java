package org.telegram.svipe;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import org.json.JSONObject;

import java.io.File;

/**
 * Voice and round-video messages as text, through our own server, for everybody.
 *
 * <p>Telegram's transcription is Premium-only and weak in the languages it was not tuned for. When
 * the server says a provider is configured ({@code GET /v1/stt/status}), {@code TranscribeButton}
 * shows the button to everyone and calls {@link #transcribe} instead of
 * {@code messages.transcribeAudio}. The voice note is the file the phone already downloaded to play
 * it; it goes up with the document id, and the server answers from its cache when somebody has asked
 * about the same voice note before.
 *
 * <p>While the server says no (no key, or unreachable), nothing changes: the button is Telegram's.
 *
 * <p>The button carries the engine on its face: "→S" for ours, "→A" for Telegram's. A long press
 * opens a choice between them, for the whole app at once ({@link #setEngine}); a person with Premium who prefers
 * Telegram's can have it back.
 */
public final class SvipeStt {

    private static final String PREFS = "svipe_stt";
    private static final String KEY_ENABLED = "enabled";
    /** Device-wide, not per account: one switch, the whole app follows. */
    private static final String KEY_ENGINE_TELEGRAM = "engine_telegram";
    /** The server's engine setup; a kept text of ours made under another one is asked for again. */
    private static final String KEY_GENERATION = "generation";
    /** How long to wait for a voice note that is not on the phone yet. */
    private static final long DOWNLOAD_WAIT_MS = 60_000;
    private static final long DOWNLOAD_POLL_MS = 300;

    private static volatile boolean statusAsked;

    private SvipeStt() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Our engine is the one the button uses right now: the server has it and nobody switched to "A". */
    public static boolean active(int account) {
        return available(account) && !prefs().getBoolean(KEY_ENGINE_TELEGRAM, false);
    }

    /** Whether the person picked Telegram's engine with a long press. */
    public static boolean telegramEngine() {
        return prefs().getBoolean(KEY_ENGINE_TELEGRAM, false);
    }

    /** Picks S or A for the whole app; every chat on screen redraws its buttons. */
    public static void setEngine(boolean telegram) {
        prefs().edit().putBoolean(KEY_ENGINE_TELEGRAM, telegram).apply();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.svipeSttEngineChanged);
    }

    /** Last answer from the server; asks again once per run, in the background. */
    public static boolean available(int account) {
        if (!statusAsked) {
            statusAsked = true;
            refresh(account);
        }
        return prefs().getBoolean(KEY_ENABLED, false);
    }

    private static void refresh(int account) {
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                statusAsked = false;
                return;
            }
            SvipeApi.get("/v1/stt/status", token, (res, code, err) -> {
                if (res == null || code != 200) {
                    statusAsked = false;
                    return;
                }
                prefs().edit().putBoolean(KEY_ENABLED, res.optBoolean("enabled", false))
                        .putString(KEY_GENERATION, res.optString("generation", "")).apply();
            });
        });
    }

    /**
     * The machine's text of {@code messageObject}, or {@code null} when it could not be had — the
     * caller then falls back to Telegram. {@code done} runs on the UI thread.
     *
     * <p>The answer also carries this person's own correction ({@code mine}) and the one most people
     * approved ({@code best}); those are put aside as layers ({@link #shown}), and the machine text
     * stays the one handed back, so it is still what gets kept as ours.
     */
    public static void transcribe(MessageObject messageObject, Utilities.Callback<String> done) {
        final TLRPC.Document doc = messageObject == null ? null : messageObject.getDocument();
        if (doc == null) {
            done.run(null);
            return;
        }
        final long docId = doc.id;
        final int duration = (int) Math.round(messageObject.getDuration());
        final String mime = mime(messageObject);
        withAudio(messageObject, (audio, token) -> {
            if (audio == null || token == null) {
                done.run(null);
                return;
            }
            SvipeApi.postFile("/v1/stt?doc_id=" + docId + "&duration=" + duration, audio, mime, token,
                    (res, code, err) -> {
                        String text = null;
                        if (res != null && code == 200) {
                            text = res.optString("text", "");
                            final String generation = res.optString("generation", "");
                            if (!TextUtils.isEmpty(generation)) {
                                prefs().edit().putString(KEY_GENERATION, generation).apply();
                            }
                            setLayers(messageObject, str(res, "mine"), str(res, "best"));
                        } else {
                            FileLog.d("svipe-stt: doc " + docId + " -> " + code);
                            if (code == 503) {
                                // The server lost its provider; stop routing here until it says so.
                                prefs().edit().putBoolean(KEY_ENABLED, false).apply();
                                statusAsked = false;
                            }
                        }
                        done.run(text);
                    });
        });
    }

    private static String mime(MessageObject messageObject) {
        return messageObject.isRoundVideo() ? "video/mp4" : "audio/ogg";
    }

    /** A JSON string field, with JSON {@code null} (and absence) as {@code null}, not "null". */
    private static String str(JSONObject o, String key) {
        return o == null || o.isNull(key) ? null : o.optString(key, null);
    }

    /**
     * The voice note on disk (downloading it first if it is not there yet) and a token, on the UI
     * thread; either is {@code null} when it could not be had.
     */
    private static void withAudio(MessageObject messageObject, Utilities.Callback2<File, String> done) {
        final TLRPC.Document doc = messageObject.getDocument();
        final int account = messageObject.currentAccount;
        File file = localFile(messageObject);
        if (file == null) {
            FileLoader.getInstance(account).loadFile(doc, messageObject, FileLoader.PRIORITY_HIGH, 0);
        }
        Utilities.globalQueue.postRunnable(() -> {
            File f = localFile(messageObject);
            final long deadline = System.currentTimeMillis() + DOWNLOAD_WAIT_MS;
            while (f == null && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(DOWNLOAD_POLL_MS);
                } catch (InterruptedException e) {
                    break;
                }
                f = localFile(messageObject);
            }
            if (f == null) {
                FileLog.d("svipe-stt: doc " + (doc == null ? 0 : doc.id) + " never arrived");
                AndroidUtilities.runOnUIThread(() -> done.run(null, null));
                return;
            }
            final File audio = f;
            SvipeAuth.ensureToken(account, token -> AndroidUtilities.runOnUIThread(() -> done.run(audio, token)));
        });
    }

    // ---- Crowd corrections ------------------------------------------------------------------
    //
    // Anyone can fix a transcript of ours. Each person always sees their own fix; everyone else
    // sees the fix most people approved; with neither, the machine's text. The machine text stays
    // the kept "ours" text (generation logic untouched) and the two fixes are layered on top of it
    // for display only, next to it in the same store, so they survive restarts and S ⇄ A swaps.

    /** Most a correction may be; the server refuses more. */
    public static final int EDIT_MAX = 4000;

    /** What a person can fix: a text of ours, finished, open, untranslated, while ours is the engine. */
    public static boolean editable(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null || messageObject.getDocument() == null
                || !messageObject.isSent() || !(messageObject.isVoice() || messageObject.isRoundVideo())) {
            return false;
        }
        final TLRPC.Message owner = messageObject.messageOwner;
        return owner.voiceTranscription != null && owner.voiceTranscriptionFinal && owner.voiceTranscriptionId == 0
                && messageObject.isVoiceTranscriptionOpen() && !messageObject.translated
                && active(messageObject.currentAccount);
    }

    /** The text to show for a message of ours: my fix, else the approved one, else the machine's. */
    public static String shown(MessageObject messageObject, String machine) {
        final String mine = layer(messageObject, "_M");
        if (!TextUtils.isEmpty(mine)) {
            return mine;
        }
        final String best = layer(messageObject, "_B");
        if (!TextUtils.isEmpty(best)) {
            return best;
        }
        return machine;
    }

    /** The machine's own text, as kept; the message's text when nothing was kept. */
    public static String machine(MessageObject messageObject) {
        final Kept k = kept(messageObject, true);
        if (k != null) {
            return k.text;
        }
        return messageObject.messageOwner == null ? null : messageObject.messageOwner.voiceTranscription;
    }

    public static String mine(MessageObject messageObject) {
        return layer(messageObject, "_M");
    }

    public static void setLayers(MessageObject messageObject, String mine, String best) {
        if (messageObject == null) {
            return;
        }
        final SharedPreferences.Editor e = texts().edit();
        putLayer(e, messageObject, "_M", mine);
        putLayer(e, messageObject, "_B", best);
        e.apply();
    }

    public static void setMine(MessageObject messageObject, String mine) {
        final SharedPreferences.Editor e = texts().edit();
        putLayer(e, messageObject, "_M", mine);
        e.apply();
    }

    public static void setBest(MessageObject messageObject, String best) {
        final SharedPreferences.Editor e = texts().edit();
        putLayer(e, messageObject, "_B", best);
        e.apply();
    }

    private static void putLayer(SharedPreferences.Editor e, MessageObject messageObject, String suffix, String text) {
        final String key = textKey(messageObject, true) + suffix;
        if (TextUtils.isEmpty(text)) {
            e.remove(key);
        } else {
            e.putString(key, text);
        }
    }

    private static String layer(MessageObject messageObject, String suffix) {
        return messageObject == null ? null : texts().getString(textKey(messageObject, true) + suffix, null);
    }

    /**
     * Puts the text a message of ours should show on screen (and in its stored copy) right now,
     * after one of its layers changed.
     */
    public static void applyShown(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null || messageObject.messageOwner.voiceTranscriptionId != 0) {
            return;
        }
        final String text = shown(messageObject, machine(messageObject));
        if (text == null || text.equals(messageObject.messageOwner.voiceTranscription)) {
            return;
        }
        messageObject.messageOwner.voiceTranscription = text;
        org.telegram.ui.Components.TranscribeButton.finishTranscription(messageObject, 0, text);
    }

    /** How many other people's versions the server last told us about, per voice note; -1 unknown. */
    private static final java.util.HashMap<Long, Integer> variantCounts = new java.util.HashMap<>();

    public static int variantCount(MessageObject messageObject) {
        final TLRPC.Document doc = messageObject.getDocument();
        final Integer n = doc == null ? null : variantCounts.get(doc.id);
        return n == null ? -1 : n;
    }

    /**
     * Sends my fix ({@code text}; empty takes it back) with the voice note it belongs to. {@code done}
     * gets the server's verdict — "ok", "suspect" — or {@code null} when it did not go through.
     */
    public static void saveEdit(MessageObject messageObject, String text, Utilities.Callback<String> done) {
        final TLRPC.Document doc = messageObject.getDocument();
        if (doc == null) {
            done.run(null);
            return;
        }
        final long docId = doc.id;
        final int duration = (int) Math.round(messageObject.getDuration());
        final String mime = mime(messageObject);
        withAudio(messageObject, (audio, token) -> {
            if (audio == null || token == null) {
                done.run(null);
                return;
            }
            final java.util.LinkedHashMap<String, String> fields = new java.util.LinkedHashMap<>();
            fields.put("text", text == null ? "" : text);
            SvipeApi.postMultipart("/v1/stt/edit?doc_id=" + docId + "&duration=" + duration, fields, "audio", audio,
                    messageObject.isRoundVideo() ? "round.mp4" : "voice.ogg", mime, token, (res, code, err) -> {
                        if (res == null || code != 200) {
                            FileLog.d("svipe-stt: edit doc " + docId + " -> " + code);
                            done.run(null);
                            return;
                        }
                        done.run(res.optString("status", "ok"));
                    });
        });
    }

    /** My vote on someone's version: 1, -1, or 0 to take it back. {@code done} gets whether it went. */
    public static void vote(int account, long editId, int value, Utilities.Callback<Boolean> done) {
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                AndroidUtilities.runOnUIThread(() -> done.run(false));
                return;
            }
            final JSONObject body = new JSONObject();
            try {
                body.put("edit_id", editId);
                body.put("value", value);
            } catch (Exception ignore) {}
            SvipeApi.post("/v1/stt/vote", body, token, (res, code, err) -> done.run(code == 200));
        });
    }

    /**
     * Every version of this voice note's text, as the server has them; {@code null} when it could not
     * be had. Also refreshes my fix and the approved one, and redraws the message if they moved.
     */
    public static void variants(MessageObject messageObject, Utilities.Callback<JSONObject> done) {
        final TLRPC.Document doc = messageObject.getDocument();
        if (doc == null) {
            done.run(null);
            return;
        }
        final long docId = doc.id;
        SvipeAuth.ensureToken(messageObject.currentAccount, token -> {
            if (token == null) {
                AndroidUtilities.runOnUIThread(() -> done.run(null));
                return;
            }
            SvipeApi.get("/v1/stt/variants?doc_id=" + docId, token, (res, code, err) -> {
                if (res == null || code != 200) {
                    FileLog.d("svipe-stt: variants doc " + docId + " -> " + code);
                    done.run(null);
                    return;
                }
                final org.json.JSONArray list = res.optJSONArray("variants");
                int others = 0;
                if (list != null) {
                    for (int i = 0; i < list.length(); i++) {
                        final JSONObject v = list.optJSONObject(i);
                        if (v != null && !v.optBoolean("mine", false)) {
                            others++;
                        }
                    }
                }
                variantCounts.put(docId, others);
                final JSONObject mine = res.optJSONObject("mine");
                final JSONObject best = res.optJSONObject("best");
                setLayers(messageObject, mine == null ? null : str(mine, "text"), best == null ? null : str(best, "text"));
                applyShown(messageObject);
                done.run(res);
            });
        });
    }

    /** A text one engine made for one message, with Telegram's transcription id (0 for ours). */
    public static final class Kept {
        public final long id;
        public final String text;
        /** For ours: the server's engine setup when it was made; empty for Telegram's. */
        final String generation;

        Kept(long id, String generation, String text) {
            this.id = id;
            this.generation = generation;
            this.text = text;
        }
    }

    private static String generation() {
        return prefs().getString(KEY_GENERATION, "");
    }

    /** Our text for this message was made by an older engine setup (or we never kept it): ask again. */
    public static boolean oursIsStale(MessageObject messageObject) {
        final String now = generation();
        if (TextUtils.isEmpty(now)) {
            return false;
        }
        final Kept k = kept(messageObject, true);
        return k == null || !now.equals(k.generation);
    }

    private static final String TEXTS = "svipe_stt_texts";
    /** Past this many kept texts the store starts over; each engine can make its text again. */
    private static final int TEXTS_MAX = 2000;

    private static SharedPreferences texts() {
        return ApplicationLoader.applicationContext.getSharedPreferences(TEXTS, Context.MODE_PRIVATE);
    }

    private static String textKey(MessageObject messageObject, boolean ours) {
        return messageObject.currentAccount + "_" + messageObject.getDialogId() + "_" + messageObject.getId() + (ours ? "_S" : "_A");
    }

    /**
     * Keeps both engines' texts side by side: the message itself holds only one, and switching S ⇄ A
     * must show each engine's own text, not the one made last.
     */
    public static void keep(MessageObject messageObject, boolean ours, long id, String text) {
        if (messageObject == null || text == null) {
            return;
        }
        final SharedPreferences p = texts();
        final SharedPreferences.Editor e = p.edit();
        if (p.getAll().size() >= TEXTS_MAX) {
            e.clear();
        }
        e.putString(textKey(messageObject, ours), id + "\n" + (ours ? generation() : "") + "\n" + text).apply();
    }

    public static Kept kept(MessageObject messageObject, boolean ours) {
        final String v = texts().getString(textKey(messageObject, ours), null);
        final int a = v == null ? -1 : v.indexOf('\n');
        final int b = a < 0 ? -1 : v.indexOf('\n', a + 1);
        if (b < 0) {
            return null;
        }
        try {
            return new Kept(Long.parseLong(v.substring(0, a)), v.substring(a + 1, b), v.substring(b + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static File localFile(MessageObject messageObject) {
        final TLRPC.Message owner = messageObject.messageOwner;
        if (owner != null && !TextUtils.isEmpty(owner.attachPath)) {
            final File sent = new File(owner.attachPath);
            if (sent.isFile() && sent.length() > 0) {
                return sent;
            }
        }
        final File cached = FileLoader.getInstance(messageObject.currentAccount).getPathToMessage(owner);
        return cached != null && cached.isFile() && cached.length() > 0 ? cached : null;
    }
}
