package org.telegram.svipe;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Tiny JSON HTTP client for the Svipe backend. All requests run on a background queue;
 * callbacks are delivered on the UI thread.
 */
public class SvipeApi {

    public interface JsonCallback {
        void run(JSONObject result, int httpCode, String error);
    }

    /** Result of a raw (non-JSON) transfer such as a presigned upload. */
    public interface RawCallback {
        void run(int httpCode, String error);
    }

    public static void get(String path, String bearer, JsonCallback cb) {
        request("GET", path, null, bearer, cb);
    }

    public static void post(String path, JSONObject body, String bearer, JsonCallback cb) {
        request("POST", path, body, bearer, cb);
    }

    public static void delete(String path, String bearer, JsonCallback cb) {
        request("DELETE", path, null, bearer, cb);
    }

    /**
     * PUT a file to an ABSOLUTE, already-signed URL — the presigned upload the backend hands out for
     * the avatar archive. Deliberately unlike the calls above: no base-url prefix and no bearer, since
     * the signature in the URL is the whole authorization and storage would reject a stray auth header.
     * Streams from disk with a fixed length, so a large photo never has to sit in memory.
     */
    public static void putFile(String absoluteUrl, java.io.File file, String contentType, RawCallback cb) {
        Utilities.globalQueue.postRunnable(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(absoluteUrl).openConnection();
                conn.setRequestMethod("PUT");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(file.length());
                if (contentType != null) {
                    conn.setRequestProperty("Content-Type", contentType);
                }
                java.io.FileInputStream in = new java.io.FileInputStream(file);
                java.io.OutputStream out = conn.getOutputStream();
                try {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                    out.flush();
                } finally {
                    try { in.close(); } catch (Exception ignore) {}
                    try { out.close(); } catch (Exception ignore) {}
                }
                final int code = conn.getResponseCode();
                AndroidUtilities.runOnUIThread(() -> cb.run(code, null));
            } catch (Exception e) {
                FileLog.e(e);
                final String err = e.getMessage();
                AndroidUtilities.runOnUIThread(() -> cb.run(0, err));
            } finally {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Exception ignore) {}
                }
            }
        });
    }

    private static void request(String method, String path, JSONObject body, String bearer, JsonCallback cb) {
        Utilities.globalQueue.postRunnable(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(SvipeConfig.baseUrl() + path);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod(method);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(25000);
                conn.setRequestProperty("Accept", "application/json");
                if (bearer != null) {
                    conn.setRequestProperty("Authorization", "Bearer " + bearer);
                }
                if (body != null) {
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.getOutputStream().write(body.toString().getBytes("UTF-8"));
                }
                int code = conn.getResponseCode();
                InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                String resp = readStream(is);
                JSONObject json = null;
                if (resp != null && resp.startsWith("{")) {
                    try { json = new JSONObject(resp); } catch (Exception ignore) {}
                }
                final JSONObject fjson = json;
                final int fcode = code;
                AndroidUtilities.runOnUIThread(() -> cb.run(fjson, fcode, null));
            } catch (Exception e) {
                FileLog.e(e);
                final String err = e.getMessage();
                AndroidUtilities.runOnUIThread(() -> cb.run(null, 0, err));
            } finally {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Exception ignore) {}
                }
            }
        });
    }

    private static String readStream(InputStream is) throws Exception {
        if (is == null) return null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        is.close();
        return new String(bos.toByteArray(), "UTF-8");
    }
}
