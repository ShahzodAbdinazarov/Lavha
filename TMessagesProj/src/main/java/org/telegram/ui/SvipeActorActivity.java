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
import org.telegram.ui.Cells.GraySectionCell;
import org.telegram.ui.Cells.SvipeWideVideoCell;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ActorProfile — a performer and their filmography. The twin of {@link MusicArtistActivity}, down to
 * the single pinned tab of works.
 *
 * <p>The name is the UZBEK spelling the uploading channel wrote ("Mark Raylens"), not a canonical
 * romanisation, because that is the only cast source that exists for this corpus and it is also the
 * spelling our users read. Two channels spelling a name differently produce two actors until an alias
 * merges them — the same trade the music layer makes for artists.
 */
public class SvipeActorActivity extends ProfileStyleActivity {

    private static final int PAGE_SIZE = 30;

    private final long actorId;
    private final String initialName;

    private ListAdapter adapter;
    private SvipeMovies.Actor actor;
    private final List<SvipeMovies.Movie> movies = new ArrayList<>();
    /**
     * The rest of the filmography, from the global index: films this performer is credited with that we
     * cannot play. Drawn under the ones we can, so the page is the performer rather than our slice of
     * them. Only the ones we could actually go and fetch are tappable.
     */
    private final List<SvipeMovies.Suggestion> alsoIn = new ArrayList<>();
    private final java.util.HashSet<String> requested = new java.util.HashSet<>();
    private Integer nextOffset = 0;
    private boolean loading;

    public SvipeActorActivity(long actorId, String initialName) {
        this.actorId = actorId;
        this.initialName = initialName;
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        avatarDrawable.setProfile(true);
        if (initialName != null) {
            setProfileTitle(initialName);
            avatarDrawable.setInfo(actorId, initialName, null);
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
        return LocaleController.getString(R.string.SvipeActorFilmography);
    }

    @Override
    protected boolean hasProfileActions() {
        // No play / share / like on a person: every action here belongs to one of their films.
        return false;
    }

    @Override
    protected void onListItemClick(View view, int position) {
        if (position >= 0 && position < movies.size()) {
            SvipeMovies.Movie m = movies.get(position);
            // A film from a filmography opens the same way one from a shelf does: it plays, on the
            // watch page, which is where the cast and the other copies live too.
            if (m.hasPoster()) {
                presentFragment(new SvipeWatchActivity(SvipeMovies.PosterRef.of(m)));
            }
            // Endless paging: the filmography of a prolific actor can outrun one page. Only films we
            // host page — the tail arrives whole, with the last page.
            if (position >= movies.size() - 3) {
                load();
            }
            return;
        }
        int idx = position - movies.size() - 1;   // past the tail's own header
        if (idx >= 0 && idx < alsoIn.size()) {
            requestFilm(alsoIn.get(idx));
        }
    }

    /**
     * Ask for a film we don't have. Only films with a source we can fetch from are tappable, so a tap
     * never promises something nothing can deliver. The row goes muted immediately and stays that way:
     * a film is a gigabyte and arrives on the worker's schedule, not while the page is open.
     */
    private void requestFilm(SvipeMovies.Suggestion s) {
        if (s == null || !s.requestable || !requested.add(s.title + "|" + s.year)) {
            return;
        }
        notifyInnerListChanged();
        SvipeMovies.requestFilm(currentAccount, s, (ok, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (ok) {
                BulletinFactory.of(this).createSimpleBulletin(
                        R.raw.done, LocaleController.getString(R.string.SvipeMovieRequested)).show();
            } else {
                requested.remove(s.title + "|" + s.year);
                notifyInnerListChanged();
            }
        }));
    }

    private void load() {
        if (loading || nextOffset == null) {
            return;
        }
        loading = true;
        final int offset = nextOffset;
        SvipeMovies.actor(currentAccount, actorId, offset, PAGE_SIZE,
                (page, error) -> AndroidUtilities.runOnUIThread(() -> {
                    loading = false;
                    if (page == null) {
                        return;
                    }
                    if (offset == 0) {
                        actor = page.actor;
                        setProfileTitle(actor.name);
                        setProfileSubtitle(actor.movieCount > 0
                                ? actor.movieCount + " " + LocaleController.getString(R.string.SvipeActorFilmography)
                                : "");
                        bindPhoto(actor);
                        movies.clear();
                    }
                    movies.addAll(page.movies);
                    alsoIn.clear();
                    alsoIn.addAll(page.alsoIn);
                    nextOffset = page.nextOffset;
                    notifyListChanged();
                    notifyInnerListChanged();
                }));
    }

    /** The actor's avatar is a poster from one of their films, resolved over MTProto like song art. */
    private void bindPhoto(SvipeMovies.Actor a) {
        if (a == null || a.artChannelId == 0 || a.artUsername == null) {
            return;
        }
        SvipeDiscover.Item ref = new SvipeDiscover.Item();
        ref.channelId = a.artChannelId;
        ref.messageId = a.artMessageId;
        ref.username = a.artUsername;
        final SvipeRefResolver.VideoRef vr = SvipeRefResolver.VideoRef.of(ref);
        SvipeRefResolver.resolve(currentAccount, vr, () -> AndroidUtilities.runOnUIThread(() -> {
            MessageObject mo = vr.message();
            if (mo != null) {
                SvipeWideVideoCell.bindThumb(avatarImage, mo, true);
                onAvatarChanged();
            }
        }), null);
    }

    private static String movieStatus(SvipeMovies.Movie m) {
        StringBuilder sb = new StringBuilder();
        if (m.year > 0) {
            sb.append(m.year);
        }
        if (m.rating() > 0) {
            if (sb.length() > 0) sb.append(" • ");
            sb.append(String.format(Locale.US, "★ %.1f", m.rating()));
        }
        if (sb.length() == 0) {
            sb.append(SvipeMovies.genreLabel(m));
        }
        return sb.toString();
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private static final int VIEW_FILM = 0;
        private static final int VIEW_MORE_HEADER = 1;
        private static final int VIEW_MORE = 2;

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() != VIEW_MORE_HEADER;
        }

        @Override
        public int getItemCount() {
            return movies.size() + (alsoIn.isEmpty() ? 0 : alsoIn.size() + 1);
        }

        @Override
        public int getItemViewType(int position) {
            if (position < movies.size()) {
                return VIEW_FILM;
            }
            return position == movies.size() ? VIEW_MORE_HEADER : VIEW_MORE;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            final View view;
            if (viewType == VIEW_MORE_HEADER) {
                view = new GraySectionCell(parent.getContext(), getResourceProvider());
            } else {
                view = new UserCell(parent.getContext(), 6, 0, false, getResourceProvider());
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            int type = holder.getItemViewType();
            if (type == VIEW_MORE_HEADER) {
                ((GraySectionCell) holder.itemView).setText(
                        LocaleController.getString(R.string.SvipeActorMoreFilms));
                return;
            }
            UserCell cell = (UserCell) holder.itemView;
            if (type == VIEW_MORE) {
                SvipeMovies.Suggestion s = alsoIn.get(position - movies.size() - 1);
                AvatarDrawable avatar = new AvatarDrawable();
                avatar.setInfo(s.title.hashCode(), s.title, null);
                cell.setData(null, s.title, s.year > 0 ? String.valueOf(s.year) : "", 0,
                        position != getItemCount() - 1);
                cell.avatarImageView.setImageDrawable(avatar);
                // Muted unless we could actually fetch it — and once asked for, muted again, because
                // asking twice does nothing and a row that still looks tappable says otherwise.
                boolean actionable = s.requestable && !requested.contains(s.title + "|" + s.year);
                cell.setAlpha(actionable ? 1f : 0.5f);
                return;
            }
            SvipeMovies.Movie m = movies.get(position);
            AvatarDrawable avatar = new AvatarDrawable();
            avatar.setInfo(m.id, m.title, null);
            cell.setAlpha(1f);
            // A divider under every film but the last, where the tail's own header takes over.
            cell.setData(null, m.title, movieStatus(m), 0, position != movies.size() - 1);
            cell.avatarImageView.setImageDrawable(avatar);
        }
    }
}
