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
 * swaps them, for the whole app at once ({@link #toggleEngine}); a person with Premium who prefers
 * Telegram's can have it back.
 */
public final class SvipeStt {

    private static final String PREFS = "svipe_stt";
    private static final String KEY_ENABLED = "enabled";
    /** Device-wide, not per account: one switch, the whole app follows. */
    private static final String KEY_ENGINE_TELEGRAM = "engine_telegram";
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

    /** Swap S and A for the whole app; every chat on screen redraws its buttons. Returns the new state. */
    public static boolean toggleEngine() {
        final boolean telegram = !telegramEngine();
        prefs().edit().putBoolean(KEY_ENGINE_TELEGRAM, telegram).apply();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.svipeSttEngineChanged);
        return telegram;
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
                prefs().edit().putBoolean(KEY_ENABLED, res.optBoolean("enabled", false)).apply();
            });
        });
    }

    /**
     * The text of {@code messageObject}, or {@code null} when it could not be had — the caller then
     * falls back to Telegram. {@code done} runs on the UI thread.
     */
    public static void transcribe(MessageObject messageObject, Utilities.Callback<String> done) {
        final TLRPC.Document doc = messageObject == null ? null : messageObject.getDocument();
        if (doc == null) {
            done.run(null);
            return;
        }
        final int account = messageObject.currentAccount;
        final long docId = doc.id;
        final int duration = (int) Math.round(messageObject.getDuration());
        final String mime = messageObject.isRoundVideo() ? "video/mp4" : "audio/ogg";

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
                FileLog.d("svipe-stt: doc " + docId + " never arrived");
                AndroidUtilities.runOnUIThread(() -> done.run(null));
                return;
            }
            final File audio = f;
            SvipeAuth.ensureToken(account, token -> {
                if (token == null) {
                    AndroidUtilities.runOnUIThread(() -> done.run(null));
                    return;
                }
                SvipeApi.postFile("/v1/stt?doc_id=" + docId + "&duration=" + duration, audio, mime, token,
                        (res, code, err) -> {
                            String text = null;
                            if (res != null && code == 200) {
                                text = res.optString("text", "");
                            } else {
                                FileLog.d("svipe-stt: doc " + docId + " -> " + code);
                                if (code == 503) {
                                    // The server lost its provider; stop routing here until it says so.
                                    prefs().edit().putBoolean(KEY_ENABLED, false).apply();
                                    statusAsked = false;
                                }
                            }
                            final String result = text;
                            AndroidUtilities.runOnUIThread(() -> done.run(result));
                        });
            });
        });
    }

    /** A text one engine made for one message, with Telegram's transcription id (0 for ours). */
    public static final class Kept {
        public final long id;
        public final String text;

        Kept(long id, String text) {
            this.id = id;
            this.text = text;
        }
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
        e.putString(textKey(messageObject, ours), id + "\n" + text).apply();
    }

    public static Kept kept(MessageObject messageObject, boolean ours) {
        final String v = texts().getString(textKey(messageObject, ours), null);
        final int nl = v == null ? -1 : v.indexOf('\n');
        if (nl < 0) {
            return null;
        }
        try {
            return new Kept(Long.parseLong(v.substring(0, nl)), v.substring(nl + 1));
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
