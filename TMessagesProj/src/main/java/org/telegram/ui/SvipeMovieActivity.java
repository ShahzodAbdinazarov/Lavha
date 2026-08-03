package org.telegram.ui;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.svipe.SvipeDiscover;
import org.telegram.svipe.SvipeMovies;
import org.telegram.svipe.video.SvipeRefResolver;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Cells.SvipeWideVideoCell;
import org.telegram.ui.Components.ShareAlert;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ProfileActionsView;
import org.telegram.ui.Components.RecyclerListView;

import java.util.Locale;

/**
 * MovieProfile — the film page, and the exact structural twin of {@link MusicSongActivity}.
 *
 * <p>A film is a canonical work with many Telegram COPIES, so this page has the same job the song
 * page has: show the work, let the user pick which copy they watch, and remember that choice as a
 * vote (backend {@code POST /v1/movies/{id}/default}). Hence the two pinned tabs — "Variantlar" (the
 * copies) and "Aktyorlar" (the cast) — instead of the song page's single one.
 *
 * <p>The cover is the poster copy's Telegram thumbnail, resolved over MTProto exactly like song
 * artwork. There is no external poster URL anywhere in this feature, by design.
 */
public class SvipeMovieActivity extends ProfileStyleActivity {

    private static final int TAB_VERSIONS = 0;
    private static final int TAB_ACTORS = 1;

    private final long movieId;
    private final String initialTitle;

    private ListAdapter adapter;
    private SvipeMovies.MovieDetail detail;
    private int currentTab = TAB_VERSIONS;
    private boolean setInFlight;

    public SvipeMovieActivity(long movieId, String initialTitle) {
        this.movieId = movieId;
        this.initialTitle = initialTitle;
    }

    public SvipeMovieActivity(SvipeMovies.Movie movie) {
        this(movie.id, movie.title);
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        avatarDrawable.setProfile(true);
        if (initialTitle != null) {
            setProfileTitle(initialTitle);
            avatarDrawable.setInfo(movieId, initialTitle, null);
            avatarImage.getImageReceiver().setImageBitmap(avatarDrawable);
        }
        load();
        return view;
    }

    @Override
    protected RecyclerListView.SelectionAdapter createListAdapter() {
        return adapter = new ListAdapter();
    }

    @Override
    protected CharSequence getTabTitle() {
        return LocaleController.getString(R.string.SvipeMovieVersions);
    }

    @Override
    protected CharSequence[] getTabTitles() {
        return new CharSequence[]{
                LocaleController.getString(R.string.SvipeMovieVersions),
                LocaleController.getString(R.string.SvipeMovieActors),
        };
    }

    @Override
    protected void onTabSelected(int index) {
        currentTab = index;
    }

    @Override
    protected void onCreateActions(ProfileActionsView view) {
        view.addAction(ProfileActionsView.ActionButton.PLAY, ProfileActionsView.KEY_PLAY);
        view.addAction(ProfileActionsView.ActionButton.SHARE, ProfileActionsView.KEY_SHARE);
    }

    @Override
    protected void onActionClick(int key, float x, float y) {
        if (key == ProfileActionsView.KEY_PLAY) {
            watch(defaultVersion());
        } else if (key == ProfileActionsView.KEY_SHARE) {
            // Share the DEFAULT copy's public post: a film has no owned svipe.uz link of its own yet
            // (share_links keys reels and songs), and a t.me link is the one thing every recipient can
            // open whether or not they have the app.
            SvipeMovies.Version v = defaultVersion();
            if (v != null && v.username != null && getParentActivity() != null) {
                final String link = "https://t.me/" + v.username + "/" + v.messageId;
                showDialog(new ShareAlert(getParentActivity(), null, link, false, link, false));
            }
        }
    }

    @Override
    protected void onListItemClick(View view, int position) {
        if (detail == null) {
            return;
        }
        if (currentTab == TAB_ACTORS) {
            if (position >= 0 && position < detail.actors.size()) {
                SvipeMovies.Actor a = detail.actors.get(position);
                presentFragment(new SvipeActorActivity(a.id, a.name));
            }
            return;
        }
        SvipeMovies.Version v = versionAt(position);
        if (v != null) {
            watch(v);
        }
    }

    @Override
    protected boolean onListItemLongClick(View view, int position) {
        if (currentTab != TAB_VERSIONS) {
            return false;
        }
        final SvipeMovies.Version v = versionAt(position);
        if (v == null || setInFlight) {
            return false;
        }
        // Long-press pins a copy: "this dub / this encode is the one I want". It is also the crowd
        // vote that elects everyone else's default, which is why it is an explicit gesture and not a
        // side effect of merely watching.
        setInFlight = true;
        SvipeMovies.setDefault(currentAccount, movieId, v.channelId, v.messageId,
                (ok, error) -> AndroidUtilities.runOnUIThread(() -> {
                    setInFlight = false;
                    if (!ok) {
                        return;
                    }
                    for (SvipeMovies.Version other : detail.versions) {
                        other.isDefault = other == v;
                    }
                    detail.myDefault = v;
                    notifyInnerListChanged();
                    if (getParentActivity() != null) {
                        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
                        b.setMessage(LocaleController.getString(R.string.SvipeMovieDefaultSet));
                        b.setPositiveButton(LocaleController.getString(R.string.OK), null);
                        showDialog(b.create());
                    }
                }));
        return true;
    }

    // ---------------------------------------------------------------------------------------------

    private SvipeMovies.Version versionAt(int position) {
        if (detail == null || position < 0 || position >= detail.versions.size()) {
            return null;
        }
        return detail.versions.get(position);
    }

    /** The copy this film plays: the user's pinned one, else the server's crowd/quality default. */
    private SvipeMovies.Version defaultVersion() {
        if (detail == null) {
            return null;
        }
        if (detail.myDefault != null) {
            return detail.myDefault;
        }
        for (SvipeMovies.Version v : detail.versions) {
            if (v.isDefault) {
                return v;
            }
        }
        return detail.versions.isEmpty() ? null : detail.versions.get(0);
    }

    private void watch(SvipeMovies.Version v) {
        if (v == null || v.username == null) {
            return;
        }
        presentFragment(new SvipeWatchActivity(v.toItem()));
    }

    private void load() {
        SvipeMovies.movie(currentAccount, movieId, (d, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (d == null || d.movie == null) {
                return;
            }
            detail = d;
            setProfileTitle(d.movie.title);
            setProfileSubtitle(subtitle(d));
            bindPoster(d.movie);
            notifyListChanged();
            notifyInnerListChanged();
        }));
    }

    private static String subtitle(SvipeMovies.MovieDetail d) {
        StringBuilder sb = new StringBuilder();
        if (d.movie.year > 0) {
            sb.append(d.movie.year);
        }
        if (d.movie.country != null && !d.movie.country.isEmpty()) {
            if (sb.length() > 0) sb.append(" • ");
            sb.append(d.movie.country);
        }
        if (d.movie.rating() > 0) {
            if (sb.length() > 0) sb.append(" • ");
            sb.append(String.format(Locale.US, "★ %.1f", d.movie.rating()));
        }
        final String genre = SvipeMovies.genreLabel(d.movie);
        if (!genre.isEmpty()) {
            if (sb.length() > 0) sb.append(" • ");
            sb.append(genre);
        }
        return sb.toString();
    }

    /**
     * Resolve the poster copy over MTProto and paint its thumbnail as the cover. Failure is silent:
     * the initials tile the header already drew stays, which is the same fallback the song page uses
     * when a track has no artwork.
     */
    private void bindPoster(SvipeMovies.Movie movie) {
        if (!movie.hasPoster()) {
            return;
        }
        SvipeDiscover.Item ref = new SvipeDiscover.Item();
        ref.channelId = movie.posterChannelId;
        ref.messageId = movie.posterMessageId;
        ref.username = movie.posterUsername;
        final SvipeRefResolver.VideoRef vr = SvipeRefResolver.VideoRef.of(ref);
        SvipeRefResolver.resolve(currentAccount, vr, () -> AndroidUtilities.runOnUIThread(() -> {
            MessageObject mo = vr.message();
            if (mo != null) {
                SvipeWideVideoCell.bindThumb(avatarImage, mo, true);
                onAvatarChanged();
            }
        }), null);
    }

    private String versionStatus(SvipeMovies.Version v) {
        StringBuilder sb = new StringBuilder();
        if (v.quality != null && !v.quality.isEmpty()) {
            sb.append(v.quality);
        } else if (v.height > 0) {
            sb.append(v.height).append("p");
        }
        if (v.language != null && !v.language.isEmpty()) {
            if (sb.length() > 0) sb.append(" • ");
            sb.append(v.language);
        }
        if (v.votes > 0) {
            if (sb.length() > 0) sb.append(" • ");
            sb.append("👤 ").append(v.votes);
        }
        if (v.isDefault) {
            if (sb.length() > 0) sb.append(" • ");
            sb.append("✓");
        }
        return sb.toString();
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            if (detail == null) {
                return 0;
            }
            return currentTab == TAB_ACTORS ? detail.actors.size() : detail.versions.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            View view = new UserCell(parent.getContext(), 6, 0, false, getResourceProvider());
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final UserCell cell = (UserCell) holder.itemView;
            final boolean divider = position != getItemCount() - 1;
            if (currentTab == TAB_ACTORS) {
                SvipeMovies.Actor a = detail.actors.get(position);
                AvatarDrawable avatar = new AvatarDrawable();
                avatar.setInfo(a.id, a.name, null);
                cell.setData(null, a.name,
                        a.movieCount > 0
                                ? a.movieCount + " " + LocaleController.getString(R.string.SvipeActorFilmography)
                                : LocaleController.getString(R.string.SvipeMovieCast),
                        0, divider);
                cell.avatarImageView.setImageDrawable(avatar);
                return;
            }
            SvipeMovies.Version v = detail.versions.get(position);
            AvatarDrawable avatar = new AvatarDrawable();
            final String name = v.channelTitle != null && !v.channelTitle.isEmpty()
                    ? v.channelTitle
                    : ("@" + (v.username == null ? "" : v.username));
            avatar.setInfo(v.channelId, name, null);
            cell.setData(null, name, versionStatus(v), 0, divider);
            cell.avatarImageView.setImageDrawable(avatar);
        }
    }
}
