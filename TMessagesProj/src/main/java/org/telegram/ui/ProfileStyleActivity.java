package org.telegram.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.PorterDuff;
import android.graphics.Shader;
import android.graphics.Paint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.core.view.NestedScrollingParent3;
import androidx.core.view.NestedScrollingParentHelper;
import androidx.core.view.ViewCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.math.MathUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ProfileActionsView;
import org.telegram.ui.Components.ProfileGooeyView;
import org.telegram.ui.Components.ScrollSlidingTextTabStrip;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * The native profile screen's header, reusable by fragments that have no Telegram peer.
 *
 * <p>{@link ProfileActivity} itself cannot be subclassed for this: its {@code onFragmentCreate()}
 * returns false unless a real user/chat is resolvable, and its private {@code updateProfileData()}
 * re-derives the name, avatar and status from that peer on ~10 notifications, clobbering anything a
 * subclass sets. So instead this fragment reproduces the header the only way that stays truthful to
 * it — by reusing the very classes ProfileActivity composes ({@link ProfileActivity.AvatarImageView},
 * {@link AvatarDrawable}, {@link SimpleTextView}, {@link ProfileActionsView}) and by porting its
 * geometry verbatim. Every constant, lerp and formula below is copied from ProfileActivity's
 * #needLayout / #checkListViewScroll / #setAvatarExpandProgress so the behaviour matches exactly.
 *
 * <p>Supports both header states: scrolling up collapses the avatar into the action bar, and pulling
 * down past the top expands the avatar — it grows as a circle (96dp -> 138dp) and then, past a third
 * of the way, latches and morphs into a full-bleed square with the name and status in white over it.
 * The expand is only offered when the avatar is a real image ({@code hasNotThumb()}); a letters-only
 * placeholder has nothing to enlarge, exactly as in a real profile.
 *
 * <p>Deliberately dropped, because all of it is peer-only: the multi-photo gallery pager (a song has
 * one cover, so the avatar itself expands and no pager is needed), stories, gifts, the star-gift emoji
 * pattern and peer colour gradients.
 *
 * <p>Subclasses supply an adapter and header content; the list reserves nothing — the header is drawn
 * above the list, which is simply top-padded by {@link #getHeaderExtraHeight()}.
 */
public abstract class ProfileStyleActivity extends BaseFragment {

    protected RecyclerListView listView;
    protected LinearLayoutManager layoutManager;

    private FrameLayout frameLayout;
    private TopView topView;
    private ScrimView scrimView;
    private CoverBlurView coverBlurView;
    private FrameLayout avatarContainer2;
    private FrameLayout avatarContainer;
    private ProfileGooeyView avatarGooey;
    private MediaSectionView mediaSection;
    private OuterAdapter outerAdapter;
    private boolean mediaSectionAttached;
    private boolean mediaHeaderVisible;
    private float mediaHeaderAnimationProgress;
    private AnimatorSet headerAnimatorSet;

    protected ProfileActivity.AvatarImageView avatarImage;
    protected final AvatarDrawable avatarDrawable = new AvatarDrawable();
    protected SimpleTextView nameTextView;
    protected SimpleTextView onlineTextView;
    protected ProfileActionsView actionsView;

    /** Top of adapter item 0: equals getHeaderExtraHeight() at rest, 0 when scrolled past the header. */
    private float extraHeight;
    private float avatarScale, avatarX, avatarY, nameX, onlineX, nameY, onlineY;
    /** 0 while the header is open, 1 once the avatar has been swallowed. Drives the gooey. */
    private float pullUpProgress;
    private int btnColor;

    // Pull-down-to-expand state, mirroring ProfileActivity's fields of the same names.
    private boolean allowPullingDown;
    private boolean isPulledDown;
    private boolean justFullyExpanded;
    private boolean invalidateScroll = true;
    private boolean photoOpening;
    private float expandProgress;
    private ValueAnimator expandAnimator;
    private float currentExpandAnimatorValue, currentExpandAnimatorFracture;
    private final float[] expandAnimatorValues = new float[]{0f, 1f};
    // Release velocity of the drag, so a fast flick collapses/expands the header even short of the
    // mid-point. Mirrors ProfileActivity#listViewVelocityY.
    private android.view.VelocityTracker velocityTracker;
    private float listViewVelocityY;

    private static int dp(float v) {
        return AndroidUtilities.dp(v);
    }

    private static float dpf2(float v) {
        return AndroidUtilities.dpf2(v);
    }

    // ---- hooks for subclasses ----

    protected abstract RecyclerListView.SelectionAdapter createListAdapter();

    /** Add buttons via actionsView.addAction(...). The row is hidden entirely if nothing is added. */
    protected void onCreateActions(ProfileActionsView view) {
    }

    protected void onActionClick(int key, float x, float y) {
    }

    protected void onListItemClick(View view, int position) {
    }

    protected boolean onListItemLongClick(View view, int position) {
        return false;
    }

    /** Mirrors ProfileActivity: dp(74) of buttons, or 0 when the screen has none. */
    protected boolean hasProfileActions() {
        return true;
    }

    // ---- header content ----

    // SimpleTextView#setText only re-measures while the view has never been laid out; afterwards it
    // rebuilds its layout in place and keeps the old measured height. Both of these arrive from a
    // network callback, i.e. after layout, so without an explicit requestLayout a view that was first
    // measured empty stays at padding height and clips its text away.

    protected void setProfileTitle(CharSequence title) {
        if (nameTextView != null) {
            nameTextView.setText(title);
            nameTextView.requestLayout();
            needLayout(false);
        }
    }

    protected void setProfileSubtitle(CharSequence subtitle) {
        if (onlineTextView != null) {
            onlineTextView.setText(subtitle);
            onlineTextView.requestLayout();
            needLayout(false);
        }
    }

    /** Call after swapping the avatar image in, so the pull-down expand can enable itself. */
    protected void onAvatarChanged() {
        needLayout(false);
    }

    /**
     * Rows shown in a card above the tabs — the slot a real profile fills with Description / Invite
     * Link / Add Members. Default none.
     */
    protected int getHeaderRowCount() {
        return 0;
    }

    protected View createHeaderRow(Context context) {
        return null;
    }

    protected void bindHeaderRow(View view, int position) {
    }

    protected void onHeaderRowClick(int position) {
    }

    /** Title for the single tab in the pinned strip. */
    protected abstract CharSequence getTabTitle();

    /**
     * SharedMediaLayout's shape, reduced to one tab.
     *
     * <p>This is what makes the strip pin: it is not the strip that sticks, it is that the whole
     * section is a single row of the outer list, as tall as the viewport, with the strip at its top
     * and its own list underneath. Scroll the outer list and the row reaches the top and stops; from
     * then on the inner list scrolls under a strip that simply never moved. SharedMediaLayout itself
     * (12k lines, wants a dialogId + SharedMediaPreloader + chatInfo) can't be reused, so this
     * rebuilds only that skeleton — the strip is still the same widget with its colours.
     */
    protected class MediaSectionView extends FrameLayout {
        final ScrollSlidingTextTabStrip tabStrip;
        final RecyclerListView innerListView;

        MediaSectionView(Context context) {
            super(context);

            innerListView = new RecyclerListView(context);
            innerListView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
            innerListView.setGlowColor(0);
            innerListView.setClipToPadding(false);
            // Rows pass under the strip rather than starting below it, as in the original.
            innerListView.setPadding(0, dp(66), 0, 0);
            innerListView.setSections();
            innerListView.applyPaddingToSections = false;
            innerListView.setAdapter(createListAdapter());
            innerListView.setOnItemClickListener(ProfileStyleActivity.this::onListItemClick);
            innerListView.setOnItemLongClickListener(ProfileStyleActivity.this::onListItemLongClick);
            addView(innerListView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            tabStrip = new ScrollSlidingTextTabStrip(context, getResourceProvider());
            tabStrip.setColors(Theme.key_profile_tabSelectedLine, Theme.key_profile_tabSelectedText, Theme.key_profile_tabText, Theme.key_profile_tabSelector);
            tabStrip.setUseMinimalWidth(true);
            tabStrip.addTextTab(0, getTabTitle());
            tabStrip.finishAddingTabs();
            tabStrip.setInitialTabId(0);
            try {
                // SharedMediaLayout's own fallback for when it is handed no liquid-glass factory:
                // the same drawable off a plain colour source, without the render-node pipeline.
                final BlurredBackgroundSourceColor source = new BlurredBackgroundSourceColor();
                source.setColor(getThemedColor(Theme.key_windowBackgroundWhite));
                final BlurredBackgroundDrawable pill = new BlurredBackgroundDrawableViewFactory(source)
                        .create(tabStrip, BlurredBackgroundProviderImpl.topPanel(getResourceProvider()));
                pill.setRadius(dp(18));
                pill.setPadding(dp(6.666f));
                tabStrip.setPadding(0, dp(7), 0, dp(7));
                tabStrip.setClipToPadding(false);
                tabStrip.setBackground(null);
                tabStrip.setBlurredBackground(pill);
                tabStrip.setOpen(false);
            } catch (Throwable ignore) {
                // No pill is better than no tab.
            }
            addView(tabStrip, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 50, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 8, 0, 8));
        }

        /** A viewport tall, whatever the rows say — SharedMediaLayout#onMeasure does the same. */
        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int h = listView != null ? listView.getHeight() : 0;
            if (h == 0) {
                h = View.MeasureSpec.getSize(heightMeasureSpec);
            }
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        }
    }

    /**
     * The image PhotoViewer should show when the cover is pulled all the way open — a
     * {@link org.telegram.messenger.MediaController.PhotoEntry} over a local file. Return null to make
     * the pull stop at the expanded square.
     *
     * <p>Neither of PhotoViewer's other two doors fits a peerless cover, hence this one:
     * ProfileActivity's avatar door needs a real Telegram FileLocation (a path-based ImageLocation
     * NPEs in PhotoViewer#getFileName on {@code location.location.volume_id}), and the SearchImage door
     * only exists with the picker chrome, because the view-only path (SELECT_TYPE_NO_SELECT) casts
     * every entry to PhotoEntry unconditionally. A local file satisfies all of it — which is also how
     * CachedMediaLayout merely views photos.
     */
    protected Object getExpandedPhotoObject() {
        return null;
    }

    /**
     * ProfileActivity's photo-viewer provider, minus the peer/carousel plumbing. Anchoring the viewer
     * on avatarImage is what makes the cover grow out of the header instead of just appearing —
     * PhotoViewer animates from this rect. Returning null (as EmptyPhotoViewerProvider does) gets you
     * a plain fade instead.
     */
    private final PhotoViewer.PhotoViewerProvider photoProvider = new PhotoViewer.EmptyPhotoViewerProvider() {
        @Override
        public PhotoViewer.PlaceProviderObject getPlaceForPhoto(MessageObject messageObject, TLRPC.FileLocation fileLocation, int index, boolean needPreview, boolean closing) {
            if (avatarImage == null || avatarContainer == null) {
                return null;
            }
            final int[] coords = new int[2];
            avatarImage.getLocationInWindow(coords);
            PhotoViewer.PlaceProviderObject object = new PhotoViewer.PlaceProviderObject();
            object.viewX = coords[0];
            object.viewY = coords[1];
            object.parentView = avatarImage;
            object.imageReceiver = avatarImage.getImageReceiver();
            object.thumb = object.imageReceiver.getBitmapSafe();
            object.size = -1;
            object.radius = avatarImage.getImageReceiver().getRoundRadius(true);
            object.scale = avatarContainer.getScaleX();
            object.canEdit = false;
            object.fadeIn = avatarContainer.getScaleX() > 0.96f;
            return object;
        }

        @Override
        public void willHidePhotoViewer() {
            // PhotoViewer hides the source while it owns the image; put it back or the header goes blank.
            if (avatarImage != null) {
                avatarImage.getImageReceiver().setVisible(true, true);
            }
            photoOpening = false;
        }
    };

    /** ProfileActivity#openAvatar, minus the peer plumbing. */
    private void openAvatarPhoto() {
        if (photoOpening || getParentActivity() == null) {
            return;
        }
        final Object photo = getExpandedPhotoObject();
        if (photo == null) {
            return;
        }
        photoOpening = true;
        final ArrayList<Object> photos = new ArrayList<>();
        photos.add(photo);
        PhotoViewer.getInstance().setParentActivity(this);
        boolean opened = PhotoViewer.getInstance().openPhotoForSelect(
                photos, 0, PhotoViewer.SELECT_TYPE_NO_SELECT, false, photoProvider, null);
        if (opened) {
            // A real profile titles the viewer with the peer's name; ours uses the song/artist name
            // instead of PhotoViewer's generic "Photo".
            if (nameTextView != null && !TextUtils.isEmpty(nameTextView.getText())) {
                PhotoViewer.getInstance().setTitle(nameTextView.getText());
            }
        } else {
            photoOpening = false;
        }
    }

    /**
     * Call right after notifyDataSetChanged(). The list's resting scroll offset is derived from its
     * (very large) top padding, so rows appearing for the first time must re-anchor row 0 back to
     * getHeaderExtraHeight() — otherwise the list lays out at the padding top and the header comes up
     * fully expanded.
     */
    protected void notifyListChanged() {
        if (listView == null) {
            return;
        }
        invalidateScroll = true;
        allowPullingDown = false;
        isPulledDown = false;
        if (outerAdapter != null) {
            outerAdapter.notifyDataSetChanged();
        }
        fragmentView.requestLayout();
    }

    /**
     * ProfileActivity measures every row to size the bottom padding, so that a short list can still be
     * scrolled far enough to collapse the header. Cheap here: these lists are tens of rows at most, and
     * it only re-runs when the fragment's size changes.
     */
    private int measureListContentHeight() {
        final RecyclerView.Adapter<?> adapter = listView.getAdapter();
        if (adapter == null || listView.getMeasuredWidth() <= 0) {
            return 0;
        }
        int height = 0;
        final int ws = View.MeasureSpec.makeMeasureSpec(listView.getMeasuredWidth(), View.MeasureSpec.EXACTLY);
        final int hs = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        final int count = adapter.getItemCount();
        for (int i = 0; i < count; i++) {
            try {
                RecyclerView.ViewHolder holder = adapter.createViewHolder(listView, adapter.getItemViewType(i));
                //noinspection unchecked
                ((RecyclerView.Adapter<RecyclerView.ViewHolder>) adapter).onBindViewHolder(holder, i);
                holder.itemView.measure(ws, hs);
                height += holder.itemView.getMeasuredHeight();
            } catch (Exception ignore) {
            }
        }
        return height;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setCastShadows(false);
        actionBar.setAddToContainer(false);
        actionBar.setOccupyStatusBar(!AndroidUtilities.isTablet());
        actionBar.setBackgroundColor(Color.TRANSPARENT);
        actionBar.setItemsColor(getThemedColor(Theme.key_actionBarDefaultIcon), false);
        actionBar.setItemsBackgroundColor(getThemedColor(Theme.key_actionBarDefaultSelector), false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        frameLayout = new NestedFrameLayout(context) {
            private boolean ignoreLayout;
            private int lastMeasuredWidth, lastMeasuredHeight;
            private int listContentHeight;

            @Override
            public void requestLayout() {
                if (ignoreLayout) {
                    return;
                }
                super.requestLayout();
            }

            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                if (listView == null) {
                    return;
                }
                final int actionBarHeight = newTop();

                if (lastMeasuredWidth != getMeasuredWidth() || lastMeasuredHeight != getMeasuredHeight()) {
                    lastMeasuredWidth = getMeasuredWidth();
                    lastMeasuredHeight = getMeasuredHeight();
                    listContentHeight = measureListContentHeight();
                }

                // The room to pull the header down is real top padding, not overscroll: the list is
                // padded by a whole expanded cover (width + actions) and then scrolled so that row 0
                // rests at getHeaderExtraHeight(). Straight out of ProfileActivity#onMeasure.
                ignoreLayout = true;
                final int paddingTop = listView.getMeasuredWidth() + getActionsExtraHeight();
                // Enough empty room under the rows that row 0 can always be scrolled to the very top,
                // i.e. extraHeight can reach 0 and the collapse (and its gooey) can finish. Without it
                // the list simply runs out of scroll and the avatar freezes half-swallowed.
                //
                // ProfileActivity computes this as height - (content + headerExtra + actionBar), which
                // only works there because a profile always carries SharedMediaLayout and that counts as
                // a whole viewport of content (see its listContentHeight loop) — so its content alone
                // already covers the scroll. Ours is a handful of rows, so the padding has to cover it.
                final int paddingBottom = 0; // the section row is already a viewport tall
                final int currentPaddingTop = listView.getPaddingTop();

                View child = null;
                int pos = RecyclerView.NO_POSITION;
                for (int i = 0; i < listView.getChildCount(); i++) {
                    int p = listView.getChildAdapterPosition(listView.getChildAt(i));
                    if (p != RecyclerView.NO_POSITION) {
                        child = listView.getChildAt(i);
                        pos = p;
                        break;
                    }
                }

                boolean layout = false;
                if (invalidateScroll || currentPaddingTop != paddingTop) {
                    if (child != null && !invalidateScroll) {
                        int top = child.getTop();
                        if (pos == 0 && !allowPullingDown && top > getHeaderExtraHeight()) {
                            top = getHeaderExtraHeight();
                        }
                        layoutManager.scrollToPositionWithOffset(pos, top - paddingTop);
                    } else {
                        layoutManager.scrollToPositionWithOffset(0, getHeaderExtraHeight() - paddingTop);
                    }
                    layout = true;
                }
                invalidateScroll = false;
                if (currentPaddingTop != paddingTop || listView.getPaddingBottom() != paddingBottom) {
                    listView.setPadding(0, paddingTop, 0, paddingBottom);
                    layout = true;
                }
                if (layout) {
                    measureChildWithMargins(listView, widthMeasureSpec, 0, heightMeasureSpec, 0);
                    try {
                        listView.layout(0, actionBarHeight, listView.getMeasuredWidth(), actionBarHeight + listView.getMeasuredHeight());
                    } catch (Exception ignore) {
                    }
                }
                ignoreLayout = false;
            }

            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                checkListViewScroll();
                needLayout(false);
            }
        };
        frameLayout.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        listView = new RecyclerListView(context) {
            @Override
            public boolean onTouchEvent(android.view.MotionEvent e) {
                final int action = e.getAction();
                // Track the drag velocity so ACTION_UP can tell a fast flick from a slow drag.
                // Straight out of ProfileActivity#onTouchEvent.
                if (action == android.view.MotionEvent.ACTION_DOWN) {
                    if (velocityTracker == null) {
                        velocityTracker = android.view.VelocityTracker.obtain();
                    } else {
                        velocityTracker.clear();
                    }
                    velocityTracker.addMovement(e);
                } else if (action == android.view.MotionEvent.ACTION_MOVE) {
                    if (velocityTracker != null) {
                        velocityTracker.addMovement(e);
                        velocityTracker.computeCurrentVelocity(1000);
                        listViewVelocityY = velocityTracker.getYVelocity(e.getPointerId(e.getActionIndex()));
                    }
                } else if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                    if (velocityTracker != null) {
                        if (action == android.view.MotionEvent.ACTION_UP) {
                            velocityTracker.addMovement(e);
                            velocityTracker.computeCurrentVelocity(1000);
                            listViewVelocityY = velocityTracker.getYVelocity(e.getPointerId(e.getActionIndex()));
                        }
                        velocityTracker.recycle();
                        velocityTracker = null;
                    }
                }
                boolean result = super.onTouchEvent(e);
                // Keep pulling once the cover is already square and, the moment the drag reaches the end
                // of the padding, hand off to the photo viewer. ProfileActivity's listView does exactly
                // this — the "second pull" is not a separate gesture, it is the same drag going further.
                if (action == android.view.MotionEvent.ACTION_MOVE) {
                    final float fullExtraHeight = getMeasuredWidth() + getActionsExtraHeight();
                    if (extraHeight >= fullExtraHeight - 1) {
                        openAvatarPhoto();
                        result = false;
                    }
                }
                if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                    final View view = layoutManager.findViewByPosition(0);
                    if (view != null) {
                        if (justFullyExpanded) {
                            justFullyExpanded = false;
                            canStopFlinger = true;
                        }
                        if (allowPullingDown) {
                            // Settle to whichever state the drag ended nearest: full cover, or resting.
                            if (isPulledDown) {
                                smoothScrollBy(0, view.getTop() - getMeasuredWidth() - getActionsExtraHeight() + newTop(), CubicBezierInterpolator.EASE_OUT_QUINT);
                            } else {
                                smoothScrollBy(0, view.getTop() - getHeaderExtraHeight(), CubicBezierInterpolator.EASE_OUT_QUINT);
                            }
                        } else {
                            // Not expanding — the header is mid-COLLAPSE (avatar melting up into the
                            // punch-hole). Snap it to one end so it never rests half-swallowed: past the
                            // mid-point (or flung up) collapse the avatar down onto the actions strip,
                            // otherwise spring the header back open. Ported verbatim from
                            // ProfileActivity#onTouchEvent's else branch (the piece that was missing here).
                            final boolean hasActions = getActionsExtraHeight() > 0;
                            if (hasActions && extraHeight > 0 && (extraHeight < getHeaderExtraHeight() * 0.6f || listViewVelocityY < -1000f) && extraHeight > getActionsExtraHeight() * .6f) {
                                smoothScrollBy(0, (int) (extraHeight - getActionsExtraHeight()), CubicBezierInterpolator.EASE_OUT_QUINT);
                            } else if (hasActions && extraHeight > 0 && extraHeight < getActionsExtraHeight() * .6f) {
                                smoothScrollBy(0, (int) (getActionsExtraHeight() - extraHeight), CubicBezierInterpolator.EASE_OUT_QUINT);
                            } else if (!hasActions && extraHeight > 0 && listViewVelocityY < -1000f) {
                                smoothScrollBy(0, (int) extraHeight, CubicBezierInterpolator.EASE_OUT_QUINT);
                            } else if (extraHeight > 0) {
                                smoothScrollBy(0, view.getTop() - getHeaderExtraHeight(), CubicBezierInterpolator.EASE_OUT_QUINT);
                            }
                        }
                    }
                }
                return result;
            }
        };
        // Lets the list be dragged past its top so the avatar can expand. Ported from ProfileActivity.
        layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false) {
            @Override
            public int scrollVerticallyBy(int dy, RecyclerView.Recycler recycler, RecyclerView.State state) {
                final View view = findViewByPosition(0);
                if (view != null) {
                    final int canScroll = view.getTop() - getHeaderExtraHeight();
                    if (!allowPullingDown && canScroll > dy) {
                        dy = canScroll;
                        if (canExpandAvatar()) {
                            allowPullingDown = true;
                        }
                    } else if (allowPullingDown) {
                        if (dy >= canScroll) {
                            dy = canScroll;
                            allowPullingDown = false;
                        } else if (listView.getScrollState() == RecyclerListView.SCROLL_STATE_DRAGGING) {
                            if (!isPulledDown) {
                                dy /= 2; // rubber-band while dragging
                            }
                        }
                    }
                }
                if (justFullyExpanded && !listView.isFlingerWorking()) {
                    return 0;
                }
                return super.scrollVerticallyBy(dy, recycler, state);
            }
        };
        layoutManager.mIgnoreTopPadding = false;
        listView.setLayoutManager(layoutManager);
        // The rounded white cards under the header are not drawn by the cells — RecyclerListView groups
        // consecutive rows into them itself. ProfileActivity turns them on with exactly these three lines.
        listView.setSections();
        listView.applyPaddingToSections = false;
        listView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        listView.setGlowColor(0);
        listView.setClipToPadding(false);
        // Corrected against the real measured width in onMeasure; this keeps the very first frame right.
        final int initialPaddingTop = AndroidUtilities.displaySize.x + getActionsExtraHeight();
        listView.setPadding(0, initialPaddingTop, 0, 0);
        listView.setAdapter(outerAdapter = new OuterAdapter());
        listView.setOnItemClickListener((view, position) -> {
            if (position >= 1 && position <= getHeaderRowCount()) {
                onHeaderRowClick(position - 1);
            }
        });
        layoutManager.scrollToPositionWithOffset(0, getHeaderExtraHeight() - initialPaddingTop);
        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                checkListViewScroll();
            }
        });
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT));

        // Drawn after listView so the header band covers rows scrolling underneath it.
        topView = new TopView(context);
        topView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        frameLayout.addView(topView);

        avatarContainer2 = new FrameLayout(context);
        frameLayout.addView(avatarContainer2, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.START));

        // Wrapped in the gooey view exactly as ProfileActivity does — it is what melts the avatar away
        // as the header collapses. It drives itself off avatarContainer's scale, hence the overrides.
        avatarContainer = new FrameLayout(context) {
            @Override
            public void setScaleX(float scaleX) {
                super.setScaleX(scaleX);
                updateGooey();
            }

            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                updateGooey();
            }
        };
        avatarContainer.setPivotX(0);
        avatarContainer.setPivotY(0);
        avatarGooey = new ProfileGooeyView(context);
        avatarGooey.addView(avatarContainer, LayoutHelper.createFrame(100, 100, Gravity.TOP | Gravity.LEFT));
        avatarContainer2.addView(avatarGooey, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        avatarImage = new ProfileActivity.AvatarImageView(context);
        avatarImage.getImageReceiver().setAllowDecodeSingleFrame(true);
        avatarImage.setRoundRadiusForExpand(dp(50));
        avatarImage.setPivotX(0);
        avatarImage.setPivotY(0);
        avatarContainer.addView(avatarImage, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // Fills the gap between the square cover and the bottom of the header. Above the cover, below
        // the scrim/buttons/text.
        coverBlurView = new CoverBlurView(context);
        avatarContainer2.addView(coverBlurView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // Above the cover, below the text and buttons — otherwise white-on-a-light-cover is unreadable.
        scrimView = new ScrimView(context);
        avatarContainer2.addView(scrimView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        expandAnimator = ValueAnimator.ofFloat(0f, 1f);
        expandAnimator.addUpdateListener(anim -> setAvatarExpandProgress(anim.getAnimatedFraction()));

        if (hasProfileActions()) {
            actionsView = new ProfileActionsView(context, dp(74));
            actionsView.mode = ProfileActionsView.MODE_MY_PROFILE; // fixed row: no peer routing
            actionsView.setOnActionClickListener(this::onActionClick);
            onCreateActions(actionsView);
            avatarContainer2.addView(actionsView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }

        // ProfileActivity styling, verbatim (17.5dp bold title / 13.5dp subtitle, both left-anchored at
        // 109dp and then translated to centre — the centring is done in needLayout, not by gravity).
        nameTextView = new SimpleTextView(context);
        nameTextView.setTextColor(getThemedColor(Theme.key_profile_title));
        nameTextView.setPadding(0, dp(6), 0, dp(4));
        nameTextView.setTextSizePx(dp(17.5f));
        nameTextView.setGravity(Gravity.LEFT);
        nameTextView.setTypeface(AndroidUtilities.bold());
        nameTextView.setLeftDrawableTopPadding(-dp(1.3f));
        nameTextView.setPivotX(0);
        nameTextView.setPivotY(0);
        nameTextView.setScrollNonFitText(true);
        nameTextView.setEllipsizeByGradient(true);
        avatarContainer2.addView(nameTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 109, -6, 0, 0));

        onlineTextView = new SimpleTextView(context);
        onlineTextView.setEllipsizeByGradient(true);
        onlineTextView.setTextColor(getThemedColor(Theme.key_actionBarDefaultSubtitle));
        onlineTextView.setTextSizePx(dp(13.5f));
        onlineTextView.setGravity(Gravity.LEFT);
        onlineTextView.setPivotX(dp(8));
        onlineTextView.setPivotY(dp(8));
        onlineTextView.setPadding(dp(4), dp(2), dp(4), dp(2));
        avatarContainer2.addView(onlineTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 109 - 4, -2, 8 - 4, 0));

        frameLayout.addView(actionBar);

        updateButtonColor();
        // Start expanded: checkListViewScroll bails while the list is empty, so without this the header
        // would render collapsed until the first row arrives and then jump.
        extraHeight = getHeaderExtraHeight();
        needLayout(false);
        return frameLayout;
    }

    /**
     * ProfileActivity gates pulling down on the avatar being a real loaded image; a letters-only
     * AvatarDrawable placeholder has nothing to enlarge. Landscape/tablet are excluded there too,
     * because the expanded avatar is measured as a full-width square.
     */
    private boolean canExpandAvatar() {
        // Expand whenever there is a cover to show. ProfileActivity gates on hasNotThumb() because a
        // peer with no full photo has nothing to open; a song, though, always carries cover art, and
        // many carry ONLY the audio's embedded thumb (hasNotThumb() stays false, only a static thumb is
        // ever set) — yet the user still expects to pull that cover open. So accept a static thumb too.
        // Pulling ALL the way to the photo viewer stays gated on a real artwork URL
        // (getExpandedPhotoObject() returns null otherwise, and openAvatarPhoto() no-ops), so a
        // thumb-only song opens to the square cover and goes no further.
        return avatarImage != null
                && (avatarImage.getImageReceiver().hasNotThumb() || avatarImage.getImageReceiver().hasStaticThumb())
                && !AndroidUtilities.isTablet()
                && !AndroidUtilities.isAccessibilityScreenReaderEnabled()
                && AndroidUtilities.displaySize.x < AndroidUtilities.displaySize.y;
    }

    // ---- geometry, ported from ProfileActivity ----

    /** ProfileActivity#getHeaderOnlyExtraHeight */
    private int getHeaderOnlyExtraHeight() {
        return getActionsExtraHeight() == 0 ? dp(168f) : dp(152f);
    }

    /** ProfileActivity#getActionsExtraHeight */
    private int getActionsExtraHeight() {
        return hasProfileActions() ? dp(74) : 0;
    }

    /** ProfileActivity#getHeaderExtraHeight */
    protected int getHeaderExtraHeight() {
        return getHeaderOnlyExtraHeight() + getActionsExtraHeight();
    }

    /** ProfileActivity#calculateHeaderExtraDiff — 1 = fully expanded, 0 = collapsed into the action bar. */
    private float calculateHeaderExtraDiff() {
        return Utilities.clamp01((extraHeight - getActionsExtraHeight()) / (getHeaderOnlyExtraHeight()));
    }

    private int newTop() {
        return (actionBar.getOccupyStatusBar() ? AndroidUtilities.statusBarHeight : 0) + ActionBar.getCurrentActionBarHeight();
    }

    /** ProfileActivity#checkListViewScroll */
    private void checkListViewScroll() {
        if (listView == null || listView.getChildCount() <= 0) {
            return;
        }
        int newOffset = 0;
        View child = null;
        for (int i = 0; i < listView.getChildCount(); i++) {
            if (listView.getChildAdapterPosition(listView.getChildAt(i)) == 0) {
                child = listView.getChildAt(i);
                break;
            }
        }
        RecyclerListView.Holder holder = child == null ? null : (RecyclerListView.Holder) listView.findContainingViewHolder(child);
        int top = child == null ? 0 : child.getTop();
        int adapterPosition = holder != null ? holder.getAdapterPosition() : RecyclerView.NO_POSITION;
        if (top >= 0 && adapterPosition == 0) {
            newOffset = top;
        }
        // ProfileActivity#checkListViewScroll: the bar flips once the media row reaches the top.
        setMediaHeaderVisible(mediaSectionAttached && mediaSection != null && mediaSection.getTop() <= 0);

        if (extraHeight != newOffset) {
            extraHeight = newOffset;
            topView.invalidate();
            if (scrimView != null) {
                scrimView.invalidate();
            }
            if (coverBlurView != null) {
                coverBlurView.invalidate();
            }
            needLayout(false);
        }
    }

    /**
     * ProfileActivity#refreshNameAndOnlineYBasedOnExpand. Deliberately derives the avatar's drawn size
     * from `scale` rather than the laid-out height, because once expanded avatarContainer's height is
     * the whole header, not 100dp.
     */
    private void refreshNameAndOnlineY(float diff) {
        final int newTop = newTop();
        final float expand = Math.max(0f, Math.min(1f,
                (extraHeight - getHeaderExtraHeight()) / (listView.getMeasuredWidth() - newTop - getHeaderOnlyExtraHeight())));
        final float scale;
        if (extraHeight < getHeaderExtraHeight() && expand < 0.33f) {
            scale = (24 + (54f + (42 - 24)) * diff) / 42.0f;
        } else {
            scale = AndroidUtilities.lerp((42f + 54f) / 42f, (42f + 42f + 54f) / 42f, Math.min(1f, expand * 3f));
        }
        final float endNameY = (actionBar.getOccupyStatusBar() ? AndroidUtilities.statusBarHeight : 0)
                + ActionBar.getCurrentActionBarHeight() / 2f * (1f + diff)
                - 21 * AndroidUtilities.density
                + actionBar.getTranslationY();
        final float avatarBottom = (float) Math.floor(endNameY) + (dp(42) * scale + dpf2(8)) * diff;
        nameY = avatarBottom + dp(1.3f) + dp(7) * diff;
        onlineY = avatarBottom + dp(24) + (float) Math.floor(11 * AndroidUtilities.density) * diff;
    }

    /**
     * On-screen height of the expanded cover: the header minus the strip the buttons sit on.
     *
     * <p>Square at rest falls out of this rather than being imposed: the list's top padding is
     * {@code width + actionsExtraHeight}, so at full expansion the header is exactly that and the photo
     * is left exactly {@code width} tall. Scroll up and the box shortens into a rectangle while the
     * strip keeps its height — the photo is centre-cropped inside it, which is why the picture rises by
     * half of what the finger moves.
     *
     * <p>Clamping to a square instead (either pinned, or via min(header, width)) is wrong in both
     * directions: pinned it freezes and overflows the list, and min() keeps the strip only while the
     * header is taller than wide, so the reflection vanishes the moment you start scrolling.
     */
    private float coverHeightPx(float extra) {
        return Math.max(0, extra + newTop() - getActionsExtraHeight());
    }

    /**
     * ProfileActivity#updateGooey. The collapse effect Telegram ran a contest for: the avatar does not
     * just shrink away, it is drawn through a metaball filter so that on the way up it stretches, thins
     * and merges into the bar at the top of the screen (into the punch-hole itself, on a phone that has
     * one). ProfileGooeyView does all of it and needs nothing from a peer — it even finds the notch
     * itself via NotchInfoUtils — so it is used as-is; this only feeds it the progress.
     */
    private void updateGooey() {
        if (avatarGooey == null) {
            return;
        }
        avatarGooey.setPullProgress(pullUpProgress);
        avatarGooey.setBlurIntensity(Math.min((MathUtils.clamp(pullUpProgress, 0.2f, 0.7f) - 0.2f) / 0.5f, 0.75f));
        avatarGooey.setGooeyEnabled(pullUpProgress > 0 && pullUpProgress < 1);
        avatarGooey.setVisibility(pullUpProgress >= 1.0f ? View.GONE : View.VISIBLE);
    }

    /**
     * ProfileActivity#setMediaHeaderVisible, minus its menu plumbing (it spends most of its length on
     * callItem/videoCallItem/editItem/otherItem and SharedMediaLayout's search+options items, none of
     * which exist here). What is left is the part that is actually visible: the big name/status fade
     * out, the action bar's own title/subtitle fade in, and mediaHeaderAnimationProgress — which is
     * what repaints the bar, since TopView draws windowBackgroundWhite from y1 up once it is 1.
     */
    private void setMediaHeaderVisible(boolean visible) {
        if (mediaHeaderVisible == visible) {
            return;
        }
        mediaHeaderVisible = visible;
        if (headerAnimatorSet != null) {
            headerAnimatorSet.cancel();
        }
        actionBar.setTitle(visible && nameTextView != null ? nameTextView.getText() : "");
        actionBar.setSubtitle(visible && onlineTextView != null ? onlineTextView.getText() : "");

        final ValueAnimator progress = ValueAnimator.ofFloat(mediaHeaderAnimationProgress, visible ? 1f : 0f);
        progress.addUpdateListener(a -> {
            mediaHeaderAnimationProgress = (float) a.getAnimatedValue();
            topView.invalidate();
            if (scrimView != null) {
                scrimView.invalidate();
            }
        });
        final ArrayList<Animator> animators = new ArrayList<>();
        animators.add(progress);
        animators.add(ObjectAnimator.ofFloat(nameTextView, View.ALPHA, visible ? 0f : 1f));
        animators.add(ObjectAnimator.ofFloat(onlineTextView, View.ALPHA, visible ? 0f : 1f));
        if (actionsView != null) {
            animators.add(ObjectAnimator.ofFloat(actionsView, View.ALPHA, visible ? 0f : 1f));
        }
        headerAnimatorSet = new AnimatorSet();
        headerAnimatorSet.playTogether(animators);
        headerAnimatorSet.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        headerAnimatorSet.setDuration(150);
        headerAnimatorSet.start();
    }

    /**
     * ProfileActivity#NestedFrameLayout. The outer list scrolls until the section row reaches the top;
     * from there every remaining pixel is handed to the inner list, which is what lets the strip stay
     * put while its rows keep moving. Copied as-is with sharedMediaLayout -> mediaSection.
     */
    private class NestedFrameLayout extends FrameLayout implements NestedScrollingParent3 {
        private final NestedScrollingParentHelper helper;

        public NestedFrameLayout(Context context) {
            super(context);
            helper = new NestedScrollingParentHelper(this);
        }

        @Override
        public void onNestedScroll(View target, int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed, int type, int[] consumed) {
            if (target == listView && mediaSectionAttached && mediaSection != null) {
                if (mediaSection.getTop() == 0) {
                    consumed[1] = dyUnconsumed;
                    mediaSection.innerListView.scrollBy(0, dyUnconsumed);
                }
            }
        }

        @Override
        public void onNestedScroll(View target, int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed, int type) {
        }

        @Override
        public void onNestedPreScroll(View target, int dx, int dy, int[] consumed, int type) {
            if (target == listView && mediaSectionAttached && mediaSection != null && dy < 0) {
                if (mediaSection.getTop() <= 0) {
                    final RecyclerListView inner = mediaSection.innerListView;
                    final LinearLayoutManager lm = (LinearLayoutManager) inner.getLayoutManager();
                    if (lm != null) {
                        final int pos = lm.findFirstVisibleItemPosition();
                        if (pos != RecyclerView.NO_POSITION) {
                            RecyclerView.ViewHolder holder = inner.findViewHolderForAdapterPosition(pos);
                            final int top = holder != null ? holder.itemView.getTop() : -1;
                            final int paddingTop = inner.getPaddingTop();
                            if (top != paddingTop || pos != 0) {
                                consumed[1] = pos != 0 ? dy : Math.max(dy, (top - paddingTop));
                                inner.scrollBy(0, dy);
                            }
                        }
                    }
                }
            }
        }

        @Override
        public boolean onStartNestedScroll(View child, View target, int axes, int type) {
            return axes == ViewCompat.SCROLL_AXIS_VERTICAL;
        }

        @Override
        public void onNestedScrollAccepted(View child, View target, int axes, int type) {
            helper.onNestedScrollAccepted(child, target, axes);
        }

        @Override
        public void onStopNestedScroll(View target, int type) {
            helper.onStopNestedScroll(target);
        }

        @Override
        public void onStopNestedScroll(View child) {
        }
    }

    /** The outer list is one row: the section. ProfileActivity's list is the same shape, with more rows. */
    private class OuterAdapter extends RecyclerListView.SelectionAdapter {
        static final int VIEW_SPACER = 0, VIEW_HEADER_ROW = 1, VIEW_SECTION = 2;

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == VIEW_HEADER_ROW;
        }

        @Override
        public int getItemCount() {
            return 1 + getHeaderRowCount() + 1; // spacer + header rows + section
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) {
                return VIEW_SPACER;
            }
            return position <= getHeaderRowCount() ? VIEW_HEADER_ROW : VIEW_SECTION;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            if (viewType == VIEW_SPACER) {
                // ProfileActivity#VIEW_TYPE_EMPTY: its list starts with one of these, which is what
                // keeps the first card off the bottom edge of the header. dp(6) is the size it picks
                // for our case (an actions row, no peer colour, no saved music).
                final View spacer = new View(parent.getContext()) {
                    @Override
                    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                        super.onMeasure(
                                View.MeasureSpec.makeMeasureSpec(View.MeasureSpec.getSize(widthMeasureSpec), View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(dp(6), View.MeasureSpec.EXACTLY));
                    }
                };
                spacer.setTag(RecyclerListView.TAG_NOT_SECTION);
                return new RecyclerListView.Holder(spacer);
            }
            if (viewType == VIEW_HEADER_ROW) {
                View row = createHeaderRow(parent.getContext());
                row.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
                return new RecyclerListView.Holder(row);
            }
            if (mediaSection == null) {
                mediaSection = new MediaSectionView(parent.getContext());
            }
            if (mediaSection.getParent() != null) {
                ((android.view.ViewGroup) mediaSection.getParent()).removeView(mediaSection);
            }
            mediaSection.setTag(RecyclerListView.TAG_NOT_SECTION);
            mediaSection.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.MATCH_PARENT));
            return new RecyclerListView.Holder(mediaSection);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == VIEW_HEADER_ROW) {
                bindHeaderRow(holder.itemView, position - 1);
            }
        }

        @Override
        public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder holder) {
            if (holder.itemView == mediaSection) {
                mediaSectionAttached = true;
            }
        }

        @Override
        public void onViewDetachedFromWindow(@NonNull RecyclerView.ViewHolder holder) {
            if (holder.itemView == mediaSection) {
                mediaSectionAttached = false;
            }
        }
    }

    /** ProfileActivity#fixAvatarImageInCenter */
    private void fixAvatarImageInCenter() {
        final FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) avatarContainer.getLayoutParams();
        avatarX = listView.getMeasuredWidth() / 2f - (params.width * avatarScale * 0.5f);
        avatarContainer.setTranslationX(avatarX);
    }

    /** ProfileActivity#needLayout */
    protected void needLayout(boolean animated) {
        if (listView == null || avatarContainer == null || nameTextView == null) {
            return;
        }
        final int newTop = newTop();
        final float diff = calculateHeaderExtraDiff();
        final int headerExtraHeight = getHeaderExtraHeight();

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) listView.getLayoutParams();
        if (lp.topMargin != newTop) {
            lp.topMargin = newTop;
            listView.setLayoutParams(lp);
        }
        listView.setTopGlowOffset((int) extraHeight);
        listView.setOverScrollMode(extraHeight > headerExtraHeight
                && extraHeight < listView.getMeasuredWidth() + getActionsExtraHeight() - newTop
                ? View.OVER_SCROLL_NEVER : View.OVER_SCROLL_ALWAYS);

        // Where the avatar ends up once collapsed: the punch-hole's centre when there is one, so the
        // gooey can merge it into the hole; otherwise just off the top. ProfileActivity#needLayout.
        float endY = -dp(29);
        if (avatarGooey != null && avatarGooey.notchInfo != null && avatarGooey.notchInfo.isLikelyCircle) {
            endY = avatarGooey.notchInfo.bounds.centerY();
        }
        avatarY = AndroidUtilities.lerp(
                endY,
                (actionBar.getOccupyStatusBar() ? AndroidUtilities.statusBarHeight : 0)
                        + ActionBar.getCurrentActionBarHeight()
                        - dp(21)
                        + actionBar.getTranslationY(),
                diff);

        final float h = extraHeight;
        if (h > headerExtraHeight || isPulledDown) {
            // ---- pulled down: the cover grows as a circle, then latches into a full-bleed square ----
            expandProgress = Math.max(0f, Math.min(1f,
                    (h - headerExtraHeight) / (listView.getMeasuredWidth() - newTop - getHeaderOnlyExtraHeight())));
            avatarScale = AndroidUtilities.lerp(96 / 42f, 138 / 42f, Math.min(1f, expandProgress * 3f)) / 100f * 42f;
            pullUpProgress = 0.0f;

            if (allowPullingDown && expandProgress >= 0.33f) {
                if (!isPulledDown) {
                    isPulledDown = true;
                    expandAnimator.cancel();
                    final float value = AndroidUtilities.lerp(expandAnimatorValues, currentExpandAnimatorFracture);
                    expandAnimatorValues[0] = value;
                    expandAnimatorValues[1] = 1f;
                    expandAnimator.setInterpolator(CubicBezierInterpolator.EASE_BOTH);
                    expandAnimator.setDuration((long) ((1f - value) * 250f));
                    expandAnimator.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            justFullyExpanded = false;
                            listView.canStopFlinger = true;
                            expandAnimator.removeListener(this);
                            topView.setBackgroundColor(Color.BLACK);
                        }

                        @Override
                        public void onAnimationCancel(Animator animation) {
                            justFullyExpanded = false;
                            listView.canStopFlinger = true;
                        }
                    });
                    final View view = layoutManager.findViewByPosition(0);
                    if (view != null) {
                        justFullyExpanded = true;
                        listView.smoothScrollBy(0,
                                view.getTop() - listView.getMeasuredWidth() - getActionsExtraHeight() + newTop,
                                (int) expandAnimator.getDuration(), CubicBezierInterpolator.EASE_BOTH);
                        listView.canStopFlinger = false;
                    }
                    expandAnimator.start();
                    try {
                        avatarContainer.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
                    } catch (Exception ignore) {
                    }
                }
                if (!expandAnimator.isRunning()) {
                    // Resting expanded state: name and status pinned to the bottom-left of the cover.
                    nameTextView.setTranslationX(dpf2(18f) - nameTextView.getLeft());
                    nameTextView.setTranslationY(newTop + h - getActionsExtraHeight() - dpf2(30f) - nameTextView.getBottom());
                    onlineTextView.setTranslationX(dpf2(16f) - onlineTextView.getLeft());
                    onlineTextView.setTranslationY(newTop + h - getActionsExtraHeight() - dpf2(10f) - onlineTextView.getBottom());
                    final FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) avatarContainer.getLayoutParams();
                    params.width = (int) (listView.getMeasuredWidth() / avatarScale);
                    params.height = (int) (coverHeightPx(h) / avatarScale);
                    avatarContainer.requestLayout();
                }
            } else if (isPulledDown) {
                // ---- released back above the latch: animate the square back into a circle ----
                isPulledDown = false;
                expandAnimator.cancel();
                final float value = AndroidUtilities.lerp(expandAnimatorValues, currentExpandAnimatorFracture);
                expandAnimatorValues[0] = value;
                expandAnimatorValues[1] = 0f;
                expandAnimator.setInterpolator(CubicBezierInterpolator.EASE_BOTH);
                expandAnimator.setDuration((long) (value * 250f));
                topView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
                expandAnimator.start();
            }

            avatarContainer.setScaleX(avatarScale);
            avatarContainer.setScaleY(avatarScale);
            fixAvatarImageInCenter();

            if (!expandAnimator.isRunning()) {
                refreshNameAndOnlineY(diff);
                if (!isPulledDown) {
                    nameTextView.setTranslationY(nameY);
                    onlineTextView.setTranslationX(onlineX);
                    onlineTextView.setTranslationY(onlineY);
                }
            }
        } else {
            // ---- normal: collapse into the action bar as the list scrolls up ----
            // Shrink into the punch-hole's own size when there is one, so the two actually meet.
            float intoSize = 24f;
            if (avatarGooey != null && avatarGooey.notchInfo != null && avatarGooey.notchInfo.isLikelyCircle) {
                intoSize = 0.5f * avatarGooey.notchInfo.bounds.width() / AndroidUtilities.density;
            }
            avatarScale = AndroidUtilities.lerp(intoSize, 96f, diff) / 100f;
            pullUpProgress = 1.0f - diff;
            if (!expandAnimator.isRunning()) {
                final FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) avatarContainer.getLayoutParams();
                if (params.width != dp(100) || params.height != dp(100)) {
                    params.width = params.height = dp(100);
                    avatarContainer.requestLayout();
                }
                avatarContainer.setScaleX(avatarScale);
                avatarContainer.setScaleY(avatarScale);
                fixAvatarImageInCenter();
                avatarContainer.setTranslationY((float) Math.ceil(avatarY));
            }

            final float nameScale = 1.0f + 0.12f * diff;
            nameTextView.setScaleX(nameScale);
            nameTextView.setScaleY(nameScale);

            refreshNameAndOnlineY(diff);

            final int viewportWidth = listView.getMeasuredWidth();
            final float minimizedX = -dpf2(42 + 4);

            FrameLayout.LayoutParams nameLp = (FrameLayout.LayoutParams) nameTextView.getLayoutParams();
            nameX = viewportWidth / 2f - (nameLp.leftMargin + Math.min(nameTextView.getExactWidth(), viewportWidth) * nameScale * 0.5f);
            FrameLayout.LayoutParams onlineLp = (FrameLayout.LayoutParams) onlineTextView.getLayoutParams();
            onlineX = viewportWidth / 2f - (onlineLp.leftMargin + Math.min(onlineTextView.getExactWidth(), viewportWidth) * 0.5f);

            final float lerpedNameX = AndroidUtilities.lerp(minimizedX, nameX, diff);
            final float lerpedOnlineX = AndroidUtilities.lerp(minimizedX, onlineX, diff);

            if (!expandAnimator.isRunning()) {
                nameTextView.setTranslationX(lerpedNameX);
                nameTextView.setTranslationY(nameY);
                onlineTextView.setTranslationX(lerpedOnlineX);
                onlineTextView.setTranslationY(onlineY);
            }
        }

        updateActionsPosition(newTop);
        topView.invalidate();
        scrimView.invalidate();
        coverBlurView.invalidate();
    }

    /** ProfileActivity#setAvatarExpandProgress — drives the circle -> full-bleed square morph. */
    private void setAvatarExpandProgress(float animatedFracture) {
        if (actionBar == null || avatarContainer == null) {
            return;
        }
        final int newTop = newTop();
        final float value = currentExpandAnimatorValue =
                AndroidUtilities.lerp(expandAnimatorValues, currentExpandAnimatorFracture = animatedFracture);

        avatarContainer.setScaleX(avatarScale);
        avatarContainer.setScaleY(avatarScale);
        avatarContainer.setTranslationY(AndroidUtilities.lerp((float) Math.ceil(avatarY), 0f, value));
        avatarImage.setRoundRadiusForExpand((int) AndroidUtilities.lerp(dp(50), 0f, value));
        if (actionsView != null) {
            actionsView.setParentExpanded(value);
        }

        final float diff = calculateHeaderExtraDiff();
        if (!isPulledDown) {
            refreshNameAndOnlineY(diff);
        }

        // Quadratic bezier from the centred position to the bottom-left of the cover, as in the original.
        final float kx = dpf2(8);
        final float ky = isPulledDown ? dpf2(8) : dpf2(-24);

        final float nameTextViewXEnd = dpf2(18f) - ((FrameLayout.LayoutParams) nameTextView.getLayoutParams()).leftMargin;
        final float nameTextViewYEnd = newTop + extraHeight - getActionsExtraHeight() - dpf2(30f) - nameTextView.getBottom();
        final float nameTextViewCx = kx + nameX + (nameTextViewXEnd - nameX) / 2f;
        final float nameTextViewCy = ky + nameY + (nameTextViewYEnd - nameY) / 2f;
        final float nameTextViewX = (1 - value) * (1 - value) * nameX + 2 * (1 - value) * value * nameTextViewCx + value * value * nameTextViewXEnd;
        final float nameTextViewY = (1 - value) * (1 - value) * nameY + 2 * (1 - value) * value * nameTextViewCy + value * value * nameTextViewYEnd;

        final float onlineTextViewXEnd = dpf2(16f) - ((FrameLayout.LayoutParams) onlineTextView.getLayoutParams()).leftMargin;
        final float onlineTextViewYEnd = newTop + extraHeight - getActionsExtraHeight() - dpf2(10f) - onlineTextView.getBottom();
        final float onlineTextViewCx = kx + onlineX + (onlineTextViewXEnd - onlineX) / 2f;
        final float onlineTextViewCy = ky + onlineY + (onlineTextViewYEnd - onlineY) / 2f;
        final float onlineTextViewX = (1 - value) * (1 - value) * onlineX + 2 * (1 - value) * value * onlineTextViewCx + value * value * onlineTextViewXEnd;
        final float onlineTextViewY = (1 - value) * (1 - value) * onlineY + 2 * (1 - value) * value * onlineTextViewCy + value * value * onlineTextViewYEnd;

        final float minEndNameY = (actionBar.getOccupyStatusBar() ? AndroidUtilities.statusBarHeight : 0)
                + ActionBar.getCurrentActionBarHeight() / 2f
                - 21 * AndroidUtilities.density
                + actionBar.getTranslationY();
        final float minNameY = (float) Math.floor(minEndNameY) + dp(1.3f);
        final float minOnlineY = minNameY + dpf2(22.7f);

        nameTextView.setTranslationX(nameTextViewX);
        nameTextView.setTranslationY(Math.max(minNameY, nameTextViewY));
        onlineTextView.setTranslationX(onlineTextViewX);
        onlineTextView.setTranslationY(Math.max(minOnlineY, onlineTextViewY));

        if (extraHeight > getHeaderOnlyExtraHeight()) {
            nameTextView.setPivotY(AndroidUtilities.lerp(0, nameTextView.getMeasuredHeight(), value));
            nameTextView.setScaleX(AndroidUtilities.lerp(1f + 0.12f * diff, 1.38f, value));
            nameTextView.setScaleY(AndroidUtilities.lerp(1f + 0.12f * diff, 1.38f, value));
        }

        nameTextView.setTextColor(ColorUtils.blendARGB(getThemedColor(Theme.key_profile_title), Color.WHITE, value));
        onlineTextView.setTextColor(ColorUtils.blendARGB(getThemedColor(Theme.key_actionBarDefaultSubtitle), 0xB3FFFFFF, value));
        actionBar.setItemsColor(ColorUtils.blendARGB(getThemedColor(Theme.key_actionBarDefaultIcon), Color.WHITE, value), false);

        final FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) avatarContainer.getLayoutParams();
        params.width = (int) AndroidUtilities.lerp(dpf2(100), listView.getMeasuredWidth() / avatarScale, value);
        params.height = (int) AndroidUtilities.lerp(dpf2(100), coverHeightPx(extraHeight) / avatarScale, value);
        fixAvatarImageInCenter();
        avatarContainer.requestLayout();

        updateActionsPosition(newTop);
        topView.invalidate();
        scrimView.invalidate();
        coverBlurView.invalidate();
    }

    /** ProfileActivity#updateActionsPosition */
    private void updateActionsPosition(int newTop) {
        if (actionsView == null) {
            return;
        }
        actionsView.isOpeningLayout = false;
        actionsView.clipHeight = -1;
        final float bottom = extraHeight + newTop;
        final float height = Math.min(dp(74), bottom - newTop);
        actionsView.updatePosition(bottom - height, height);
    }

    /**
     * ProfileActivity's TopView#setBackgroundColorId branch for a peer with no colour set — which is
     * what a song or an artist is. Picks the button tint from the action bar's perceived brightness.
     */
    private void updateButtonColor() {
        final int actionBarColor = getThemedColor(Theme.key_actionBarDefault);
        final float brightness = AndroidUtilities.computePerceivedBrightness(actionBarColor);
        if (brightness > .8f) {
            btnColor = Color.WHITE;
        } else if (brightness < .2f) {
            btnColor = Theme.multAlpha(Theme.adaptHSV(actionBarColor, +0.02f, +0.25f), .35f);
        } else {
            btnColor = Theme.multAlpha(PeerColorActivity.adaptProfileEmojiColor(actionBarColor), .15f);
        }
        if (actionsView != null) {
            actionsView.setActionsColor(btnColor, false);
        }
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (isPulledDown) {
            // Same affordance as a real profile: back collapses the expanded cover first. The offset is
            // relative to the (very large) top padding — ProfileActivity#collapseAvatarInstant.
            layoutManager.scrollToPositionWithOffset(0, getHeaderExtraHeight() - listView.getPaddingTop());
            listView.post(() -> {
                if (expandAnimator.isRunning()) {
                    expandAnimator.cancel();
                }
                allowPullingDown = false;
                isPulledDown = false;
                // Reset the animator's range before driving it to 0, otherwise lerp() returns the range's
                // start (the value it was interrupted at) rather than the collapsed state.
                expandAnimatorValues[0] = 0f;
                expandAnimatorValues[1] = 1f;
                topView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
                setAvatarExpandProgress(0f);
                needLayout(false);
            });
            return false;
        }
        return super.onBackPressed(invoked);
    }

    /**
     * The strip between the bottom of the square cover and the bottom of the header, where the action
     * buttons sit. A real profile fills it with a blurred mirror of the photo, so the buttons never sit
     * on bare background.
     *
     * <p>ProfileActivity gets this from {@code ProfileGalleryBlurView}, which cannot be reused here: it
     * captures and blurs frames off the avatar *gallery pager* and its draw() no-ops when that pager is
     * null. So the two pieces that actually produce the effect are taken from it directly —
     * {@code Utilities.stackBlurBitmapMax} for the blur, and a {@code BitmapShader} in
     * {@link Shader.TileMode#MIRROR}, which is what reflects the image once sampling passes the photo's
     * bottom edge. The shader is mapped onto exactly the cover's rect, so the reflection lines up with it.
     */
    private class CoverBlurView extends View {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Matrix matrix = new Matrix();
        private final Canvas frameCanvas = new Canvas();
        private Bitmap frame;
        private BitmapShader shader;

        CoverBlurView(Context context) {
            super(context);
            setWillNotDraw(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (currentExpandAnimatorValue <= 0f || avatarImage == null || avatarContainer == null) {
                return;
            }
            final float coverTop = avatarContainer.getY();
            final float coverHeight = avatarContainer.getHeight() * avatarContainer.getScaleY();
            final float stripTop = coverTop + coverHeight;
            final float headerBottom = newTop() + extraHeight;
            final int srcW = avatarImage.getMeasuredWidth();
            final int srcH = avatarImage.getMeasuredHeight();
            if (coverHeight <= 0 || headerBottom <= stripTop + 1 || srcW <= 0 || srcH <= 0 || getWidth() <= 0) {
                return;
            }

            // Re-shot every frame off avatarImage itself, so the reflection is of what is on screen right
            // now — the photo is centre-cropped as the box shortens, and a blur of the original bitmap
            // would reflect rows that are no longer visible. ProfileGalleryBlurView does the same thing
            // with the gallery pager, and these are its numbers: viewport/6 wide, stack blur radius 10.
            final int fw = Math.max(1, (int) (getWidth() / 6f));
            final int fh = Math.max(1, Math.round(fw * (coverHeight / (float) getWidth())));
            try {
                if (frame == null || frame.getWidth() != fw || frame.getHeight() != fh) {
                    if (frame != null) {
                        frame.recycle();
                    }
                    frame = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888);
                    frameCanvas.setBitmap(frame);
                    shader = new BitmapShader(frame, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR);
                    paint.setShader(shader);
                }
                frame.eraseColor(0);
                frameCanvas.save();
                frameCanvas.scale(fw / (float) srcW, fh / (float) srcH);
                avatarImage.draw(frameCanvas);
                frameCanvas.restore();
                Utilities.stackBlurBitmap(frame, Math.max(10, fw / 180));
            } catch (Throwable ignore) {
                return;
            }

            // Map the shot onto exactly the visible photo, so MIRROR reflects it about its bottom edge.
            matrix.reset();
            matrix.setScale(getWidth() / (float) fw, coverHeight / fh);
            matrix.postTranslate(0, coverTop);
            // Fade the reflection out over the strip's length instead of ending it in a hard edge —
            // exactly what ProfileGalleryBlurView#applyShader does, composing the mirror with an alpha
            // gradient via DST_IN. The gradient lives in the same frame space as the bitmap shader, so
            // the one localMatrix on the ComposeShader maps both together. Across the visible strip the
            // sampled frame y runs from fh (canvas stripTop) DOWN to fh + stripFrameLen (canvas
            // headerBottom) — the MIRROR reflects the bitmap there — so an opaque->transparent ramp over
            // exactly that span fades the far edge to nothing.
            final float stripFrameLen = Math.max(1f, (headerBottom - stripTop) / (coverHeight / fh));
            final LinearGradient fade = new LinearGradient(
                    0, fh, 0, fh + stripFrameLen,
                    new int[]{Color.WHITE, Color.TRANSPARENT}, null, Shader.TileMode.CLAMP);
            final ComposeShader composed = new ComposeShader(shader, fade, PorterDuff.Mode.DST_IN);
            composed.setLocalMatrix(matrix);
            paint.setShader(composed);
            paint.setAlpha((int) (0xFF * Math.min(1f, currentExpandAnimatorValue)));
            canvas.drawRect(0, stripTop, getWidth(), headerBottom, paint);
        }
    }

    /**
     * The readable part of ProfileActivity#OverlaysView: the two scrims that darken the top and bottom
     * of an expanded cover so the white name/status and the buttons stay legible over a light image.
     * Its page-indicator bars and black backing rects are gallery-only and are gone.
     */
    private class ScrimView extends View {
        private final android.graphics.drawable.GradientDrawable topGradient =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x42000000, 0});
        private final android.graphics.drawable.GradientDrawable bottomGradient =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0x42000000, 0});

        ScrimView(Context context) {
            super(context);
            setWillNotDraw(false);
            topGradient.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            bottomGradient.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (currentExpandAnimatorValue <= 0f) {
                return;
            }
            final int w = getMeasuredWidth();
            final int h = (int) (newTop() + extraHeight);
            final int alpha = (int) (0xFF * Math.min(1f, currentExpandAnimatorValue));
            topGradient.setBounds(0, 0, w, newTop() + dp(16));
            topGradient.setAlpha(alpha);
            topGradient.draw(canvas);
            bottomGradient.setBounds(0, h - getActionsExtraHeight() - dp(72) - dp(24), w, h);
            bottomGradient.setAlpha(alpha);
            bottomGradient.draw(canvas);
        }
    }

    /**
     * ProfileActivity#TopView, reduced to what a peerless screen can show: the flat colour band behind
     * the avatar, sized to the action bar plus the live extraHeight. The gradient, star-gift emoji
     * pattern and blur-behind are all peer/gallery features and are gone.
     */
    private class TopView extends View {
        private int currentColor;
        private final Paint paint = new Paint();

        public TopView(Context context) {
            super(context);
            setWillNotDraw(false);
        }

        @Override
        public void setBackgroundColor(int color) {
            if (color != currentColor) {
                currentColor = color;
                paint.setColor(color);
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int height = ActionBar.getCurrentActionBarHeight()
                    + (actionBar.getOccupyStatusBar() ? AndroidUtilities.statusBarHeight : 0);
            final float v = height + extraHeight;
            // ProfileActivity#TopView.onDraw. The band shrinks to nothing as the media header comes in,
            // and windowBackgroundWhite takes over from there down — that is the whole colour change:
            // there is no separate "media" bar, the header band just stops being drawn.
            final int y1 = (int) (v * (1.0f - mediaHeaderAnimationProgress));
            if (y1 != 0) {
                canvas.drawRect(0, 0, getMeasuredWidth(), y1, paint);
            }
            if (y1 != v) {
                whitePaint.setColor(getThemedColor(Theme.key_windowBackgroundWhite));
                canvas.drawRect(0, y1, getMeasuredWidth(), v, whitePaint);
            }
        }

        private final Paint whitePaint = new Paint();
    }

    // No isSwipeBackEnabled override on purpose. ProfileActivity only blocks swipe-back where a
    // horizontal drag means something else — over the multi-photo avatar pager and over
    // SharedMediaLayout's tabs — neither of which exists here. Being pulled down is not a reason.
}
