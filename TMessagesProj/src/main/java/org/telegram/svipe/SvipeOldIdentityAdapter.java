package org.telegram.svipe;

import android.content.Context;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.UserObject;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

/**
 * The list behind the profile's "Old profiles" / "Old numbers" tabs. Deliberately the app's own
 * {@link UserCell} — the same row the contact list is made of — so these read as people rather than
 * as a report: avatar, name, and underneath the number this row is about.
 *
 * Rows whose account we could not reach carry a deleted user and are not enabled, which is what
 * makes them render as Telegram's "Deleted Account" and refuse to be tapped.
 */
public class SvipeOldIdentityAdapter extends RecyclerListView.SelectionAdapter {

    private final Context context;
    private final ArrayList<SvipeOldIdentity.Item> items = new ArrayList<>();

    public SvipeOldIdentityAdapter(Context context) {
        this.context = context;
    }

    public void setItems(List<SvipeOldIdentity.Item> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    public SvipeOldIdentity.Item getItem(int position) {
        return position >= 0 && position < items.size() ? items.get(position) : null;
    }

    @Override
    public boolean isEnabled(RecyclerView.ViewHolder holder) {
        SvipeOldIdentity.Item item = getItem(holder.getAdapterPosition());
        return item != null && item.openable;
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
        UserCell cell = new UserCell(context, 6, 0, false);
        cell.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT));
        return new RecyclerListView.Holder(cell);
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        SvipeOldIdentity.Item item = items.get(position);
        UserCell cell = (UserCell) holder.itemView;
        // getUserName gives Telegram's own "Deleted Account" for the stand-ins, so nothing here has
        // to invent a wording for an account that is gone.
        cell.setData(item.user, UserObject.getUserName(item.user), item.displayPhone(), 0,
                position < items.size() - 1);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }
}
