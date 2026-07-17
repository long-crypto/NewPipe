/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.views.player;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.util.SparseBooleanArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

import org.schabi.newpipe.extractor.bulletComments.BulletCommentsInfoItem;

/** Draws scrolling and fixed-position bullet comments over the video surface. */
public final class BulletCommentsOverlayView extends FrameLayout {
    private static final long DEFAULT_SCROLL_DURATION_MILLIS = 8000L;
    private static final long DEFAULT_FIXED_DURATION_MILLIS = 4000L;
    private static final float DEFAULT_TEXT_SIZE_SP = 20F;
    private static final float MIN_TEXT_SIZE_SP = 12F;
    private static final float LANE_HEIGHT_DP = 32F;

    @NonNull
    private final SparseBooleanArray occupiedScrollingLanes = new SparseBooleanArray();
    @NonNull
    private final SparseBooleanArray occupiedTopLanes = new SparseBooleanArray();
    @NonNull
    private final SparseBooleanArray occupiedBottomLanes = new SparseBooleanArray();
    private int resetGeneration;

    public BulletCommentsOverlayView(@NonNull final Context context) {
        super(context);
        init();
    }

    public BulletCommentsOverlayView(@NonNull final Context context,
                                     @Nullable final AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BulletCommentsOverlayView(@NonNull final Context context,
                                     @Nullable final AttributeSet attrs,
                                     final int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setClipChildren(true);
        setClipToPadding(true);
    }

    public void showOverlay() {
        setVisibility(View.VISIBLE);
    }

    public void hideAndReset() {
        resetComments();
        setVisibility(View.GONE);
    }

    public void resetComments() {
        resetGeneration++;
        removeAllViews();
        occupiedScrollingLanes.clear();
        occupiedTopLanes.clear();
        occupiedBottomLanes.clear();
    }

    public void showComment(@NonNull final BulletCommentsInfoItem item) {
        final String commentText = item.getCommentText();
        if (commentText == null || commentText.isBlank()) {
            return;
        }

        if (getWidth() == 0 || getHeight() == 0) {
            final int generation = resetGeneration;
            post(() -> {
                if (generation == resetGeneration) {
                    showComment(item);
                }
            });
            return;
        }

        showOverlay();
        final AppCompatTextView textView = buildTextView(item);
        final BulletCommentsInfoItem.Position position = item.getPosition();
        if (position == BulletCommentsInfoItem.Position.TOP) {
            showFixedComment(textView, true, resolveDuration(item, DEFAULT_FIXED_DURATION_MILLIS));
        } else if (position == BulletCommentsInfoItem.Position.BOTTOM) {
            showFixedComment(textView, false,
                    resolveDuration(item, DEFAULT_FIXED_DURATION_MILLIS));
        } else {
            showScrollingComment(textView, resolveDuration(item, DEFAULT_SCROLL_DURATION_MILLIS));
        }
    }

    @NonNull
    private AppCompatTextView buildTextView(@NonNull final BulletCommentsInfoItem item) {
        final AppCompatTextView textView = new AppCompatTextView(getContext());
        textView.setText(item.getCommentText());
        textView.setTextColor(item.getArgbColor() == 0 ? Color.WHITE : item.getArgbColor());
        textView.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                Math.max(MIN_TEXT_SIZE_SP,
                        DEFAULT_TEXT_SIZE_SP * (float) item.getRelativeFontSize()));
        textView.setShadowLayer(6F, 2F, 2F, Color.BLACK);
        textView.setSingleLine(true);
        textView.setClickable(false);
        textView.setFocusable(false);
        return textView;
    }

    private void showScrollingComment(@NonNull final AppCompatTextView textView,
                                      final long durationMillis) {
        final int laneHeight = getLaneHeight();
        final int lane = findAvailableLane(occupiedScrollingLanes,
                Math.max(1, getHeight() / laneHeight));
        if (lane < 0) {
            return;
        }
        occupiedScrollingLanes.put(lane, true);

        final LayoutParams layoutParams =
                new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        layoutParams.gravity = Gravity.START | Gravity.TOP;
        layoutParams.topMargin = lane * laneHeight;
        addView(textView, layoutParams);

        textView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        textView.setTranslationX(getWidth());
        textView.animate()
                .translationX(-textView.getMeasuredWidth())
                .setDuration(durationMillis)
                .withEndAction(() -> removeComment(textView, occupiedScrollingLanes, lane))
                .start();
    }

    private void showFixedComment(@NonNull final AppCompatTextView textView,
                                  final boolean top,
                                  final long durationMillis) {
        final int laneHeight = getLaneHeight();
        final int laneCount = Math.max(1, getHeight() / laneHeight / 3);
        final SparseBooleanArray occupiedLanes = top
                ? occupiedTopLanes : occupiedBottomLanes;
        final int lane = findAvailableLane(occupiedLanes, laneCount);
        if (lane < 0) {
            return;
        }
        occupiedLanes.put(lane, true);

        final LayoutParams layoutParams =
                new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        layoutParams.gravity = (top ? Gravity.TOP : Gravity.BOTTOM) | Gravity.CENTER_HORIZONTAL;
        if (top) {
            layoutParams.topMargin = lane * laneHeight;
        } else {
            layoutParams.bottomMargin = lane * laneHeight;
        }
        addView(textView, layoutParams);

        textView.setAlpha(0F);
        textView.animate()
                .alpha(1F)
                .setDuration(200L)
                .withEndAction(() -> postDelayed(() -> textView.animate()
                        .alpha(0F)
                        .setDuration(200L)
                        .withEndAction(() -> removeComment(textView, occupiedLanes, lane))
                        .start(), Math.max(200L, durationMillis - 200L)))
                .start();
    }

    private void removeComment(@NonNull final View view,
                               @NonNull final SparseBooleanArray occupiedLanes,
                               final int lane) {
        if (indexOfChild(view) < 0) {
            return;
        }
        removeView(view);
        occupiedLanes.delete(lane);
    }

    private static int findAvailableLane(@NonNull final SparseBooleanArray occupiedLanes,
                                         final int laneCount) {
        for (int lane = 0; lane < laneCount; lane++) {
            if (!occupiedLanes.get(lane)) {
                return lane;
            }
        }
        return -1;
    }

    private int getLaneHeight() {
        final float density = getResources().getDisplayMetrics().density;
        return Math.max((int) (density * LANE_HEIGHT_DP), 1);
    }

    private static long resolveDuration(@NonNull final BulletCommentsInfoItem item,
                                        final long defaultDurationMillis) {
        return item.getLastingTime() > 0 ? item.getLastingTime() : defaultDurationMillis;
    }
}
