package org.telegram.svipe;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.ArrayList;

/**
 * Crowd-corrected transcripts of ours: a tap on the text of a voice or round message (while "→S" is
 * the engine) offers to fix it, or to see and vote on the versions other people wrote. Each person
 * always sees their own fix; everyone else sees the most-approved one ({@link SvipeStt#shown}).
 *
 * <p>All of it is the app's own UI: the options are {@link ItemOptions}, like the engine picker on
 * the button; the editor and the list are {@link BottomSheet}s with Telegram's own input and buttons.
 */
public final class SvipeSttEditor {

    /** How long a tap waits for the version count before the options open without it. */
    private static final long COUNT_WAIT_MS = 700;

    private SvipeSttEditor() {
    }

    /** The options for one transcript: fix it, or see the other versions. */
    public static void showOptions(ChatMessageCell cell) {
        final MessageObject messageObject = cell == null ? null : cell.getMessageObject();
        if (!SvipeStt.editable(messageObject)) {
            return;
        }
        final int known = SvipeStt.variantCount(messageObject);
        if (known >= 0) {
            open(cell, messageObject, known);
            // Refresh in the background: the count and the approved text may have moved since.
            SvipeStt.variants(messageObject, res -> {});
            return;
        }
        final boolean[] opened = {false};
        final Runnable withoutCount = () -> {
            if (!opened[0]) {
                opened[0] = true;
                open(cell, messageObject, -1);
            }
        };
        AndroidUtilities.runOnUIThread(withoutCount, COUNT_WAIT_MS);
        SvipeStt.variants(messageObject, res -> {
            if (opened[0]) {
                return;
            }
            AndroidUtilities.cancelRunOnUIThread(withoutCount);
            opened[0] = true;
            open(cell, messageObject, SvipeStt.variantCount(messageObject));
        });
    }

    private static void open(ChatMessageCell cell, MessageObject messageObject, int count) {
        final BaseFragment fragment = LaunchActivity.getLastFragment();
        if (fragment == null || fragment.getParentActivity() == null || cell.getMessageObject() != messageObject
                || !SvipeStt.editable(messageObject)) {
            return;
        }
        final Context context = fragment.getParentActivity();
        final Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();
        final String others = count >= 0
                ? LocaleController.formatString(R.string.SvipeSttOtherVersionsCount, count)
                : LocaleController.getString(R.string.SvipeSttOtherVersions);
        ItemOptions.makeOptions(fragment, cell)
                .add(R.drawable.msg_edit, LocaleController.getString(R.string.SvipeSttEditText),
                        () -> showEdit(context, messageObject, resourcesProvider))
                .add(R.drawable.msg_list, others, () -> showVariants(context, messageObject, resourcesProvider))
                .setDimAlpha(0)
                .show();
    }

    // ---- Edit ---------------------------------------------------------------------------------

    public static void showEdit(Context context, MessageObject messageObject, Theme.ResourcesProvider resourcesProvider) {
        final BottomSheet sheet = new BottomSheet(context, true, resourcesProvider);
        sheet.smoothKeyboardAnimationEnabled = true;
        sheet.setApplyBottomPadding(false);

        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);

        layout.addView(title(context, R.string.SvipeSttEditTitle, resourcesProvider),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 16, 22, 4));
        layout.addView(info(context, LocaleController.getString(R.string.SvipeSttEditInfo), resourcesProvider),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 0, 22, 12));

        final String current = messageObject.messageOwner.voiceTranscription == null ? "" : messageObject.messageOwner.voiceTranscription;
        final EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, resourcesProvider));
        editText.setHint(LocaleController.getString(R.string.SvipeSttEditHint));
        editText.setBackgroundDrawable(null);
        editText.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField, resourcesProvider),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, resourcesProvider),
                Theme.getColor(Theme.key_text_RedRegular, resourcesProvider));
        editText.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        editText.setCursorSize(AndroidUtilities.dp(20));
        editText.setCursorWidth(1.5f);
        editText.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        editText.setSingleLine(false);
        editText.setMaxLines(8);
        editText.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(10));
        editText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(SvipeStt.EDIT_MAX)});
        editText.setText(current);
        editText.setSelection(editText.length());
        layout.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 0, 22, 12));

        final ButtonWithCounterView save = new ButtonWithCounterView(context, resourcesProvider);
        save.setText(LocaleController.getString(R.string.Save), false);
        layout.addView(save, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 4, 16, 0));

        final ButtonWithCounterView cancel = new ButtonWithCounterView(context, false, resourcesProvider);
        cancel.setText(LocaleController.getString(R.string.Cancel), false);
        layout.addView(cancel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 6, 16, 12));

        cancel.setOnClickListener(v -> {
            AndroidUtilities.hideKeyboard(editText);
            sheet.dismiss();
        });
        save.setOnClickListener(v -> {
            AndroidUtilities.hideKeyboard(editText);
            sheet.dismiss();
            save(messageObject, editText.getText().toString().trim(), current);
        });

        final ScrollView scrollView = new ScrollView(context);
        scrollView.addView(layout);
        sheet.setCustomView(scrollView);
        sheet.show();
        AndroidUtilities.runOnUIThread(() -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        }, 250);
    }

    /**
     * Shows my fix right away (it is mine: I always see it), then sends it. When it does not go
     * through, my previous fix comes back, so the screen never claims what the server never got.
     */
    private static void save(MessageObject messageObject, String text, String current) {
        final String previous = SvipeStt.mine(messageObject);
        if (text.equals(current.trim()) || text.isEmpty() && TextUtils.isEmpty(previous)) {
            return;
        }
        SvipeStt.setMine(messageObject, text);
        SvipeStt.applyShown(messageObject);
        SvipeStt.saveEdit(messageObject, text, status -> {
            if (status == null) {
                SvipeStt.setMine(messageObject, previous);
                SvipeStt.applyShown(messageObject);
                BulletinFactory.global().createErrorBulletin(LocaleController.getString(R.string.SvipeSttEditFailed)).show();
                return;
            }
            if (text.isEmpty()) {
                BulletinFactory.global().createSimpleBulletin(R.raw.done, LocaleController.getString(R.string.SvipeSttEditRemoved)).show();
            } else if ("suspect".equals(status)) {
                BulletinFactory.global().createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.SvipeSttEditSuspect)).show();
            } else {
                BulletinFactory.global().createSimpleBulletin(R.raw.done, LocaleController.getString(R.string.SvipeSttEditSaved)).show();
            }
        });
    }

    // ---- Versions -----------------------------------------------------------------------------

    public static void showVariants(Context context, MessageObject messageObject, Theme.ResourcesProvider resourcesProvider) {
        final BottomSheet sheet = new BottomSheet(context, false, resourcesProvider);
        sheet.setApplyBottomPadding(false);

        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(title(context, R.string.SvipeSttVersionsTitle, resourcesProvider),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 16, 22, 4));
        layout.addView(info(context, LocaleController.getString(R.string.SvipeSttVersionsInfo), resourcesProvider),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 0, 22, 8));

        final LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        layout.addView(list, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        final FrameLayout loading = new FrameLayout(context);
        final RadialProgressView progress = new RadialProgressView(context, resourcesProvider);
        progress.setSize(AndroidUtilities.dp(28));
        loading.addView(progress, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        list.addView(loading, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 96));

        final ScrollView scrollView = new ScrollView(context);
        scrollView.addView(layout);
        sheet.setCustomView(scrollView);
        sheet.show();

        new VariantsList(sheet, list, messageObject, resourcesProvider).load();
    }

    /** The rows of the versions sheet: the machine's text, then everybody's fixes with their votes. */
    private static final class VariantsList {
        private final BottomSheet sheet;
        private final LinearLayout list;
        private final MessageObject messageObject;
        private final Theme.ResourcesProvider resourcesProvider;
        private final Context context;
        private boolean voting;

        VariantsList(BottomSheet sheet, LinearLayout list, MessageObject messageObject, Theme.ResourcesProvider resourcesProvider) {
            this.sheet = sheet;
            this.list = list;
            this.messageObject = messageObject;
            this.resourcesProvider = resourcesProvider;
            this.context = list.getContext();
        }

        void load() {
            SvipeStt.variants(messageObject, res -> {
                if (!sheet.isShowing()) {
                    return;
                }
                if (res == null) {
                    sheet.dismiss();
                    BulletinFactory.global().createErrorBulletin(LocaleController.getString(R.string.SvipeSttVersionsFailed)).show();
                    return;
                }
                bind(res);
            });
        }

        private void bind(JSONObject res) {
            list.removeAllViews();
            final JSONObject best = res.optJSONObject("best");
            final long bestId = best == null ? 0 : best.optLong("id", 0);

            final JSONObject machine = res.optJSONObject("machine");
            String machineText = machine == null || machine.isNull("text") ? null : machine.optString("text", null);
            if (TextUtils.isEmpty(machineText)) {
                machineText = SvipeStt.machine(messageObject);
            }
            final ArrayList<String> labels = new ArrayList<>();
            labels.add(LocaleController.getString(R.string.SvipeSttVersionMachine));
            if (bestId == 0) {
                labels.add(LocaleController.getString(R.string.SvipeSttVersionBest));
            }
            list.addView(row(TextUtils.join(" · ", labels), machineText, null),
                    LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            final JSONArray variants = res.optJSONArray("variants");
            if (variants == null || variants.length() == 0) {
                list.addView(info(context, LocaleController.getString(R.string.SvipeSttVersionsEmpty), resourcesProvider),
                        LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 8, 22, 8));
                return;
            }
            for (int i = 0; i < variants.length(); i++) {
                final JSONObject v = variants.optJSONObject(i);
                if (v == null) {
                    continue;
                }
                labels.clear();
                if (v.optBoolean("mine", false)) {
                    labels.add(LocaleController.getString(R.string.SvipeSttVersionMine));
                }
                if (bestId != 0 && v.optLong("id", 0) == bestId) {
                    labels.add(LocaleController.getString(R.string.SvipeSttVersionBest));
                }
                list.addView(row(labels.isEmpty() ? null : TextUtils.join(" · ", labels), v.optString("text", ""), v),
                        LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }
        }

        /** One version: its marks, its text, and (for other people's) the vote controls. */
        private View row(String label, String text, JSONObject variant) {
            final LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(10), AndroidUtilities.dp(10), AndroidUtilities.dp(10));

            final LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            if (label != null) {
                final TextView labelView = new TextView(context);
                labelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                labelView.setTypeface(AndroidUtilities.bold());
                labelView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, resourcesProvider));
                labelView.setText(label);
                texts.addView(labelView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 2));
            }
            final TextView textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            textView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
            textView.setText(TextUtils.isEmpty(text) ? LocaleController.getString(R.string.NoWordsRecognized) : text);
            textView.setTextIsSelectable(true);
            texts.addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            if (variant != null) {
                final boolean mine = variant.optBoolean("mine", false);
                final int myVote = variant.optInt("my_vote", 0);
                final LinearLayout votes = new LinearLayout(context);
                votes.setOrientation(LinearLayout.VERTICAL);
                votes.setGravity(Gravity.CENTER_HORIZONTAL);
                final ImageView up = voteButton(R.drawable.msg_rate_up, myVote == 1, !mine, R.string.SvipeSttVoteUp);
                final TextView count = new TextView(context);
                count.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                count.setTypeface(AndroidUtilities.bold());
                count.setGravity(Gravity.CENTER);
                count.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
                count.setText(String.valueOf(variant.optInt("votes", 0)));
                final ImageView down = voteButton(R.drawable.msg_rate_down, myVote == -1, !mine, R.string.SvipeSttVoteDown);
                votes.addView(up, LayoutHelper.createLinear(40, 36));
                votes.addView(count, LayoutHelper.createLinear(40, LayoutHelper.WRAP_CONTENT));
                votes.addView(down, LayoutHelper.createLinear(40, 36));
                row.addView(votes, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 8, 0, 0, 0));
                if (!mine) {
                    up.setOnClickListener(v -> vote(variant, myVote == 1 ? 0 : 1));
                    down.setOnClickListener(v -> vote(variant, myVote == -1 ? 0 : -1));
                }
            }
            return row;
        }

        private ImageView voteButton(int icon, boolean chosen, boolean enabled, int description) {
            final ImageView button = new ImageView(context);
            button.setScaleType(ImageView.ScaleType.CENTER);
            button.setImageResource(icon);
            button.setContentDescription(LocaleController.getString(description));
            final int color = chosen
                    ? Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider)
                    : Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon, resourcesProvider);
            button.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            if (enabled) {
                button.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), 1, AndroidUtilities.dp(18)));
            } else {
                // My own version: I cannot vote on it.
                button.setAlpha(0.35f);
                button.setEnabled(false);
            }
            return button;
        }

        private void vote(JSONObject variant, int value) {
            if (voting) {
                return;
            }
            voting = true;
            SvipeStt.vote(messageObject.currentAccount, variant.optLong("id", 0), value, ok -> {
                voting = false;
                if (!sheet.isShowing()) {
                    return;
                }
                if (!ok) {
                    BulletinFactory.of(sheet.getContainer(), resourcesProvider)
                            .createErrorBulletin(LocaleController.getString(R.string.SvipeSttVoteFailed)).show();
                    return;
                }
                // The approved version may have changed with this vote: ask again, which also
                // redraws the message when what everyone sees moved.
                load();
            });
        }
    }

    // ---- Shared bits --------------------------------------------------------------------------

    private static TextView title(Context context, int text, Theme.ResourcesProvider resourcesProvider) {
        final TextView title = new TextView(context);
        title.setText(LocaleController.getString(text));
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        return title;
    }

    private static TextView info(Context context, CharSequence text, Theme.ResourcesProvider resourcesProvider) {
        final TextView info = new TextView(context);
        info.setText(text);
        info.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, resourcesProvider));
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        return info;
    }
}
