package org.telegram.svipe;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.os.Build;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Is the Android package in this message dangerous to open?
 *
 * <p>Scope is one thing and stays one thing: <b>APKs that arrive as chat documents</b>. Nothing here
 * looks at what is installed on the phone — no package list is read, no permission is asked for, and
 * {@code QUERY_ALL_PACKAGES} is deliberately not in our manifest. The only files this class ever
 * touches are files somebody sent.
 *
 * <p>Three outcomes, and the asymmetry between them is the whole design:
 *
 * <ul>
 *   <li><b>{@link #MALICIOUS}</b> — this exact file is known malware. The message is replaced by a
 *       warning and the file cannot be downloaded or opened from it at all.</li>
 *   <li><b>{@link #UNKNOWN} / {@link #SUSPICIOUS}</b> — nobody can vouch for it, or its manifest asks
 *       for a combination with no innocent explanation. The file stays reachable; the message carries
 *       a warning line, and opening it asks first. An APK from a chat is genuinely this risky, so
 *       this is the honest default and not a hedge.</li>
 *   <li><b>{@link #CLEAN}</b> — positive evidence of safety. The message is left completely alone; we
 *       never draw a "this is safe" badge, because a badge that appears on almost nothing teaches
 *       people that its absence means nothing.</li>
 * </ul>
 *
 * <h3>Why the answer arrives before the download</h3>
 *
 * A phone can see a Telegram {@code document.id} the moment a message arrives, and the sha256 of the
 * bytes only after downloading them. A warning that needs the download has already lost. So the
 * lookup is keyed on the document id, which the backend maps to a content hash the first time ANY
 * device downloads that file — and from then on every other device is answered before it spends a
 * byte. One person's download protects everybody who is sent the same file.
 *
 * <h3>What this device contributes</h3>
 *
 * After a download finishes, {@link #onFileLoaded} hashes the file and reads its manifest with
 * {@link PackageManager#getPackageArchiveInfo} — which parses an APK <i>file</i>, installs nothing,
 * and needs no permission — then reports both. That reading is the only layer that can say anything
 * about a file nobody in the world has seen before, which is exactly the file that matters.
 */
public final class SvipeApkGuard {

    private SvipeApkGuard() {}

    /** Asked, and nothing is known. The client shows a caution — see the class docs. */
    public static final int UNKNOWN = 0;
    /** Positive evidence of safety. The message is not touched. */
    public static final int CLEAN = 1;
    /** Our own reading of the manifest found a combination with no innocent explanation. */
    public static final int SUSPICIOUS = 2;
    /** A malware corpus holds this exact file. The message is replaced. */
    public static final int MALICIOUS = 3;

    private static final String PREFS = "svipe_apk_guard";
    private static final String KEY_VERDICTS = "verdicts";
    private static final String KEY_REPORTED = "reported";

    /** Verdicts kept on disk. Small rows; this is a couple of hundred documents at most. */
    private static final int MAX_CACHED = 400;
    /** Documents whose hash this device has already sent. One report per file is enough. */
    private static final int MAX_REPORTED = 200;
    /** A lookup batch is collected for this long so one screenful becomes one request. */
    private static final long BATCH_DELAY_MS = 250;
    /** The server takes 50; anything past that waits for the next flush. */
    private static final int MAX_BATCH = 50;
    /**
     * Files past this are not hashed. Reading half a gigabyte to learn something optional is not a
     * trade worth making, and an APK this large is not what the feature is about.
     */
    private static final long MAX_HASH_BYTES = 512L * 1024 * 1024;
    /**
     * Past this the sample is not uploaded. Hashing a large file costs this device a disk read;
     * sending it costs somebody's bandwidth twice over, and the server has its own ceiling anyway.
     */
    private static final long MAX_UPLOAD_BYTES = 200L * 1024 * 1024;

    /** doc_id -> verdict. Read on the UI thread from cell binding, so it must never block. */
    private static final LinkedHashMap<Long, Integer> verdicts = new LinkedHashMap<>();
    /** doc_ids the server told us it already has a hash for, plus the ones we reported ourselves. */
    private static final LinkedHashSet<Long> reported = new LinkedHashSet<>();
    /** doc_ids waiting to be asked about, with the little we know about them before a download. */
    private static final LinkedHashMap<Long, TLRPC.Document> pending = new LinkedHashMap<>();
    /**
     * Documents re-checked during THIS run of the app. Deliberately not persisted: one refresh per
     * launch is the right cadence for a verdict that changes hours later, and it costs one batched
     * request for the handful of APKs a person actually has in their chats.
     */
    private static final java.util.Set<Long> refreshed =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private static volatile boolean loaded;
    private static boolean flushScheduled;
    private static boolean flushing;

    /** Hashing and manifest parsing, off every thread anybody is waiting on. */
    private static volatile DispatchQueue queue;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static DispatchQueue queue() {
        if (queue == null) {
            synchronized (SvipeApkGuard.class) {
                if (queue == null) {
                    queue = new DispatchQueue("svipeApkGuard");
                }
            }
        }
        return queue;
    }

    /* ------------------------------------------------------------------ *
     * What a message is
     * ------------------------------------------------------------------ */

    /** True when this document is an Android package — by mime type or by name, either is enough. */
    public static boolean isApk(TLRPC.Document doc) {
        if (doc == null) {
            return false;
        }
        if ("application/vnd.android.package-archive".equalsIgnoreCase(doc.mime_type)) {
            return true;
        }
        final String name = FileLoader.getDocumentFileName(doc);
        if (TextUtils.isEmpty(name)) {
            return false;
        }
        final String lower = name.toLowerCase(Locale.ROOT);
        // .apks / .xapk are split-package bundles: the same thing in a zip, installed the same way.
        return lower.endsWith(".apk") || lower.endsWith(".apks") || lower.endsWith(".xapk");
    }

    /** True when this message carries an APK document. Cheap enough for cell binding. */
    public static boolean isApkMessage(MessageObject messageObject) {
        return messageObject != null && !messageObject.isSecretMedia()
                && isApk(messageObject.getDocument());
    }

    /* ------------------------------------------------------------------ *
     * The verdict
     * ------------------------------------------------------------------ */

    /**
     * The verdict for this message, from cache. Never blocks and never waits on the network.
     *
     * <p>An unseen document is queued for a lookup and answered {@link #UNKNOWN} in the meantime,
     * which is the safe way round: the user sees the caution immediately and it is either confirmed
     * or lifted a moment later. The opposite — silence until the server answers — would leave the
     * riskiest moment, the first seconds after a file arrives, completely unguarded.
     */
    public static int verdict(MessageObject messageObject) {
        if (!isApkMessage(messageObject)) {
            return CLEAN;
        }
        final TLRPC.Document doc = messageObject.getDocument();
        load();
        Integer cached;
        synchronized (verdicts) {
            cached = verdicts.get(doc.id);
        }
        if (cached == null) {
            enqueue(messageObject.currentAccount, doc);
            return UNKNOWN;
        }
        // A cached answer is not a final one. Most files are `unknown` the first time anybody sees
        // them and become something else later — when the first device to download one reports its
        // manifest, or when an antivirus finishes with the sample hours afterwards. Caching that
        // first `unknown` forever would mean the phone that received the file EARLIEST, the one
        // most at risk, is the one never told what it turned out to be.
        //
        // So every non-final verdict is re-asked once per app run: the cached value is returned
        // immediately (no wait, no flicker) and the answer, if it changed, arrives moments later
        // through svipeApkVerdictUpdated. `malicious` is not re-asked — nothing it could change to
        // would make the file safe to open.
        if (cached != MALICIOUS && refreshed.add(doc.id)) {
            enqueue(messageObject.currentAccount, doc);
        }
        return cached;
    }

    /** True when the message must be replaced by a warning instead of being drawn as a file. */
    public static boolean isBlocked(MessageObject messageObject) {
        return verdict(messageObject) == MALICIOUS;
    }

    /** True when the file stays reachable but the message has to carry a warning line. */
    public static boolean needsCaution(MessageObject messageObject) {
        final int v = verdict(messageObject);
        return v == UNKNOWN || v == SUSPICIOUS;
    }

    /* ------------------------------------------------------------------ *
     * Lookups
     * ------------------------------------------------------------------ */

    private static void enqueue(int account, TLRPC.Document doc) {
        synchronized (pending) {
            if (pending.containsKey(doc.id)) {
                return;
            }
            pending.put(doc.id, doc);
        }
        AndroidUtilities.runOnUIThread(() -> scheduleFlush(account));
    }

    private static void scheduleFlush(int account) {
        if (flushScheduled || flushing) {
            return;
        }
        flushScheduled = true;
        AndroidUtilities.runOnUIThread(() -> {
            flushScheduled = false;
            flush(account);
        }, BATCH_DELAY_MS);
    }

    private static void flush(int account) {
        final List<TLRPC.Document> batch = new ArrayList<>();
        synchronized (pending) {
            for (Map.Entry<Long, TLRPC.Document> e : pending.entrySet()) {
                batch.add(e.getValue());
                if (batch.size() >= MAX_BATCH) {
                    break;
                }
            }
            for (TLRPC.Document d : batch) {
                pending.remove(d.id);
            }
        }
        if (batch.isEmpty()) {
            return;
        }
        flushing = true;
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                flushing = false;
                // No token: put them back, so a later screen re-asks rather than being told nothing
                // forever. The caution stays up in the meantime, which is the correct thing to show.
                synchronized (pending) {
                    for (TLRPC.Document d : batch) {
                        pending.put(d.id, d);
                    }
                }
                return;
            }
            lookupRequest(account, token, batch, false);
        });
    }

    private static void lookupRequest(int account, String token, List<TLRPC.Document> batch,
                                      boolean retried) {
        final JSONObject body = new JSONObject();
        try {
            final JSONArray items = new JSONArray();
            for (TLRPC.Document d : batch) {
                final JSONObject o = new JSONObject();
                o.put("doc_id", d.id);
                if (d.size > 0) {
                    o.put("size", d.size);
                }
                final String name = FileLoader.getDocumentFileName(d);
                if (!TextUtils.isEmpty(name)) {
                    o.put("file_name", name);
                }
                items.put(o);
            }
            body.put("items", items);
        } catch (Exception e) {
            flushing = false;
            FileLog.e(e);
            return;
        }

        SvipeApi.post("/v1/apk/lookup", body, token, (res, code, err) -> {
            if (code == 401 && !retried) {
                SvipeAuth.invalidateAccessToken(account);
                SvipeAuth.ensureToken(account, t2 -> {
                    if (t2 == null) {
                        flushing = false;
                        return;
                    }
                    lookupRequest(account, t2, batch, true);
                });
                return;
            }
            flushing = false;
            if (res == null) {
                return;
            }
            final JSONArray arr = res.optJSONArray("verdicts");
            if (arr == null) {
                return;
            }
            boolean changed = false;
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                final long docId = o.optLong("doc_id");
                if (docId == 0) {
                    continue;
                }
                changed |= put(docId, parseVerdict(o.optString("verdict", "unknown")));
                if (o.optBoolean("hashed")) {
                    // Somebody else has already taught the server what this file is; this device's
                    // download has nothing left to contribute.
                    markReported(docId, false);
                }
            }
            persist();  // one write for the batch — the reported set may have grown even if no verdict did
            if (changed) {
                AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance()
                        .postNotificationName(NotificationCenter.svipeApkVerdictUpdated));
            }
            // Whatever did not fit in this batch goes out on the next one.
            if (!pending.isEmpty()) {
                AndroidUtilities.runOnUIThread(() -> scheduleFlush(account));
            }
        });
    }

    private static int parseVerdict(String s) {
        if ("malicious".equals(s)) {
            return MALICIOUS;
        }
        if ("suspicious".equals(s)) {
            return SUSPICIOUS;
        }
        if ("clean".equals(s)) {
            return CLEAN;
        }
        return UNKNOWN;
    }

    /**
     * Re-derive one message after its verdict changed. Returns true when the message is an APK.
     *
     * <p>A verdict usually arrives a moment AFTER the message was drawn — that is the price of not
     * making the user wait for the network — so the list has to be told to think again. Only the
     * blocked case needs the text rebuilt: the caution line is produced at bind time in
     * {@code createDocumentLayout}, so for that a redraw is enough.
     */
    public static boolean refresh(MessageObject messageObject) {
        if (!isApkMessage(messageObject)) {
            return false;
        }
        messageObject.updateMessageText();
        messageObject.setType();
        if (messageObject.isRestrictedMessage) {
            // Now a text bubble, and a text bubble with no layout blocks draws as an empty one.
            messageObject.applyNewText(messageObject.messageText);
        }
        return true;
    }

    /* ------------------------------------------------------------------ *
     * The last gate, in front of the installer
     * ------------------------------------------------------------------ */

    /**
     * Stand in front of "open this file" for an APK. Returns true when this class handled it and the
     * caller must not open anything.
     *
     * <p>The in-message warning says what is known; this is where the user has to answer for it. The
     * short line in the bubble has to fit on one line, so the sentence that actually explains the
     * risk — and the app's real name and package, which are the two things a repackaged trojan gets
     * wrong — lives here, at the moment it matters: the tap before the installer opens.
     *
     * <p>A blocked file gets no way through at all. Not a scarier confirmation: none.
     */
    public static boolean interceptOpen(MessageObject messageObject, android.app.Activity activity,
                                        org.telegram.ui.ActionBar.Theme.ResourcesProvider resourcesProvider,
                                        Runnable proceed) {
        if (activity == null || !isApkMessage(messageObject)) {
            return false;
        }
        final int v = verdict(messageObject);
        if (v == CLEAN) {
            return false;
        }

        final org.telegram.ui.ActionBar.AlertDialog.Builder b =
                new org.telegram.ui.ActionBar.AlertDialog.Builder(activity, resourcesProvider);
        if (v == MALICIOUS) {
            b.setTitle(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.SvipeApkBlockedTitle));
            b.setMessage(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.SvipeApkBlockedDetail));
            b.setPositiveButton(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.OK), null);
            b.show();
            return true;
        }

        b.setTitle(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.SvipeApkWarnTitle));
        b.setMessage(org.telegram.messenger.LocaleController.getString(
                v == SUSPICIOUS ? org.telegram.messenger.R.string.SvipeApkSuspiciousDetail
                        : org.telegram.messenger.R.string.SvipeApkUnknownDetail));
        b.setNegativeButton(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.Cancel), null);
        b.setPositiveButton(
                org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.SvipeApkInstallAnyway),
                (dialog, which) -> {
                    if (proceed != null) {
                        proceed.run();
                    }
                });
        final org.telegram.ui.ActionBar.AlertDialog dialog = b.create();
        dialog.show();
        // The way through is red. It is a real choice — the user may well know the sender — but it
        // should never be the button the thumb reaches for without reading.
        final android.view.View ok = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
        if (ok instanceof android.widget.TextView) {
            ((android.widget.TextView) ok).setTextColor(
                    org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_text_RedBold, resourcesProvider));
        }
        return true;
    }

    /* ------------------------------------------------------------------ *
     * What this device learns from a download
     * ------------------------------------------------------------------ */

    /**
     * A file finished downloading. If it is an APK, hash it and read its manifest, then send both.
     *
     * <p>Called from {@code FileLoader}'s completion path for every file the app downloads, so the
     * first thing it does is decide this is none of its business. The work itself runs on this
     * class's own queue: hashing a large file is real I/O and must not sit on a shared thread.
     */
    public static void onFileLoaded(int account, TLRPC.Document doc, File file,
                                    MessageObject messageObject) {
        if (doc == null || file == null || !isApk(doc)) {
            return;
        }
        load();
        synchronized (reported) {
            if (reported.contains(doc.id)) {
                return;
            }
        }
        if (file.length() <= 0 || file.length() > MAX_HASH_BYTES) {
            return;
        }
        final String path = file.getAbsolutePath();
        final long docId = doc.id;
        final long size = file.length();
        // The story the file was told with. A trojan spreads because somebody is told something — a
        // court summons, a delivery slip — and the same file keeps arriving under the same few
        // sentences, which names a campaign long before any engine has a signature for the binary.
        final String text = messageText(messageObject);
        queue().postRunnable(() -> {
            final String sha = sha256(path);
            if (sha == null) {
                return;
            }
            final Manifest manifest = readManifest(path);
            AndroidUtilities.runOnUIThread(() ->
                    sendReport(account, docId, sha, size, manifest, text, path));
        });
    }

    /** The caption or message body that carried the file, trimmed to what the server will store. */
    private static String messageText(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return null;
        }
        final CharSequence caption = messageObject.caption;
        String out = caption != null ? caption.toString() : messageObject.messageOwner.message;
        if (out == null) {
            return null;
        }
        out = out.trim();
        if (out.isEmpty()) {
            return null;
        }
        return out.length() > 4000 ? out.substring(0, 4000) : out;
    }

    private static void sendReport(int account, long docId, String sha, long size, Manifest m,
                                   String text, String path) {
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                return;
            }
            reportRequest(account, token, docId, sha, size, m, text, path, false);
        });
    }

    private static void reportRequest(int account, String token, long docId, String sha, long size,
                                      Manifest m, String text, String path, boolean retried) {
        final JSONObject body = new JSONObject();
        try {
            body.put("doc_id", docId);
            body.put("sha256", sha);
            body.put("size", size);
            if (m != null) {
                if (m.packageName != null) body.put("package_name", m.packageName);
                if (m.label != null) body.put("label", m.label);
                if (m.versionCode > 0) body.put("version_code", m.versionCode);
                if (m.versionName != null) body.put("version_name", m.versionName);
                if (m.signerSha256 != null) body.put("signer_sha256", m.signerSha256);
                body.put("permissions", new JSONArray(m.permissions));
                body.put("flags", new JSONArray(m.flags));
            } else {
                body.put("flags", new JSONArray(java.util.Collections.singletonList("parse_failed")));
            }
            if (text != null) {
                body.put("message_text", text);
            }
        } catch (Exception e) {
            FileLog.e(e);
            return;
        }

        SvipeApi.post("/v1/apk/report", body, token, (res, code, err) -> {
            if (code == 401 && !retried) {
                SvipeAuth.invalidateAccessToken(account);
                SvipeAuth.ensureToken(account, t2 -> {
                    if (t2 != null) {
                        reportRequest(account, t2, docId, sha, size, m, text, path, true);
                    }
                });
                return;
            }
            if (res == null) {
                return;
            }
            markReported(docId, true);
            // The reply carries the verdict this very report produced — including the case that
            // matters most, where the manifest this device just read is what condemned the file.
            if (put(docId, parseVerdict(res.optString("verdict", "unknown")))) {
                persist();
                AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance()
                        .postNotificationName(NotificationCenter.svipeApkVerdictUpdated));
            }
            if (res.optBoolean("want_upload")) {
                uploadSample(account, token, docId, sha, path);
            }
        });
    }

    /**
     * Hand the APK's bytes to our own backend, so a third-party antivirus can look at the file
     * itself rather than at a hash of it.
     *
     * <p>Only ever runs when the server has just said it wants this sample, and it says that to
     * <b>exactly one device per file</b>. That matters more than it sounds: an infected account
     * forwards the same package to every contact and every group it can reach, so the same bytes
     * reach us as hundreds of distinct Telegram documents. The server keys its store on the content
     * hash, so the first holder uploads and everybody after is told there is nothing to send.
     *
     * <p>The upload goes to <b>our</b> server and nowhere else. The phone holds no antivirus
     * credential, makes no call to any third party, and never learns which service answered.
     *
     * <p>Deliberately unmetered-only and deliberately silent. This is the lowest-priority thing the
     * app does — a hundred megabytes is not something to spend somebody's mobile data on for a file
     * they have already downloaded and may never open, and a failure simply means the next device
     * that gets this file will be asked instead.
     */
    private static void uploadSample(int account, String token, long docId, String sha, String path) {
        if (path == null) {
            return;
        }
        final File file = new File(path);
        if (!file.isFile() || file.length() <= 0 || file.length() > MAX_UPLOAD_BYTES) {
            return;
        }
        if (!isUnmetered()) {
            // Not now. The server keeps asking until somebody answers, and somebody on wifi will.
            return;
        }
        SvipeApi.postFile("/v1/apk/upload?sha256=" + sha + "&doc_id=" + docId, file,
                "application/vnd.android.package-archive", token,
                (res, code, err) -> {
                    if (res != null && res.optBoolean("stored")) {
                        FileLog.d("svipe-apk: sample " + sha.substring(0, 12) + " uploaded for scanning");
                    }
                });
    }

    /** True on wifi (or any connection the system does not consider metered). */
    private static boolean isUnmetered() {
        try {
            final android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            final android.net.Network network = cm.getActiveNetwork();
            final android.net.NetworkCapabilities caps =
                    network == null ? null : cm.getNetworkCapabilities(network);
            return caps != null
                    && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        } catch (Throwable t) {
            return false;
        }
    }

    /* ------------------------------------------------------------------ *
     * Reading an APK without installing it
     * ------------------------------------------------------------------ */

    /** What one APK file turned out to declare. Everything is optional — a broken APK declares little. */
    public static final class Manifest {
        public String packageName;
        public String label;
        public long versionCode;
        public String versionName;
        public String signerSha256;
        public final List<String> permissions = new ArrayList<>();
        /** Findings that are not permissions: a service declaration, a missing icon, a bad parse. */
        public final Set<String> flags = new TreeSet<>();
    }

    /**
     * Parse the downloaded APK. Never installs, never runs anything, and needs no permission —
     * {@link PackageManager#getPackageArchiveInfo} reads the file as data.
     *
     * <p>Returns {@code null} when the package cannot be parsed at all, which the caller reports as
     * {@code parse_failed}: an APK Android itself cannot read is either corrupt or deliberately
     * malformed to defeat exactly this inspection, and neither is a reason to install it.
     */
    public static Manifest readManifest(String path) {
        final Context ctx = ApplicationLoader.applicationContext;
        final PackageManager pm = ctx.getPackageManager();
        int flags = PackageManager.GET_PERMISSIONS | PackageManager.GET_SERVICES
                | PackageManager.GET_RECEIVERS | PackageManager.GET_ACTIVITIES;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            flags |= PackageManager.GET_SIGNING_CERTIFICATES;
        } else {
            flags |= PackageManager.GET_SIGNATURES;
        }
        PackageInfo info;
        try {
            info = pm.getPackageArchiveInfo(path, flags);
        } catch (Throwable t) {
            // A malformed package can throw out of the parser rather than returning null.
            FileLog.e(t);
            return null;
        }
        if (info == null) {
            return null;
        }

        final Manifest m = new Manifest();
        m.packageName = info.packageName;
        m.versionName = info.versionName;
        m.versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? info.getLongVersionCode() : info.versionCode;

        final ApplicationInfo app = info.applicationInfo;
        if (app != null) {
            // The label lives in the archive's resources, and the loader will only find them if it is
            // told where the archive is — an ApplicationInfo from getPackageArchiveInfo has both
            // paths empty, and without this the app's own name is unreadable.
            app.sourceDir = path;
            app.publicSourceDir = path;
            try {
                final CharSequence label = pm.getApplicationLabel(app);
                if (label != null) {
                    m.label = label.toString();
                }
            } catch (Throwable ignore) {
            }
        }

        if (info.requestedPermissions != null) {
            for (String p : info.requestedPermissions) {
                if (!TextUtils.isEmpty(p)) {
                    m.permissions.add(p);
                }
            }
        }

        // A service is an accessibility / notification-listener service because the SYSTEM requires
        // that permission to bind it — the app declares it on the service, not in its permission
        // list, so it is invisible to the loop above.
        if (info.services != null) {
            for (ServiceInfo s : info.services) {
                if (s == null || s.permission == null) {
                    continue;
                }
                if ("android.permission.BIND_ACCESSIBILITY_SERVICE".equals(s.permission)) {
                    m.flags.add("accessibility_service");
                } else if ("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE".equals(s.permission)) {
                    m.flags.add("notification_listener");
                }
            }
        }
        if (info.receivers != null) {
            for (android.content.pm.ActivityInfo r : info.receivers) {
                if (r != null && "android.permission.BIND_DEVICE_ADMIN".equals(r.permission)) {
                    m.flags.add("device_admin");
                }
            }
        }
        // No activity at all means nothing the user can start: legitimate for a plugin, and the
        // shape of something that would rather not be found again after it is installed.
        if (info.activities == null || info.activities.length == 0) {
            m.flags.add("no_launcher");
        }

        m.signerSha256 = signerSha256(info);
        if (m.signerSha256 == null) {
            m.flags.add("unsigned");
        }
        return m;
    }

    private static String signerSha256(PackageInfo info) {
        try {
            Signature[] sigs = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
                sigs = info.signingInfo.hasMultipleSigners()
                        ? info.signingInfo.getApkContentsSigners()
                        : info.signingInfo.getSigningCertificateHistory();
            }
            if (sigs == null || sigs.length == 0) {
                sigs = info.signatures;
            }
            if (sigs == null || sigs.length == 0 || sigs[0] == null) {
                return null;
            }
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            return toHex(md.digest(sigs[0].toByteArray()));
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    private static String sha256(String path) {
        try (FileInputStream in = new FileInputStream(path)) {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            return toHex(md.digest());
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    private static String toHex(byte[] bytes) {
        final StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /* ------------------------------------------------------------------ *
     * Cache
     * ------------------------------------------------------------------ */

    private static boolean put(long docId, int verdict) {
        synchronized (verdicts) {
            final Integer old = verdicts.get(docId);
            if (old != null && old == verdict) {
                return false;
            }
            verdicts.remove(docId);          // re-insert so the map stays in recency order
            verdicts.put(docId, verdict);
            while (verdicts.size() > MAX_CACHED) {
                final Long oldest = verdicts.keySet().iterator().next();
                verdicts.remove(oldest);
            }
            return true;
        }
    }

    private static void markReported(long docId, boolean persistNow) {
        synchronized (reported) {
            if (!reported.add(docId)) {
                return;
            }
            while (reported.size() > MAX_REPORTED) {
                final Long oldest = reported.iterator().next();
                reported.remove(oldest);
            }
        }
        if (persistNow) {
            persist();
        }
    }

    private static void load() {
        if (loaded) {
            return;
        }
        synchronized (SvipeApkGuard.class) {
            if (loaded) {
                return;
            }
            try {
                final SharedPreferences p = prefs();
                final String raw = p.getString(KEY_VERDICTS, null);
                if (raw != null) {
                    final JSONObject o = new JSONObject(raw);
                    final java.util.Iterator<String> it = o.keys();
                    synchronized (verdicts) {
                        while (it.hasNext()) {
                            final String k = it.next();
                            verdicts.put(Long.parseLong(k), o.optInt(k, UNKNOWN));
                        }
                    }
                }
                final String rawReported = p.getString(KEY_REPORTED, null);
                if (rawReported != null) {
                    final JSONArray arr = new JSONArray(rawReported);
                    synchronized (reported) {
                        for (int i = 0; i < arr.length(); i++) {
                            reported.add(arr.optLong(i));
                        }
                    }
                }
            } catch (Exception ignore) {
            }
            loaded = true;
        }
    }

    private static void persist() {
        try {
            final JSONObject o = new JSONObject();
            synchronized (verdicts) {
                for (Map.Entry<Long, Integer> e : verdicts.entrySet()) {
                    o.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
            final JSONArray arr = new JSONArray();
            synchronized (reported) {
                for (Long id : reported) {
                    arr.put((long) id);
                }
            }
            prefs().edit().putString(KEY_VERDICTS, o.toString())
                    .putString(KEY_REPORTED, arr.toString()).apply();
        } catch (Exception ignore) {
        }
    }
}
