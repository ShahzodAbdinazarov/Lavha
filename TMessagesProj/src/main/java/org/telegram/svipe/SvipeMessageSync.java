package org.telegram.svipe;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client control plane for the P2P message-sync archive (docs/svipe-message-sync-plan.md). Phase 4:
 * discover whether a chat partner is a sync-enabled Svipe user, and read/write MY global sync mode.
 * Uploading a chat's deleted/edited messages and reading a pair archive back come in later phases.
 *
 * <p><b>The peer probe is k-anonymous.</b> Telegram user ids are a small, enumerable space, so asking
 * the server "is id X a Svipe user?" outright would let anyone walk the whole user base. Instead the
 * client reveals only a short prefix of {@code sha256(peerId)}; the server returns the hashes of
 * sync-enabled users sharing that prefix, and the client matches locally. The server never learns which
 * peer was asked about — only the bucket.
 *
 * <p>The mode is a per-user server setting, cached locally only for instant display: it governs uploads
 * from THIS device but also what OTHER participants may receive, so the server is the source of truth.
 */
public class SvipeMessageSync {

    // Server-side modes (app/db/msg_sync_repo.py MODES).
    public static final String MODE_WITH_PARTNER = "with_partner";  // reciprocal: both see each other's deletions
    public static final String MODE_SELF_ONLY = "self_only";        // back up only my own messages
    public static final String MODE_OFF = "off";                    // sync nothing (and, reciprocally, receive nothing)

    /**
     * Hex chars of sha256(tg_id) revealed per peer. MUST equal the backend's msg_sync_kanon_prefix_len
     * (app/config.py, default 4): the server requires the bucket to be exactly that many chars.
     */
    static final int KANON_PREFIX_LEN = 4;

    // Cached peer verdicts for this session, so opening the same chat twice does not re-probe.
    private static final ConcurrentHashMap<Long, Boolean> peerIsSvipe = new ConcurrentHashMap<>();
    // Once the server says the whole feature is off, stop asking for the rest of the session.
    private static volatile boolean serverDisabled;

    // ---- pure logic (JVM-testable, no Android) ----

    /** SHA-256 of the decimal id string, lowercase hex — identical to the server's user_hash(tg_id). */
    static String userHash(long tgId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(Long.toString(tgId).getBytes(StandardCharsets.US_ASCII));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /** The prefix a client reveals for a peer — {@code prefixLen} hex chars of {@link #userHash}. */
    static String kanonBucket(long tgId, int prefixLen) {
        String h = userHash(tgId);
        if (h == null || prefixLen <= 0 || prefixLen > h.length()) {
            return h;
        }
        return h.substring(0, prefixLen);
    }

    /** Is the peer among the enabled-user hashes the server returned for its bucket? */
    static boolean matchesPeer(long peerTgId, Set<String> returnedHashes) {
        if (returnedHashes == null || returnedHashes.isEmpty()) {
            return false;
        }
        String h = userHash(peerTgId);
        return h != null && returnedHashes.contains(h);
    }

    // ---- Android glue ----

    public interface PeerCallback {
        void run(boolean isSvipeSyncUser);
    }

    public interface ModeCallback {
        /** {@code mode} is null on failure, "" (or a MODE_* value) otherwise; {@code deleted} is how many
         *  of my items the server dropped (when turning off / erasing). */
        void run(String mode, int deleted, boolean ok);
    }

    /** Cached result of a previous probe this session, or null if not yet known. */
    public static Boolean cachedPeerVerdict(long peerTgId) {
        return peerIsSvipe.get(peerTgId);
    }

    /**
     * Ask whether {@code peerTgId} is a sync-enabled Svipe user, k-anonymously. The verdict is cached
     * for the session and delivered on whatever thread SvipeApi calls back on.
     */
    public static void checkPeer(int account, long peerTgId, PeerCallback cb) {
        if (peerTgId <= 0 || serverDisabled) {
            cb.run(false);
            return;
        }
        Boolean cached = peerIsSvipe.get(peerTgId);
        if (cached != null) {
            cb.run(cached);
            return;
        }
        final String bucket = kanonBucket(peerTgId, KANON_PREFIX_LEN);
        if (bucket == null) {
            cb.run(false);
            return;
        }
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                cb.run(false);
                return;
            }
            JSONObject body = new JSONObject();
            try {
                body.put("buckets", new JSONArray().put(bucket));
            } catch (Exception e) {
                FileLog.e(e);
                cb.run(false);
                return;
            }
            SvipeApi.post("/v1/msg-sync/peers/check", body, token, (res, code, err) -> {
                if (code == 503) {
                    serverDisabled = true;
                    cb.run(false);
                    return;
                }
                if (res == null || code < 200 || code >= 300) {
                    cb.run(false);
                    return;
                }
                Set<String> hashes = new HashSet<>();
                JSONArray arr = res.optJSONArray("hashes");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        String h = arr.optString(i, null);
                        if (h != null) {
                            hashes.add(h);
                        }
                    }
                }
                boolean isPeer = matchesPeer(peerTgId, hashes);
                peerIsSvipe.put(peerTgId, isPeer);
                cb.run(isPeer);
            });
        });
    }

    /** My current global mode as the server holds it (source of truth; null = not decided yet). */
    public static void loadMyMode(int account, ModeCallback cb) {
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                cb.run(null, 0, false);
                return;
            }
            SvipeApi.get("/v1/msg-sync/me/mode", token, (res, code, err) -> {
                if (res == null || code < 200 || code >= 300) {
                    cb.run(null, 0, false);
                    return;
                }
                cb.run(res.optString("mode", ""), 0, true);
            });
        });
    }

    /** Record my choice. "off" also withdraws my own already-synced items server-side. */
    public static void setMyMode(int account, String mode, ModeCallback cb) {
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                cb.run(null, 0, false);
                return;
            }
            JSONObject body = new JSONObject();
            try {
                body.put("mode", mode);
            } catch (Exception e) {
                FileLog.e(e);
                cb.run(null, 0, false);
                return;
            }
            SvipeApi.put("/v1/msg-sync/me/mode", body, token, (res, code, err) -> {
                if (code == 503) {
                    serverDisabled = true;
                }
                boolean ok = res != null && code >= 200 && code < 300;
                cb.run(ok ? res.optString("mode", mode) : null, ok ? res.optInt("deleted") : 0, ok);
            });
        });
    }

    /** Erase everything I contributed (my authored items + my own backup) and turn sync off. */
    public static void deleteMyArchive(int account, ModeCallback cb) {
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                cb.run(null, 0, false);
                return;
            }
            SvipeApi.delete("/v1/msg-sync/me", token, (res, code, err) -> {
                boolean ok = res != null && code >= 200 && code < 300;
                cb.run(ok ? MODE_OFF : null, ok ? res.optInt("deleted") : 0, ok);
            });
        });
    }
}
