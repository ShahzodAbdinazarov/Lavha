package org.telegram.svipe;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Business.BusinessChatbotController;
import org.telegram.ui.Business.ChatbotsActivity;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.PremiumPreviewFragment;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

/**
 * A bot asks to become the user's business chatbot (Telegram Business → Chatbots), the way an
 * attachment-menu bot asks to be added: {@code t.me/<bot>?business=reply+read_messages+…}.
 *
 * <p>The link names the rights the bot wants, with the field names of {@code businessBotRights};
 * an empty {@code business} asks for Telegram's own defaults. A bottom sheet shows the bot and every
 * right it would get; Connect is {@code account.updateConnectedBot} for all private chats, and a
 * snackbar then opens Telegram's Chatbots page, where the chats and rights can be changed or the bot
 * disconnected. Nothing here can lift Telegram's own limits: business bots need Telegram Premium and
 * still answer only within the time Telegram allows.
 */
public final class SvipeBusinessBot {

    /** The rights a link may ask for, by their TL names, with the line the sheet shows for each. */
    private static final String[] RIGHTS = {
            "reply", "read_messages", "delete_sent_messages", "delete_received_messages",
            "edit_name", "edit_bio", "edit_profile_photo", "edit_username",
            "view_gifts", "sell_gifts", "change_gift_settings", "transfer_and_upgrade_gifts", "transfer_stars",
            "manage_stories",
    };
    private static final int[] RIGHT_TEXTS = {
            R.string.BusinessBotPermissionsMessagesReply, R.string.BusinessBotPermissionsMessagesMarkAsRead,
            R.string.BusinessBotPermissionsMessagesDeleteSent, R.string.BusinessBotPermissionsMessagesDeleteReceived,
            R.string.BusinessBotPermissionsProfileName, R.string.BusinessBotPermissionsProfileBio,
            R.string.BusinessBotPermissionsProfilePicture, R.string.BusinessBotPermissionsProfileUsername,
            R.string.BusinessBotPermissionsGiftsView, R.string.BusinessBotPermissionsGiftsSell,
            R.string.BusinessBotPermissionsGiftsSettings, R.string.BusinessBotPermissionsGiftsTransfer,
            R.string.BusinessBotPermissionsGiftsTransferStars,
            R.string.BusinessBotPermissionsStories,
    };

    /** From LaunchActivity, for a {@code business} link to {@code username}. */
    public static void request(LaunchActivity activity, int account, String username, String rightsParam) {
        final MessagesController mc = MessagesController.getInstance(account);
        mc.getUserNameResolver().resolve(username, peerId -> {
            TLRPC.User bot = peerId != null && peerId > 0 ? mc.getUser(peerId) : null;
            BaseFragment fragment = LaunchActivity.getSafeLastFragment();
            if (fragment == null) {
                return;
            }
            if (bot == null || !bot.bot) {
                BulletinFactory.of(fragment).createErrorBulletin(getString(R.string.BusinessBotNotFound)).show();
                return;
            }
            if (!bot.bot_business) {
                BulletinFactory.of(fragment).createErrorBulletin(getString(R.string.BusinessBotNotSupportedTitle)).show();
                return;
            }
            if (!UserConfig.getInstance(account).isPremium()) {
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.star_premium_2,
                        getString(R.string.SvipeBusinessBotPremium), getString(R.string.SvipeBusinessBotPremiumOpen),
                        () -> fragment.presentFragment(new PremiumPreviewFragment("business"))).show();
                return;
            }
            final TL_account.TL_businessBotRights rights = parseRights(rightsParam);
            BusinessChatbotController.getInstance(account).load(connected -> {
                TLRPC.User current = null;
                if (connected != null && !connected.connected_bots.isEmpty()) {
                    long id = connected.connected_bots.get(0).bot_id;
                    current = id == bot.id ? null : mc.getUser(id);
                }
                show(fragment, account, bot, rights, current);
            });
        });
    }

    static TL_account.TL_businessBotRights parseRights(String param) {
        if (param == null || param.trim().isEmpty()) {
            return TL_account.TL_businessBotRights.makeDefault();
        }
        HashSet<String> asked = new HashSet<>(Arrays.asList(param.trim().toLowerCase().split("[\\s+,]+")));
        TL_account.TL_businessBotRights r = new TL_account.TL_businessBotRights();
        r.reply = asked.contains("reply");
        r.read_messages = asked.contains("read_messages");
        r.delete_sent_messages = asked.contains("delete_sent_messages");
        r.delete_received_messages = asked.contains("delete_received_messages");
        r.edit_name = asked.contains("edit_name");
        r.edit_bio = asked.contains("edit_bio");
        r.edit_profile_photo = asked.contains("edit_profile_photo");
        r.edit_username = asked.contains("edit_username");
        r.view_gifts = asked.contains("view_gifts");
        r.sell_gifts = asked.contains("sell_gifts");
        r.change_gift_settings = asked.contains("change_gift_settings");
        r.transfer_and_upgrade_gifts = asked.contains("transfer_and_upgrade_gifts");
        r.transfer_stars = asked.contains("transfer_stars");
        r.manage_stories = asked.contains("manage_stories");
        return r;
    }

    private static boolean has(TL_account.TL_businessBotRights r, int i) {
        switch (i) {
            case 0: return r.reply;
            case 1: return r.read_messages;
            case 2: return r.delete_sent_messages;
            case 3: return r.delete_received_messages;
            case 4: return r.edit_name;
            case 5: return r.edit_bio;
            case 6: return r.edit_profile_photo;
            case 7: return r.edit_username;
            case 8: return r.view_gifts;
            case 9: return r.sell_gifts;
            case 10: return r.change_gift_settings;
            case 11: return r.transfer_and_upgrade_gifts;
            case 12: return r.transfer_stars;
            default: return r.manage_stories;
        }
    }

    private static void show(BaseFragment fragment, int account, TLRPC.User bot,
                             TL_account.TL_businessBotRights rights, TLRPC.User current) {
        final Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        final Theme.ResourcesProvider rp = fragment.getResourceProvider();
        final BottomSheet sheet = new BottomSheet(context, false, rp);

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(22), dp(20), dp(22), dp(14));

        BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(40));
        avatar.setForUserOrChat(bot, new AvatarDrawable(bot));
        layout.addView(avatar, LayoutHelper.createLinear(80, 80, Gravity.CENTER_HORIZONTAL));

        TextView title = new TextView(context);
        title.setText(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.SvipeBusinessBotRequestTitle, UserObject.getUserName(bot))));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        layout.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 6));

        TextView text = new TextView(context);
        text.setText(getString(R.string.SvipeBusinessBotRequestText));
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        text.setGravity(Gravity.CENTER);
        text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp));
        layout.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 16));

        TextView header = new TextView(context);
        header.setText(getString(R.string.BusinessBotPermissions));
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        header.setTypeface(AndroidUtilities.bold());
        header.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, rp));
        layout.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

        // Reading the chats it serves comes with being a business bot at all, so it is always listed.
        ArrayList<String> lines = new ArrayList<>();
        lines.add(getString(R.string.BusinessBotPermissionsMessagesRead));
        for (int i = 0; i < RIGHTS.length; i++) {
            if (has(rights, i)) {
                lines.add(getString(RIGHT_TEXTS[i]));
            }
        }
        for (String line : lines) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            ImageView check = new ImageView(context);
            check.setImageResource(R.drawable.msg_check_s);
            check.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_featuredStickers_addButton, rp), PorterDuff.Mode.SRC_IN));
            row.addView(check, LayoutHelper.createLinear(24, 24, 0, 0, 12, 0));
            TextView label = new TextView(context);
            label.setText(line);
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
            row.addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            layout.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40));
        }

        if (current != null) {
            TextView replaces = new TextView(context);
            replaces.setText(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.SvipeBusinessBotReplaces, UserObject.getUserName(current))));
            replaces.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            replaces.setTextColor(Theme.getColor(Theme.key_text_RedRegular, rp));
            layout.addView(replaces, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        }

        ButtonWithCounterView connect = new ButtonWithCounterView(context, rp);
        connect.setText(getString(R.string.SvipeBusinessBotConnect), false);
        layout.addView(connect, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 18, 0, 0));

        ButtonWithCounterView cancel = new ButtonWithCounterView(context, false, rp);
        cancel.setText(getString(R.string.Cancel), false);
        cancel.setOnClickListener(v -> sheet.dismiss());
        layout.addView(cancel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 6, 0, 0));

        connect.setOnClickListener(v -> {
            if (connect.isLoading()) return;
            connect.setLoading(true);
            connectBot(account, bot, rights, current, error -> {
                connect.setLoading(false);
                if (error != null) {
                    BulletinFactory.of(sheet.getContainer(), rp).createErrorBulletin(error).show();
                    return;
                }
                sheet.dismiss();
                BaseFragment last = LaunchActivity.getSafeLastFragment();
                if (last != null) {
                    BulletinFactory.of(last).createSimpleBulletin(R.raw.contact_check,
                            AndroidUtilities.replaceTags(LocaleController.formatString(R.string.SvipeBusinessBotConnected, UserObject.getUserName(bot))),
                            getString(R.string.Settings),
                            () -> {
                                BaseFragment f = LaunchActivity.getSafeLastFragment();
                                if (f != null) f.presentFragment(new ChatbotsActivity());
                            }).setDuration(5000).show();
                }
            });
        });

        ScrollView scroll = new ScrollView(context);
        scroll.addView(layout);
        sheet.setCustomView(scroll);
        fragment.showDialog(sheet);
    }

    /** Replaces the connected bot, as Telegram's Chatbots page does: the old one out, the new one in. */
    private static void connectBot(int account, TLRPC.User bot, TL_account.TL_businessBotRights rights,
                                   TLRPC.User current, org.telegram.messenger.Utilities.Callback<String> done) {
        final MessagesController mc = MessagesController.getInstance(account);
        final Runnable add = () -> {
            TL_account.updateConnectedBot req = new TL_account.updateConnectedBot();
            req.deleted = false;
            req.rights = rights;
            req.bot = mc.getInputUser(bot);
            // Every private chat, as Telegram's own page starts: the user narrows it there.
            TL_account.TL_inputBusinessBotRecipients recipients = new TL_account.TL_inputBusinessBotRecipients();
            recipients.existing_chats = true;
            recipients.new_chats = true;
            recipients.contacts = true;
            recipients.non_contacts = true;
            recipients.exclude_selected = true;
            req.recipients = recipients;
            ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
                if (err != null) {
                    done.run(TextUtils.isEmpty(err.text) ? getString(R.string.UnknownError) : err.text);
                    return;
                }
                if (res instanceof TLRPC.Updates) {
                    mc.processUpdates((TLRPC.Updates) res, false);
                }
                BusinessChatbotController.getInstance(account).invalidate(true);
                done.run(null);
            }));
        };
        if (current == null) {
            add.run();
            return;
        }
        TL_account.updateConnectedBot remove = new TL_account.updateConnectedBot();
        remove.deleted = true;
        remove.bot = mc.getInputUser(current);
        remove.recipients = new TL_account.TL_inputBusinessBotRecipients();
        ConnectionsManager.getInstance(account).sendRequest(remove, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            if (err != null) {
                done.run(err.text);
            } else {
                add.run();
            }
        }));
    }
}
