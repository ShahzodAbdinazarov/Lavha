package org.telegram.svipe;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * What a guest is offered when they reach for something an account owns.
 *
 * <p>This replaced an alert that said "sign up to do that" and stopped there. A wall with no answer
 * to "why would I" is a toll booth, and people close apps at toll booths. So the sheet does one job:
 * it names what an account actually unlocks, in the visitor's own language, next to the thing they
 * just tried to do.
 *
 * <p>Every line is true and specific. "Like, comment and follow" is not us withholding a feature —
 * those actions physically happen on the person's Telegram account, and saying so is both honest and
 * the strongest argument, because it means the account they are being asked for is one they probably
 * already have. The other three are the surfaces a guest genuinely cannot see: music and long video,
 * a feed that carries across devices, and anything remembered between sessions.
 *
 * <p>Native components throughout — {@link BottomSheet}, {@link Theme} colours, the filled-button
 * ripple the rest of the app uses — so it inherits dark mode, the drag handle and the corner radius
 * without re-deriving any of them.
 */
public class SvipeGuestSignUpSheet {

    public interface Listener {
        void onSignUp();
    }

    private SvipeGuestSignUpSheet() {
    }

    public static void show(Context context, Listener listener) {
        if (context == null) {
            return;
        }
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(8));

        TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.SvipeGuestSheetTitle));
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, 22, 4, 22, 0));

        TextView sub = new TextView(context);
        sub.setText(LocaleController.getString(R.string.SvipeGuestSheetSub));
        sub.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, 22, 8, 22, 14));

        // U+FE0E after each glyph is the text-presentation selector, and it is not decoration: without
        // it Android hands ♥ and ⌛ to the emoji font, which draws them in its own colours and ignores
        // the tint — so two badges came out full-colour next to two tinted ones.
        addBenefit(content, "\u2665\uFE0E", R.string.SvipeGuestBenefit1, R.string.SvipeGuestBenefit1Text);
        addBenefit(content, "\u266A\uFE0E", R.string.SvipeGuestBenefit2, R.string.SvipeGuestBenefit2Text);
        addBenefit(content, "\u2726\uFE0E", R.string.SvipeGuestBenefit3, R.string.SvipeGuestBenefit3Text);
        addBenefit(content, "\u21BB\uFE0E", R.string.SvipeGuestBenefit4, R.string.SvipeGuestBenefit4Text);

        final BottomSheet[] sheet = new BottomSheet[1];

        TextView signUp = new TextView(context);
        signUp.setText(LocaleController.getString(R.string.SvipeGuestSignUp));
        signUp.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        signUp.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        signUp.setTypeface(AndroidUtilities.bold());
        signUp.setGravity(Gravity.CENTER);
        signUp.setBackground(Theme.AdaptiveRipple.filledRectByKey(
                Theme.key_featuredStickers_addButton, 8));
        signUp.setOnClickListener(v -> {
            if (sheet[0] != null) {
                sheet[0].dismiss();
            }
            if (listener != null) {
                listener.onSignUp();
            }
        });
        content.addView(signUp, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48,
                16, 8, 16, 0));

        TextView notNow = new TextView(context);
        notNow.setText(LocaleController.getString(R.string.SvipeGuestNotNow));
        notNow.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        notNow.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        notNow.setGravity(Gravity.CENTER);
        // A visible way out, on purpose. A sheet the visitor has to guess their way out of is the
        // same toll booth wearing a nicer coat.
        notNow.setOnClickListener(v -> {
            if (sheet[0] != null) {
                sheet[0].dismiss();
            }
        });
        content.addView(notNow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48,
                16, 4, 16, 4));

        BottomSheet.Builder builder = new BottomSheet.Builder(context);
        builder.setApplyBottomPadding(false);
        builder.setCustomView(content);
        sheet[0] = builder.show();
    }

    /** One promise: a glyph in a tinted circle, a name, and the sentence that makes it concrete. */
    private static void addBenefit(LinearLayout parent, String glyph, int titleRes, int textRes) {
        final Context context = parent.getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);

        FrameLayout badge = new FrameLayout(context) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(Canvas canvas) {
                paint.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
                paint.setAlpha(30);
                final float r = Math.min(getWidth(), getHeight()) / 2f;
                canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, r, paint);
            }
        };
        badge.setWillNotDraw(false);
        TextView icon = new TextView(context);
        icon.setText(glyph);
        icon.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        icon.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        icon.setGravity(Gravity.CENTER);
        badge.addView(icon, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        row.addView(badge, LayoutHelper.createLinear(36, 36, Gravity.TOP, 0, 2, 14, 0));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView name = new TextView(context);
        name.setText(LocaleController.getString(titleRes));
        name.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        name.setTypeface(AndroidUtilities.bold());
        texts.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView text = new TextView(context);
        text.setText(LocaleController.getString(textRes));
        text.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        texts.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));

        row.addView(texts, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        parent.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, 22, 0, 22, 16));
    }
}
