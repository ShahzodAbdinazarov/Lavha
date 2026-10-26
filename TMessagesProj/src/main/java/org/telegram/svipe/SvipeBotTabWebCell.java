package org.telegram.svipe;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.bots.BotWebViewSheet;
import org.telegram.ui.web.BotWebViewContainer;

import java.util.List;

/**
 * A bot tab in {@code mode: "web"} (svipe.uz/bots/profile-tabs#web): the bot's page, opened as a mini
 * app inside the tab and as tall as the tab. Telegram signs the viewer into its initData as for any
 * mini app; the URL then gets svipeInitData too: the profile's owner ({@code chat_type=profile}) or the
 * channel ({@code chat_type=channel}).
 */
@SuppressLint("ViewConstructor")
public class SvipeBotTabWebCell extends FrameLayout {

    private BotWebViewContainer container;
    private String loaded;

    public SvipeBotTabWebCell(Context context) {
        super(context);
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
    }

    /** Loads the tab's page once per (bot, page, peer); binding the same state again keeps it as is. */
    public void bind(SvipeBotTab.State state) {
        if (state == null || TextUtils.isEmpty(state.webUrl) || state.botId == 0) {
            return;
        }
        final long peerId = state.ownerId != 0 ? state.ownerId : -state.channelId;
        final String chatType = state.ownerId != 0 ? "profile" : "channel";
        final String key = state.botId + "|" + state.webUrl + "|" + peerId;
        if (key.equals(loaded)) {
            return;
        }
        loaded = key;
        final int account = UserConfig.selectedAccount;
        if (container == null) {
            container = new BotWebViewContainer(getContext(), null, Theme.getColor(Theme.key_windowBackgroundWhite), true) {};
            container.setDelegate(new Delegate());
            addView(container, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }
        TLRPC.User bot = MessagesController.getInstance(account).getUser(state.botId);
        if (bot != null) {
            open(account, bot, state.webUrl, peerId, chatType, key);
        } else if (!TextUtils.isEmpty(state.botUsername)) {
            MessagesController.getInstance(account).getUserNameResolver().resolve(state.botUsername, id -> {
                TLRPC.User resolved = id != null ? MessagesController.getInstance(account).getUser(id) : null;
                if (resolved != null) {
                    open(account, resolved, state.webUrl, peerId, chatType, key);
                }
            });
        }
    }

    private void open(int account, TLRPC.User bot, String webUrl, long peerId, String chatType, String key) {
        container.setBotUser(bot);
        TLRPC.TL_messages_requestSimpleWebView req = new TLRPC.TL_messages_requestSimpleWebView();
        req.bot = MessagesController.getInstance(account).getInputUser(bot);
        req.platform = "android";
        req.url = webUrl;
        req.flags |= 8;
        JSONObject theme = BotWebViewSheet.makeThemeParams(null);
        if (theme != null) {
            req.theme_params = new TLRPC.TL_dataJSON();
            req.theme_params.data = theme.toString();
            req.flags |= 1;
        }
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!(response instanceof TLRPC.TL_webViewResultUrl) || !key.equals(loaded) || container == null) {
                return;
            }
            TLRPC.TL_webViewResultUrl result = (TLRPC.TL_webViewResultUrl) response;
            // svipeInitData for whoever is looking, whatever the tab shows (owner's decision, 2026-10-26).
            SvipeAttachMenu.get(account).signContext(bot.id, peerId, result.url, null, chatType, signed -> {
                if (key.equals(loaded) && container != null) {
                    container.loadUrl(account, signed, result.same_origin);
                }
            });
        }));
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // The page scrolls itself; the profile list must not take the gesture away from it.
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // As tall as the tab's page: the list this cell sits in.
        int height = getParent() instanceof View ? ((View) getParent()).getMeasuredHeight() : 0;
        if (height <= 0) {
            height = AndroidUtilities.displaySize.y / 2;
        }
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    /** Called when the profile closes. */
    public void destroy() {
        if (container != null) {
            container.destroyWebView();
            removeView(container);
            container = null;
        }
        loaded = null;
    }

    /** The tab is not a sheet: nothing to close, no buttons of its own to show. */
    private static class Delegate implements BotWebViewContainer.Delegate {
        @Override public void onCloseRequested(@Nullable Runnable callback) {}
        @Override public void onWebAppSetupClosingBehavior(boolean needConfirmation) {}
        @Override public void onWebAppSwipingBehavior(boolean allowSwiping) {}
        @Override public void onWebAppSetActionBarColor(int colorKey, int color, boolean isOverrideColor) {}
        @Override public void onWebAppSetBackgroundColor(int color) {}
        @Override public void onWebAppExpand() {}
        @Override public void onWebAppSwitchInlineQuery(TLRPC.User botUser, String query, List<String> chatTypes) {}
        @Override public void onWebAppOpenInvoice(TLRPC.InputInvoice inputInvoice, String slug, TLObject response) {}
        @Override public void onSetupMainButton(boolean isVisible, boolean isActive, String text, long emojiId, int color, int textColor, boolean isProgressVisible, boolean hasShineEffect) {}
        @Override public void onSetupSecondaryButton(boolean isVisible, boolean isActive, String text, long emojiId, int color, int textColor, boolean isProgressVisible, boolean hasShineEffect, String position) {}
        @Override public void onSetBackButtonVisible(boolean visible) {}
        @Override public void onSetSettingsButtonVisible(boolean visible) {}
    }
}
