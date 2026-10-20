package org.telegram.svipe;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.ChatAttachAlert;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The order of the attachment menu: Gallery always first, then whatever was last used to SEND
 * something, most recent first; what was never used keeps the menu's own order after that. Built-in
 * tabs and bots compete on equal terms.
 *
 * <p>Opening a tab is not using it. A tab counts once a message it made is sent: the tab opened last
 * in a chat is remembered, and the next outgoing message in that chat that is the kind of thing the tab
 * sends (a location for Location, a file for Files…) moves it. A bot counts when a message "via" it is
 * sent. The order is written the moment the message goes out, so opening the menu only reads it.
 *
 * <p>One order for every chat of the account, for now.
 */
public final class SvipeAttachOrder implements NotificationCenter.NotificationCenterDelegate {

    public static final String GALLERY = "gallery";
    private static final int MAX = 64;
    // Picking a contact, a file or a place can take a while; past this it is no longer that tab's send.
    private static final long PENDING_MS = 30 * 60 * 1000L;

    private static final SvipeAttachOrder[] instances = new SvipeAttachOrder[UserConfig.MAX_ACCOUNT_COUNT];

    public static synchronized SvipeAttachOrder get(int account) {
        if (instances[account] == null) {
            instances[account] = new SvipeAttachOrder(account);
        }
        return instances[account];
    }

    private final int account;
    private final ArrayList<String> recent = new ArrayList<>();
    private int pendingTab = -1;
    private long pendingDialog;
    private long pendingAt;

    private SvipeAttachOrder(int account) {
        this.account = account;
        String saved = prefs().getString("order_" + account, "");
        if (!TextUtils.isEmpty(saved)) {
            recent.addAll(Arrays.asList(saved.split(",")));
        }
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.didReceiveNewMessages);
    }

    public static String tabKey(int tab) {
        return tab == ChatAttachAlert.LAYOUT_TYPE_PHOTO ? GALLERY : "tab" + tab;
    }

    public static String botKey(long botId) {
        return "bot" + botId;
    }

    /** A tab of the menu was opened in this chat; its next matching send makes it the most recent. */
    public void opened(int tab, long dialogId) {
        pendingTab = tab == ChatAttachAlert.LAYOUT_TYPE_PHOTO ? -1 : tab;
        pendingDialog = dialogId;
        pendingAt = System.currentTimeMillis();
    }

    /** {@code keys} in menu order, rearranged: Gallery, the recently used, then the rest as they were. */
    public <T> List<T> sort(List<T> items, List<String> keys) {
        return sort(items, keys, recent);
    }

    static <T> List<T> sort(List<T> items, List<String> keys, List<String> recent) {
        ArrayList<T> out = new ArrayList<>(items.size());
        boolean[] taken = new boolean[items.size()];
        int gallery = keys.indexOf(GALLERY);
        if (gallery >= 0) {
            out.add(items.get(gallery));
            taken[gallery] = true;
        }
        for (String key : recent) {
            int i = keys.indexOf(key);
            if (i >= 0 && !taken[i]) {
                out.add(items.get(i));
                taken[i] = true;
            }
        }
        for (int i = 0; i < items.size(); i++) {
            if (!taken[i]) {
                out.add(items.get(i));
            }
        }
        return out;
    }

    private void used(String key) {
        if (GALLERY.equals(key) || (!recent.isEmpty() && recent.get(0).equals(key))) {
            return;
        }
        recent.remove(key);
        recent.add(0, key);
        while (recent.size() > MAX) {
            recent.remove(recent.size() - 1);
        }
        prefs().edit().putString("order_" + account, TextUtils.join(",", recent)).apply();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.didReceiveNewMessages || args.length < 2 || !(args[1] instanceof ArrayList)) {
            return;
        }
        if (args.length > 2 && args[2] instanceof Boolean && (Boolean) args[2]) {
            return; // scheduled, not sent
        }
        final long dialogId = (Long) args[0];
        for (Object o : (ArrayList<?>) args[1]) {
            if (!(o instanceof MessageObject)) continue;
            MessageObject message = (MessageObject) o;
            if (!message.isOut() || message.messageOwner == null) continue;
            if (message.messageOwner.via_bot_id != 0) {
                used(botKey(message.messageOwner.via_bot_id));
                continue;
            }
            if (pendingTab >= 0 && dialogId == pendingDialog
                    && System.currentTimeMillis() - pendingAt < PENDING_MS && madeBy(pendingTab, message)) {
                used(tabKey(pendingTab));
                pendingTab = -1;
            }
        }
    }

    /** Whether this message is what that tab sends — so a text typed after closing it does not count. */
    private static boolean madeBy(int tab, MessageObject message) {
        TLRPC.MessageMedia media = message.messageOwner.media;
        switch (tab) {
            case ChatAttachAlert.LAYOUT_TYPE_MUSIC:
                return message.isMusic();
            case ChatAttachAlert.LAYOUT_TYPE_DOCUMENTS:
                return message.getDocument() != null && !message.isMusic() && !message.isVoice() && !message.isRoundVideo();
            case ChatAttachAlert.LAYOUT_TYPE_LOCATION:
                return media instanceof TLRPC.TL_messageMediaGeo || media instanceof TLRPC.TL_messageMediaVenue
                        || media instanceof TLRPC.TL_messageMediaGeoLive;
            case ChatAttachAlert.LAYOUT_TYPE_CONTACTS:
                return media instanceof TLRPC.TL_messageMediaContact;
            case ChatAttachAlert.LAYOUT_TYPE_POLL:
                return media instanceof TLRPC.TL_messageMediaPoll;
            case ChatAttachAlert.LAYOUT_TYPE_TODO:
                return media instanceof TLRPC.TL_messageMediaToDo;
            default:
                // Article, money, quick replies: whatever they send is theirs.
                return true;
        }
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("svipe_attach_order", Context.MODE_PRIVATE);
    }
}
