package org.telegram.svipe;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves music track references (channel_id, message_id, username) into real TLRPC.Messages via
 * the same two MTProto round-trips the reels pipeline uses: contacts.resolveUsername (cached per
 * account) + channels.getMessages batched per channel. Callbacks arrive on the UI thread.
 */
public class SvipeMusicResolver {

    public interface Callback {
        /** resolved maps Track.key() -> real channel TLRPC.Message with a music document. */
        void onResolved(Map<String, TLRPC.Message> resolved);
    }

    // username -> resolved Chat, per account. Chats are small; the map only ever holds curated
    // music channels, so no eviction is needed.
    private static final ConcurrentHashMap<Integer, ConcurrentHashMap<String, TLRPC.Chat>> chatCache = new ConcurrentHashMap<>();

    public static void resolve(int account, List<SvipeMusic.Track> tracks, Callback cb) {
        final HashMap<String, ArrayList<SvipeMusic.Track>> byUser = new HashMap<>();
        for (SvipeMusic.Track t : tracks) {
            if (t.username == null || t.username.isEmpty()) {
                continue;
            }
            String u = t.username.toLowerCase();
            ArrayList<SvipeMusic.Track> group = byUser.get(u);
            if (group == null) {
                group = new ArrayList<>();
                byUser.put(u, group);
            }
            group.add(t);
        }
        if (byUser.isEmpty()) {
            AndroidUtilities.runOnUIThread(() -> cb.onResolved(new HashMap<>()));
            return;
        }
        final Map<String, TLRPC.Message> resolved = new ConcurrentHashMap<>();
        final int[] pending = {byUser.size()};
        for (Map.Entry<String, ArrayList<SvipeMusic.Track>> e : byUser.entrySet()) {
            resolveGroup(account, e.getKey(), e.getValue(), resolved, () -> {
                // Always invoked on the UI thread, so the countdown needs no extra sync.
                if (--pending[0] == 0) {
                    cb.onResolved(resolved);
                }
            });
        }
    }

    private static void resolveGroup(int account, String username, ArrayList<SvipeMusic.Track> group,
                                     Map<String, TLRPC.Message> resolved, Runnable done) {
        ConcurrentHashMap<String, TLRPC.Chat> cache = chatCache.get(account);
        if (cache == null) {
            cache = new ConcurrentHashMap<>();
            ConcurrentHashMap<String, TLRPC.Chat> prev = chatCache.putIfAbsent(account, cache);
            if (prev != null) {
                cache = prev;
            }
        }
        final ConcurrentHashMap<String, TLRPC.Chat> chats = cache;

        TLRPC.Chat cached = chats.get(username);
        if (cached != null) {
            fetchMessages(account, cached, group, resolved, done);
            return;
        }

        // The reference already carries the channel id, so a channel this device has ever seen is
        // addressable from its stored access_hash. Asking that first is what stops every cold start
        // re-resolving the same handles — the traffic that earns the hours-long FLOOD_WAIT which
        // leaves favourites unplayable. See SvipeChannelResolve.
        final long channelId = group.get(0).channelId;
        SvipeChannelResolve.lookup(account, channelId, local -> {
            if (local != null) {
                chats.put(username, local);
                fetchMessages(account, local, group, resolved, done);
                return;
            }
            // Nothing local. Take each track off the post's own public link rather than buying the
            // channel: messages.getWebPage answers with the audio document (verified against prod —
            // a cold link comes back empty and full on the retry, which SvipeWebRef does) and it does
            // not flood, which contacts.resolveUsername very much does. Only a link that has nothing
            // falls through to the resolve below.
            webPageGroup(account, username, channelId, group, resolved, done, chats);
        });
    }

    /** Tracks through the link-preview path — no channel, no resolveUsername. */
    private static void webPageGroup(int account, String username, long channelId,
                                     ArrayList<SvipeMusic.Track> group,
                                     Map<String, TLRPC.Message> resolved, Runnable done,
                                     ConcurrentHashMap<String, TLRPC.Chat> chats) {
        final int[] pending = {group.size()};
        final boolean[] anyMissed = {false};
        for (SvipeMusic.Track t : group) {
            final SvipeMusic.Track track = t;
            org.telegram.svipe.video.SvipeWebRef.fetch(account, username, track.messageId, channelId,
                    (mo, page) -> {
                if (mo != null && mo.messageOwner != null && mo.messageOwner.media != null
                        && mo.messageOwner.media.document != null
                        && MessageObject.isMusicDocument(mo.messageOwner.media.document)) {
                    resolved.put(track.key(), mo.messageOwner);
                    SvipeObserved.note(account, channelId, track.messageId, mo, "music");
                } else {
                    anyMissed[0] = true;
                }
                if (--pending[0] == 0) {
                    if (anyMissed[0]) {
                        // Some links had no preview at all. Those tracks are worth ONE resolve for
                        // the whole channel — and the ones already resolved above are simply
                        // re-filled, which costs nothing but a map write.
                        sendResolve(account, username, group, resolved, done, chats);
                    } else {
                        done.run();
                    }
                }
            });
        }
    }

    private static void sendResolve(int account, String username, ArrayList<SvipeMusic.Track> group,
                                    Map<String, TLRPC.Message> resolved, Runnable done,
                                    ConcurrentHashMap<String, TLRPC.Chat> chats) {
        if (SvipeChannelResolve.blocked(account) || SvipeChannelResolve.exhausted(account)) {
            // Telegram is already making this account wait. Asking anyway is not merely useless: a
            // call inside an open flood window is what makes Telegram extend it.
            final boolean budget = !SvipeChannelResolve.blocked(account);
            SvipeLimitLog.denied(account, SvipeLimitLog.RESOLVE_USERNAME, SvipeLimitLog.MUSIC_PLAY,
                    budget, SvipeChannelResolve.blockedForSeconds(account),
                    SvipeLimitLog.subject(username, group.isEmpty() ? 0 : group.get(0).channelId),
                    "music");
            done.run();
            return;
        }
        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = username;
        // Through the same paced lane as every other resolve in the app — see SvipeChannelResolve.
        SvipeChannelResolve.pace(true, () -> {
        SvipeChannelResolve.spend(account);
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            SvipeChannelResolve.sent();
            if (error != null || !(response instanceof TLRPC.TL_contacts_resolvedPeer)) {
                SvipeChannelResolve.noteError(account, error);
                SvipeLimitLog.failed(account, SvipeLimitLog.RESOLVE_USERNAME, SvipeLimitLog.MUSIC_PLAY,
                        error, SvipeLimitLog.subject(username, group.isEmpty() ? 0 : group.get(0).channelId),
                        "music");
                done.run();
                return;
            }
            SvipeLimitLog.ok(account, SvipeLimitLog.RESOLVE_USERNAME, SvipeLimitLog.MUSIC_PLAY,
                    SvipeLimitLog.subject(username, group.isEmpty() ? 0 : group.get(0).channelId),
                    "music");
            TLRPC.TL_contacts_resolvedPeer rp = (TLRPC.TL_contacts_resolvedPeer) response;
            // Persisted, not just cached in memory: this call is expensive enough that paying it
            // once per launch is what put the account in a flood window in the first place.
            SvipeChannelResolve.remember(account, rp);
            TLRPC.Chat chat = null;
            long channelId = group.get(0).channelId;
            if (rp.chats != null && !rp.chats.isEmpty()) {
                for (int i = 0; i < rp.chats.size(); i++) {
                    if (rp.chats.get(i).id == channelId) {
                        chat = rp.chats.get(i);
                        break;
                    }
                }
                if (chat == null) {
                    chat = rp.chats.get(0);
                }
            }
            if (chat == null) {
                done.run();
                return;
            }
            chats.put(username, chat);
            fetchMessages(account, chat, group, resolved, done);
            // FailOnServerErrors: without it tgnet swallows a 420 FLOOD_WAIT and re-queues the
            // request behind the wait, so this callback — and the `done` barrier the caller is
            // waiting on — would simply never run (see SvipeAuth for the measured case).
        }), ConnectionsManager.RequestFlagFailOnServerErrors);
        });
    }

    private static void fetchMessages(int account, TLRPC.Chat chat, ArrayList<SvipeMusic.Track> group,
                                      Map<String, TLRPC.Message> resolved, Runnable done) {
        TLRPC.TL_inputChannel inputChannel = new TLRPC.TL_inputChannel();
        inputChannel.channel_id = chat.id;
        inputChannel.access_hash = chat.access_hash;
        TLRPC.TL_channels_getMessages gm = new TLRPC.TL_channels_getMessages();
        gm.channel = inputChannel;
        for (SvipeMusic.Track t : group) {
            gm.id.add(t.messageId);
        }
        ConnectionsManager.getInstance(account).sendRequest(gm, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error != null || !(response instanceof TLRPC.messages_Messages)) {
                SvipeLimitLog.failed(account, SvipeLimitLog.GET_MESSAGES, SvipeLimitLog.MUSIC_PLAY,
                        error, SvipeLimitLog.subject(chat.username, chat.id), "music");
                done.run();
                return;
            }
            SvipeLimitLog.ok(account, SvipeLimitLog.GET_MESSAGES, SvipeLimitLog.MUSIC_PLAY,
                    SvipeLimitLog.subject(chat.username, chat.id), "music");
            TLRPC.messages_Messages mm = (TLRPC.messages_Messages) response;
            MessagesController mc = MessagesController.getInstance(account);
            mc.putUsers(mm.users, false);
            mc.putChats(mm.chats, false);
            if (mm.messages != null) {
                HashMap<Integer, TLRPC.Message> byId = new HashMap<>();
                for (int i = 0; i < mm.messages.size(); i++) {
                    TLRPC.Message m = mm.messages.get(i);
                    if (m == null || m instanceof TLRPC.TL_messageEmpty) {
                        continue;
                    }
                    byId.put(m.id, m);
                }
                for (SvipeMusic.Track t : group) {
                    TLRPC.Message m = byId.get(t.messageId);
                    if (m != null && m.media != null && m.media.document != null
                        && MessageObject.isMusicDocument(m.media.document)) {
                        resolved.put(t.key(), m);
                        SvipeObserved.note(account, t.channelId, t.messageId,
                                new MessageObject(account, m, false, true), "music");
                    } else if (m == null) {
                        // getMessages succeeded but the post is gone (TL_messageEmpty or absent id):
                        // a permanent dead reference. Report it so the backend can stop serving the
                        // track once enough distinct users independently agree. This is ONLY the
                        // success branch — a transient error above never reaches here, so a network
                        // blip can't wrongly retire a live track.
                        try {
                            SvipeObserved.noteGone(account, t.channelId, t.messageId, "music");
                            org.json.JSONObject p = new org.json.JSONObject();
                            p.put("reason", "ref_dead");
                            SvipeMusic.sendEvent(account, t, "PLAY_FAILED", p);
                        } catch (Exception e) {
                            org.telegram.messenger.FileLog.e(e);
                        }
                    }
                    // exists-but-not-music: leave untouched (not a dead ref).
                }
            }
            done.run();
        }), ConnectionsManager.RequestFlagFailOnServerErrors);
    }
}
