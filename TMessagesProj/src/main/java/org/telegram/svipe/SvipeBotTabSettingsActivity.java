package org.telegram.svipe;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.EditTextSettingsCell;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Channel settings → the bot tab. The admin names a bot registered with Svipe; the request waits until
 * the bot has checked that this person is an admin and accepts it. The admin who attached the tab can
 * rename it (when the bot allows) or take it down.
 */
public class SvipeBotTabSettingsActivity extends BaseFragment {

    private final long channelId;
    private SvipeBotTab.State state;
    private LinearLayout content;
    private boolean busy;

    private int headerText() {
        switch (chatType) {
            case "supergroup": case "group": return R.string.SvipeBotTabCurrentGroup;
            case "private": return R.string.SvipeBotTabCurrentPrivate;
            case "saved": return R.string.SvipeBotTabCurrentSaved;
            default: return R.string.SvipeBotTabCurrent;
        }
    }

    private int infoText() {
        switch (chatType) {
            case "supergroup": case "group": return R.string.SvipeBotTabInfoGroup;
            case "private": return R.string.SvipeBotTabInfoPrivate;
            case "saved": return R.string.SvipeBotTabInfoSaved;
            default: return R.string.SvipeBotTabInfo;
        }
    }

    /** "channel", "supergroup", "group", "private" or "saved" (svipe.uz/bots/profile-tabs#places). */
    private final String chatType;

    /**
     * @param key a channel's or supergroup's id, a basic group's negative id, the other person's id in a
     *            private chat, or 0 for Saved Messages
     */
    public SvipeBotTabSettingsActivity(long key, String chatType) {
        this.channelId = key;
        this.chatType = chatType;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.SvipeBotTab));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });
        ScrollView scroll = new ScrollView(context);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0));
        fragmentView = scroll;
        state = "private".equals(chatType) || "saved".equals(chatType) ? null : SvipeBotTab.cached(channelId);
        render();
        reload();
        return fragmentView;
    }

    private void reload() {
        SvipeBotTab.load(currentAccount, chatType, channelId, loaded -> {
            if (loaded != null) {
                state = loaded;
                render();
            }
        });
    }

    private void render() {
        if (content == null) {
            return;
        }
        Context context = content.getContext();
        content.removeAllViews();

        HeaderCell header = new HeaderCell(context);
        header.setText(LocaleController.getString(headerText()));
        header.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        content.addView(header);
        TextCell status = new TextCell(context);
        status.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        status.setTextAndValue(statusText(), "", false);
        content.addView(status);

        boolean active = state != null && state.active;
        if (active && state.canRename) {
            EditTextSettingsCell title = new EditTextSettingsCell(context);
            title.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            title.setTextAndHint(state.title, LocaleController.getString(R.string.SvipeBotTabTitleHint), true);
            content.addView(title);
            TextCell save = new TextCell(context);
            save.setBackground(Theme.getSelectorDrawable(true));
            save.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueButton);
            save.setText(LocaleController.getString(R.string.SvipeBotTabRename), false);
            save.setOnClickListener(v -> run(done -> SvipeBotTab.rename(currentAccount, channelId,
                    title.getTextView().getText().toString(), done)));
            content.addView(save);
        }
        if (active && state.isAttacher) {
            TextCell remove = new TextCell(context);
            remove.setBackground(Theme.getSelectorDrawable(true));
            remove.setColors(Theme.key_text_RedRegular, Theme.key_text_RedRegular);
            remove.setText(LocaleController.getString(R.string.SvipeBotTabRemove), false);
            remove.setOnClickListener(v -> run(done -> SvipeBotTab.detach(currentAccount, chatType, channelId, done)));
            content.addView(remove);
        }
        content.addView(new ShadowSectionCell(context));

        HeaderCell attachHeader = new HeaderCell(context);
        attachHeader.setText(LocaleController.getString(active ? R.string.SvipeBotTabReplace : R.string.SvipeBotTabAttach));
        attachHeader.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        content.addView(attachHeader);
        EditTextSettingsCell username = new EditTextSettingsCell(context);
        username.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        username.setTextAndHint("", LocaleController.getString(R.string.SvipeBotTabUsernameHint), true);
        content.addView(username);
        TextCell send = new TextCell(context);
        send.setBackground(Theme.getSelectorDrawable(true));
        send.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueButton);
        send.setText(LocaleController.getString(R.string.SvipeBotTabSend), false);
        send.setOnClickListener(v -> {
            String name = username.getTextView().getText().toString().trim();
            if (name.startsWith("@")) {
                name = name.substring(1);
            }
            if (TextUtils.isEmpty(name)) {
                AndroidUtilities.shakeViewSpring(username, 4);
                return;
            }
            final String bot = name;
            run(done -> SvipeBotTab.attach(currentAccount, chatType, channelId, bot, done));
        });
        content.addView(send);
        TextInfoPrivacyCell info = new TextInfoPrivacyCell(context);
        info.setText(LocaleController.getString(infoText()));
        content.addView(info);
    }

    private String statusText() {
        if (state != null && state.active) {
            return LocaleController.formatString(R.string.SvipeBotTabActive, state.title, "@" + state.botUsername);
        }
        if (state != null && state.myRequestBot != null) {
            if ("pending".equals(state.myRequestState)) {
                return LocaleController.formatString(R.string.SvipeBotTabPending, "@" + state.myRequestBot);
            }
            if ("refused".equals(state.myRequestState)) {
                return LocaleController.formatString(R.string.SvipeBotTabRefused, "@" + state.myRequestBot);
            }
        }
        return LocaleController.getString(R.string.SvipeBotTabNone);
    }

    private interface Action {
        void run(SvipeBotTab.Done done);
    }

    private void run(Action action) {
        if (busy) {
            return;
        }
        busy = true;
        action.run(error -> {
            busy = false;
            if (error != null) {
                BulletinFactory.of(this).createErrorBulletin(errorText(error)).show();
            }
            reload();
        });
    }

    private String errorText(String error) {
        switch (error) {
            case "bot_not_registered":
                return LocaleController.getString(R.string.SvipeBotTabErrorNotRegistered);
            case "bot_refused":
                return LocaleController.getString(R.string.SvipeBotTabErrorRefused);
            case "rename_not_allowed":
                return LocaleController.getString(R.string.SvipeBotTabErrorNoRename);
            case "not_attacher":
                return LocaleController.getString(R.string.SvipeBotTabErrorNotAllowed);
            default:
                return LocaleController.getString(R.string.UnknownError);
        }
    }
}
