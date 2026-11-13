package com.doom.launcher;

import android.graphics.PointF;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Cinematic per-tile zoom for Start, played in both directions around an app.
 *
 * <p>Every visible tile, row, the header and the index bar animate on their own, in a ripple that
 * spreads outward from a focal point: the tile the app was launched from (or the list centre when
 * there isn't one).
 *
 * <ul>
 *   <li>{@link #zoomPast} — launching: Start rushes past the camera. The source tile swells while
 *       the others enlarge, fade and are pushed away from it, so the app seems to grow out of it.
 *   <li>{@link #settle} — returning: the reverse. Everything starts enlarged and faded and glides
 *       back into place, the source tile first, so the app seems to shrink back into its tile.
 * </ul>
 */
final class TileZoomAnimator {

    // Shared "away" state: where elements are when Start is zoomed past.
    private static final float AWAY_SCALE = 1.18f;
    private static final float SOURCE_AWAY_SCALE = 1.5f;
    /** How far elements are pushed away from the focal point, as a fraction of their distance. */
    private static final float PUSH_OUT = 0.14f;

    // Returning home: long, soft landing.
    private static final long SETTLE_MS = 560;
    private static final long SOURCE_SETTLE_MS = 620;
    private static final long SETTLE_STAGGER_MS = 200;
    /** Material "emphasized decelerate". */
    private static final Interpolator SETTLE_EASE = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);

    // Launching: quick and front-loaded, so most of the motion is seen before the app covers Start.
    private static final long ZOOM_PAST_MS = 320;
    private static final long ZOOM_PAST_STAGGER_MS = 70;
    /** Material "emphasized": a fast surge that eases out. */
    private static final Interpolator ZOOM_PAST_EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);

    private final List<View> animated = new ArrayList<>();
    private final List<ViewGroup> unclipped = new ArrayList<>();
    private int running;
    private boolean away;

    /**
     * Launching an app: zooms Start past the camera, ending with every element hidden in the
     * "away" state. Call {@link #settle} or {@link #cancel} to bring it back.
     *
     * @param focus  focal point in window coordinates, or null for the centre of {@code list}
     * @param others other views that join the ripple as a whole (header, index bar), if visible
     */
    void zoomPast(RecyclerView list, @Nullable PointF focus, View... others) {
        play(false, list, focus, others);
    }

    /**
     * Returning home: Start starts in the "away" state and settles back into place.
     *
     * @param focus  focal point in window coordinates, or null for the centre of {@code list}
     * @param others other views that join the ripple as a whole (header, index bar), if visible
     */
    void settle(RecyclerView list, @Nullable PointF focus, View... others) {
        play(true, list, focus, others);
    }

    /** True while Start is zoomed past (or on its way there) and not yet brought back. */
    boolean isAway() {
        return away;
    }

    /** Stops any animation immediately and puts every element back to its resting state. */
    void cancel() {
        for (View v : animated) {
            v.animate().cancel();
            v.animate().setStartDelay(0);
            reset(v);
        }
        animated.clear();
        running = 0;
        away = false;
        restoreClipping();
    }

    private void play(boolean settling, RecyclerView list, @Nullable PointF focus, View... others) {
        cancel();

        List<View> leaves = new ArrayList<>();
        for (View v : others) {
            if (v.getVisibility() == View.VISIBLE) leaves.add(v);
        }
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (child instanceof TileGridLayout) {
                TileGridLayout grid = (TileGridLayout) child;
                unclip(grid);
                for (int j = 0; j < grid.getChildCount(); j++) {
                    View tile = grid.getChildAt(j);
                    if (tile.getVisibility() == View.VISIBLE) leaves.add(tile);
                }
            } else {
                leaves.add(child);
            }
        }
        if (leaves.isEmpty()) return;
        // Let enlarged tiles draw past their grid and the list instead of being cut off.
        unclip(list);
        if (list.getParent() instanceof ViewGroup) unclip((ViewGroup) list.getParent());

        int[] loc = new int[2];
        float fx, fy;
        if (focus != null) {
            fx = focus.x;
            fy = focus.y;
        } else {
            list.getLocationInWindow(loc);
            fx = loc[0] + list.getWidth() / 2f;
            fy = loc[1] + list.getHeight() / 2f;
        }

        // Centres in window coordinates, and the farthest distance to normalise the stagger.
        int n = leaves.size();
        float[] cx = new float[n];
        float[] cy = new float[n];
        float maxDist = 1f;
        View source = null;
        for (int i = 0; i < n; i++) {
            View v = leaves.get(i);
            v.getLocationInWindow(loc);
            cx[i] = loc[0] + v.getWidth() / 2f;
            cy[i] = loc[1] + v.getHeight() / 2f;
            maxDist = Math.max(maxDist, (float) Math.hypot(cx[i] - fx, cy[i] - fy));
            if (focus != null && source == null
                    && fx >= loc[0] && fx <= loc[0] + v.getWidth()
                    && fy >= loc[1] && fy <= loc[1] + v.getHeight()) {
                source = v;
            }
        }

        away = !settling;
        for (int i = 0; i < n; i++) {
            View v = leaves.get(i);
            animated.add(v);
            boolean isSource = v == source;
            float dx = cx[i] - fx;
            float dy = cy[i] - fy;
            float awayScale = isSource ? SOURCE_AWAY_SCALE : AWAY_SCALE;
            float awayX = isSource ? 0f : dx * PUSH_OUT;
            float awayY = isSource ? 0f : dy * PUSH_OUT;
            float awayZ = isSource ? 1f : 0f; // source draws over its neighbours while it's large
            float nearness = (float) Math.hypot(dx, dy) / maxDist;

            if (settling) {
                v.setScaleX(awayScale);
                v.setScaleY(awayScale);
                v.setAlpha(0f);
                v.setTranslationX(awayX);
                v.setTranslationY(awayY);
                v.setTranslationZ(awayZ);
            } else if (isSource) {
                v.setTranslationZ(awayZ);
            }

            running++;
            v.animate()
                    .scaleX(settling ? 1f : awayScale)
                    .scaleY(settling ? 1f : awayScale)
                    .alpha(settling ? 1f : 0f)
                    .translationX(settling ? 0f : awayX)
                    .translationY(settling ? 0f : awayY)
                    .translationZ(settling ? 0f : awayZ)
                    .setStartDelay(isSource ? 0 : (long) (nearness
                            * (settling ? SETTLE_STAGGER_MS : ZOOM_PAST_STAGGER_MS)))
                    .setDuration(settling
                            ? (isSource ? SOURCE_SETTLE_MS : SETTLE_MS)
                            : ZOOM_PAST_MS)
                    .setInterpolator(settling ? SETTLE_EASE : ZOOM_PAST_EASE)
                    .withEndAction(() -> {
                        v.animate().setStartDelay(0); // the delay sticks to the view's animator
                        onOneFinished(settling);
                    })
                    .start();
        }
    }

    private void onOneFinished(boolean settling) {
        if (--running > 0) return;
        running = 0;
        // After zooming past, keep tracking the views so cancel()/settle() can restore them.
        if (settling) {
            animated.clear();
            restoreClipping();
        }
    }

    private void unclip(ViewGroup group) {
        if (group.getClipChildren()) {
            group.setClipChildren(false);
            unclipped.add(group);
        }
    }

    private void restoreClipping() {
        for (ViewGroup group : unclipped) group.setClipChildren(true);
        unclipped.clear();
    }

    private static void reset(View v) {
        v.setScaleX(1f);
        v.setScaleY(1f);
        v.setAlpha(1f);
        v.setTranslationX(0f);
        v.setTranslationY(0f);
        v.setTranslationZ(0f);
    }
}
