package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.svipe.SvipeMusic;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;

/**
 * Artist page: the artist's name + every canonical song they perform on, each opening the song's
 * version picker ({@link MusicSongActivity}). Reached by tapping an artist chip on a song.
 */
public class MusicArtistActivity extends BaseFragment {

    private final long artistId;
    private final String initialName;

    private LinearLayout container;
    private FrameLayout stateOverlay;

    public MusicArtistActivity(long artistId, String initialName) {
        this.artistId = artistId;
        this.initialName = initialName;
    }

    private static int dp(float v) {
        return AndroidUtilities.dp(v);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(initialName != null ? initialName : "");
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
        showProgress(true);
        SvipeMusic.artist(currentAccount, artistId, 0, 50, (page, error) -> {
            showProgress(false);
            if (page == null) {
                showMessage("Yuklashda xatolik");
                return;
            }
            if (page.artist != null && page.artist.name != null && !page.artist.name.isEmpty()) {
                actionBar.setTitle(page.artist.name);
            }
            rebuild(page);
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

    private void rebuild(SvipeMusic.ArtistPage page) {
        if (container == null) return;
        container.removeAllViews();
        Context context = container.getContext();

        TextView header = new TextView(context);
        int n = page.songCount > 0 ? page.songCount : page.songs.size();
        header.setText(n == 1 ? "1 qo'shiq" : n + " qo'shiq");
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        header.setTypeface(AndroidUtilities.bold());
        header.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
        container.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 16, 20, 6));

        for (SvipeMusic.Song s : page.songs) {
            container.addView(buildSongRow(context, s));
        }
    }

    private View buildSongRow(Context context, SvipeMusic.Song s) {
        FrameLayout row = new FrameLayout(context);
        row.setPadding(dp(16), dp(8), dp(12), dp(8));
        row.setBackground(Theme.getSelectorDrawable(false));
        row.setOnClickListener(v -> presentFragment(new MusicSongActivity(s.id, s.title)));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 0, 0, 56, 0));

        String title = s.title != null && !s.title.isEmpty() ? s.title : getString(R.string.AudioUnknownTitle);
        if (s.variantLabel != null && !s.variantLabel.isEmpty()) {
            title = title + " (" + s.variantLabel + ")";
        }
        TextView line1 = new TextView(context);
        line1.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        line1.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        line1.setSingleLine(true);
        line1.setEllipsize(TextUtils.TruncateAt.END);
        line1.setText(title);
        texts.addView(line1);

        TextView line2 = new TextView(context);
        line2.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        line2.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
        line2.setSingleLine(true);
        line2.setEllipsize(TextUtils.TruncateAt.END);
        String artistLine = s.artistLine();
        line2.setText(s.versionCount > 1 ? (artistLine + "  ·  " + s.versionCount + " versiya") : artistLine);
        texts.addView(line2, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));

        TextView chevron = new TextView(context);
        chevron.setText("›");
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        chevron.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
        chevron.setGravity(Gravity.CENTER);
        row.addView(chevron, LayoutHelper.createFrame(40, LayoutHelper.MATCH_PARENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        return row;
    }
}
