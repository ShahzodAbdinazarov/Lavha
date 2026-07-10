package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.svipe.SvipeMusic;
import org.telegram.svipe.SvipeMusicQueue;
import org.telegram.svipe.SvipeMusicResolver;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Canonical song detail + version picker (the Zona-style "one song, many versions" card). Lists every
 * indexed copy of the song; the user plays any version inline and taps "Menga default qil" to pin it
 * as their own default (a crowd vote — the per-version count shows how many users picked it). Tapping
 * an artist opens {@link MusicArtistActivity}.
 */
public class MusicSongActivity extends BaseFragment {

    private final long songId;
    private final String initialTitle;

    private LinearLayout container;
    private FrameLayout stateOverlay;
    private SvipeMusic.SongDetail detail;
    private boolean loading;
    private boolean setInFlight;

    public MusicSongActivity(long songId, String initialTitle) {
        this.songId = songId;
        this.initialTitle = initialTitle;
    }

    private static int dp(float v) {
        return AndroidUtilities.dp(v);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(initialTitle != null ? initialTitle : "");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));

        ScrollView scroll = new ScrollView(context);
        container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(container, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        stateOverlay = new FrameLayout(context);
        root.addView(stateOverlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        fragmentView = root;
        load();
        return root;
    }

    private void load() {
        loading = true;
        showProgress(true);
        SvipeMusic.song(currentAccount, songId, (song, error) -> {
            loading = false;
            showProgress(false);
            if (song == null) {
                showMessage("Yuklashda xatolik");
                return;
            }
            detail = song;
            if (initialTitle == null || initialTitle.isEmpty()) {
                actionBar.setTitle(song.title);
            }
            rebuild();
        });
    }

    private void showProgress(boolean show) {
        if (stateOverlay == null) return;
        stateOverlay.removeAllViews();
        if (show) {
            RadialProgressView p = new RadialProgressView(getParentActivity());
            p.setSize(dp(30));
            stateOverlay.addView(p, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        }
    }

    private void showMessage(String msg) {
        if (stateOverlay == null) return;
        stateOverlay.removeAllViews();
        TextView tv = new TextView(getParentActivity());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        tv.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
        tv.setText(msg);
        stateOverlay.addView(tv, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
    }

    private void rebuild() {
        if (container == null || detail == null) return;
        container.removeAllViews();
        Context context = container.getContext();

        // ---- Header: title (+variant) + artist chips ----
        String title = detail.title != null && !detail.title.isEmpty() ? detail.title : getString(R.string.AudioUnknownTitle);
        if (detail.variantLabel != null && !detail.variantLabel.isEmpty()) {
            title = title + " (" + detail.variantLabel + ")";
        }
        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        container.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 18, 20, 4));

        if (!detail.artists.isEmpty()) {
            LinearLayout chips = new LinearLayout(context);
            chips.setOrientation(LinearLayout.HORIZONTAL);
            for (SvipeMusic.Artist a : detail.artists) {
                TextView chip = new TextView(context);
                chip.setText(a.name);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                chip.setTextColor(getThemedColor(Theme.key_chats_actionBackground));
                chip.setPadding(dp(2), dp(2), dp(10), dp(2));
                chip.setOnClickListener(v -> presentFragment(new MusicArtistActivity(a.id, a.name)));
                chips.addView(chip);
            }
            container.addView(chips, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 18, 0, 20, 10));
        }

        // ---- Versions header ----
        TextView vh = new TextView(context);
        vh.setText("Versiyalar (" + detail.versions.size() + ")");
        vh.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        vh.setTypeface(AndroidUtilities.bold());
        vh.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
        container.addView(vh, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 8, 20, 4));

        for (SvipeMusic.SongVersion v : detail.versions) {
            container.addView(buildVersionRow(context, v));
        }
    }

    private View buildVersionRow(Context context, SvipeMusic.SongVersion v) {
        FrameLayout row = new FrameLayout(context);
        row.setPadding(dp(16), dp(8), dp(12), dp(8));
        row.setBackground(Theme.getSelectorDrawable(false));

        ImageView play = new ImageView(context);
        play.setScaleType(ImageView.ScaleType.CENTER);
        play.setImageResource(isPlaying(v) ? R.drawable.ic_pause : R.drawable.ic_play);
        play.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_chats_actionBackground), PorterDuff.Mode.MULTIPLY));
        play.setBackground(Theme.createCircleDrawable(dp(40), getThemedColor(Theme.key_windowBackgroundGray)));
        play.setOnClickListener(view -> playVersion(v));
        row.addView(play, LayoutHelper.createFrame(40, 40, Gravity.LEFT | Gravity.CENTER_VERTICAL));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 56, 0, 96, 0));

        TextView line1 = new TextView(context);
        line1.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        line1.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        line1.setSingleLine(true);
        line1.setEllipsize(TextUtils.TruncateAt.END);
        String perf = v.performer != null && !v.performer.isEmpty() ? v.performer : (v.username != null ? "@" + v.username : "");
        line1.setText(quality(v) + (perf.isEmpty() ? "" : "  ·  " + perf));
        texts.addView(line1);

        TextView line2 = new TextView(context);
        line2.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        line2.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
        line2.setSingleLine(true);
        String votes = v.voteCount == 1 ? "1 kishi tanlagan" : v.voteCount + " kishi tanlagan";
        line2.setText(v.isDefault ? (votes + "  ·  standart") : votes);
        texts.addView(line2, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));

        // Right: "my default" state / set-as-default action.
        TextView action = new TextView(context);
        action.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(10), dp(6), dp(10), dp(6));
        if (v.isMyDefault) {
            action.setText("✓ Meniki");
            action.setTextColor(getThemedColor(Theme.key_chats_actionBackground));
        } else {
            action.setText("Tanlash");
            action.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
            action.setBackground(Theme.createRoundRectDrawable(dp(14), getThemedColor(Theme.key_windowBackgroundGray)));
            action.setOnClickListener(view -> setMyDefault(v));
        }
        row.addView(action, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 4, 0));
        return row;
    }

    private String quality(SvipeMusic.Track t) {
        if (t.size > 0 && t.durationS > 0) {
            long kbps = Math.round((t.size * 8.0) / t.durationS / 1000.0);
            return kbps + " kbps";
        }
        if (t.size > 0) {
            return String.format("%.1f MB", t.size / (1024.0 * 1024.0));
        }
        return "audio";
    }

    private boolean isPlaying(SvipeMusic.Track t) {
        SvipeMusicQueue active = SvipeMusicQueue.getActive();
        if (active == null) return false;
        MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
        if (mo == null || MediaController.getInstance().isMessagePaused()) return false;
        SvipeMusic.Track pt = active.trackFor(mo);
        return pt != null && pt.key().equals(t.key());
    }

    private void playVersion(SvipeMusic.SongVersion v) {
        ArrayList<SvipeMusic.Track> one = new ArrayList<>();
        one.add(v);
        SvipeMusicResolver.resolve(currentAccount, one, resolved -> {
            HashMap<String, TLRPC.Message> map = new HashMap<>(resolved);
            SvipeMusicQueue queue = new SvipeMusicQueue(currentAccount, SvipeMusicQueue.SOURCE_SECTION,
                detail != null ? detail.title : "", false);
            queue.appendResolved(one, map);
            MessageObject first = queue.messageForKey(v.key());
            if (first == null && !queue.list.isEmpty()) {
                first = queue.list.get(0);
            }
            if (first != null) {
                queue.play(first);
            }
            rebuild();
        });
    }

    private void setMyDefault(SvipeMusic.SongVersion v) {
        if (setInFlight) return;
        setInFlight = true;
        SvipeMusic.setDefault(currentAccount, songId, v.channelId, v.messageId, (ack, error) -> {
            setInFlight = false;
            if (error == null) {
                load();  // reload to reflect new my-default + vote counts
            }
        });
    }
}
