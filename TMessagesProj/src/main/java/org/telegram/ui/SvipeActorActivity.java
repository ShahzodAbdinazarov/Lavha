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
import org.telegram.ui.Cells.SvipeWideVideoCell;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.Components.AvatarDrawable;
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
            presentFragment(new SvipeMovieActivity(m));
        }
        // Endless paging: the filmography of a prolific actor can outrun one page.
        if (position >= movies.size() - 3) {
            load();
        }
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
        if (sb.length() == 0 && !m.genres.isEmpty()) {
            sb.append(m.genres.get(0));
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
            return movies.size();
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
            SvipeMovies.Movie m = movies.get(position);
            UserCell cell = (UserCell) holder.itemView;
            AvatarDrawable avatar = new AvatarDrawable();
            avatar.setInfo(m.id, m.title, null);
            cell.setData(null, m.title, movieStatus(m), 0, position != getItemCount() - 1);
            cell.avatarImageView.setImageDrawable(avatar);
        }
    }
}
