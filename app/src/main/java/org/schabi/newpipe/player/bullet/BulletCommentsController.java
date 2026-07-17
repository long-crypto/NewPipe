/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.player.bullet;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.bulletComments.BulletCommentsExtractor;
import org.schabi.newpipe.extractor.bulletComments.BulletCommentsInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.views.player.BulletCommentsOverlayView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.disposables.SerialDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Loads bullet comments and keeps extractor-specific lifecycle details out of the player UI.
 */
public final class BulletCommentsController {
    private static final String TAG = BulletCommentsController.class.getSimpleName();
    private static final long LIVE_POLL_INTERVAL_MILLIS = 500L;
    private static final long SEEK_DISCONTINUITY_MILLIS =
            Player.PROGRESS_LOOP_INTERVAL_MILLIS * 2L;

    @NonNull
    private final BulletCommentsOverlayView overlayView;
    @NonNull
    private final CompositeDisposable livePollingDisposable = new CompositeDisposable();
    @NonNull
    private final SerialDisposable loadDisposable = new SerialDisposable();

    @NonNull
    private List<BulletCommentsInfoItem> comments = Collections.emptyList();
    @Nullable
    private BulletCommentsExtractor activeExtractor;
    private int nextCommentIndex;
    private volatile long currentPositionMillis;
    private long lastProgressMillis = -1L;
    private volatile long loadGeneration;
    private boolean playbackInterrupted;
    private volatile boolean destroyed;

    public BulletCommentsController(@NonNull final BulletCommentsOverlayView overlayView) {
        this.overlayView = overlayView;
        overlayView.hideAndReset();
    }

    /**
     * Starts loading after a complete {@link StreamInfo} has reached the player.
     *
     * @param streamInfo successfully extracted stream metadata
     */
    public void load(@NonNull final StreamInfo streamInfo) {
        clearCurrentExtractor();
        if (destroyed) {
            return;
        }

        final StreamingService service = streamInfo.getService();
        if (service.getBulletCommentsLHFactory() == null) {
            return;
        }

        final long generation = loadGeneration;
        final String streamUrl = streamInfo.getUrl();
        final AtomicReference<BulletCommentsExtractor> loadingExtractor =
                new AtomicReference<>();
        final Disposable disposable = Single.fromCallable(
                        () -> loadExtractor(service, streamUrl, generation, loadingExtractor))
                .doOnDispose(() -> disconnectAsync(loadingExtractor.getAndSet(null)))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        result -> {
                            loadingExtractor.compareAndSet(result.extractor, null);
                            acceptLoadResult(generation, result);
                        },
                        throwable -> {
                            loadingExtractor.set(null);
                            handleLoadError(generation, streamUrl, throwable);
                        });
        loadDisposable.set(disposable);
    }

    public void onProgress(final long positionMillis) {
        currentPositionMillis = Math.max(0L, positionMillis);
        if (playbackInterrupted || comments.isEmpty()) {
            return;
        }

        if (lastProgressMillis >= 0L
                && Math.abs(currentPositionMillis - lastProgressMillis)
                > SEEK_DISCONTINUITY_MILLIS) {
            overlayView.resetComments();
            nextCommentIndex = findFirstCommentAtOrAfter(currentPositionMillis);
            lastProgressMillis = currentPositionMillis - 1L;
            return;
        }

        while (nextCommentIndex < comments.size()) {
            final BulletCommentsInfoItem item = comments.get(nextCommentIndex);
            final long scheduledPosition = item.getDuration().toMillis();
            if (scheduledPosition > currentPositionMillis) {
                break;
            }

            if (lastProgressMillis < 0L || scheduledPosition > lastProgressMillis) {
                overlayView.showComment(item);
            }
            nextCommentIndex++;
        }

        lastProgressMillis = currentPositionMillis;
    }

    /** Removes comments currently crossing the video while keeping the loaded timeline. */
    public void onPlaybackInterrupted() {
        playbackInterrupted = true;
        overlayView.resetComments();
    }

    public void onPlaybackResumed() {
        playbackInterrupted = false;
        if (!comments.isEmpty()) {
            nextCommentIndex = findFirstCommentAtOrAfter(currentPositionMillis);
            lastProgressMillis = currentPositionMillis - 1L;
        }
    }

    public void destroy() {
        destroyed = true;
        clearCurrentExtractor();
        loadDisposable.dispose();
    }

    @NonNull
    private LoadResult loadExtractor(
            @NonNull final StreamingService service,
            @NonNull final String streamUrl,
            final long generation,
            @NonNull final AtomicReference<BulletCommentsExtractor> loadingExtractor)
            throws Exception {
        if (destroyed || generation != loadGeneration) {
            return LoadResult.disabled();
        }

        final BulletCommentsExtractor extractor = service.getBulletCommentsExtractor(streamUrl);
        if (extractor == null) {
            return LoadResult.disabled();
        }
        loadingExtractor.set(extractor);

        try {
            if (destroyed || generation != loadGeneration) {
                disconnect(extractor);
                loadingExtractor.compareAndSet(extractor, null);
                return LoadResult.disabled();
            }
            extractor.fetchPage();
            if (destroyed || generation != loadGeneration) {
                disconnect(extractor);
                loadingExtractor.compareAndSet(extractor, null);
                return LoadResult.disabled();
            }
            if (extractor.isDisabled()) {
                disconnect(extractor);
                loadingExtractor.compareAndSet(extractor, null);
                return LoadResult.disabled();
            }

            final InfoItemsPage<BulletCommentsInfoItem> initialPage = extractor.getInitialPage();
            final List<BulletCommentsInfoItem> initialComments = initialPage == null
                    ? Collections.emptyList() : initialPage.getItems();
            final boolean live = extractor.isLive();
            final List<BulletCommentsInfoItem> preparedComments = live
                    ? Collections.emptyList() : prepareOnDemandComments(initialComments);
            return new LoadResult(extractor, preparedComments, live);
        } catch (final Exception exception) {
            disconnect(extractor);
            loadingExtractor.compareAndSet(extractor, null);
            throw exception;
        }
    }

    private void acceptLoadResult(final long generation, @NonNull final LoadResult result) {
        if (destroyed || generation != loadGeneration) {
            disconnectAsync(result.extractor);
            return;
        }

        activeExtractor = result.extractor;
        if (activeExtractor == null) {
            overlayView.hideAndReset();
        } else if (result.live) {
            startLivePolling(activeExtractor, generation);
        } else {
            setOnDemandComments(result.comments);
        }
    }

    private void setOnDemandComments(@NonNull final List<BulletCommentsInfoItem> loadedComments) {
        comments = loadedComments;
        nextCommentIndex = findFirstCommentAtOrAfter(currentPositionMillis);
        lastProgressMillis = currentPositionMillis - 1L;
        if (comments.isEmpty()) {
            overlayView.hideAndReset();
        } else {
            overlayView.showOverlay();
        }
    }

    @NonNull
    private static List<BulletCommentsInfoItem> prepareOnDemandComments(
            @NonNull final List<BulletCommentsInfoItem> loadedComments) {
        final List<BulletCommentsInfoItem> sortedComments = new ArrayList<>();
        for (final BulletCommentsInfoItem item : loadedComments) {
            if (item != null && item.getDuration() != null) {
                sortedComments.add(item);
            }
        }
        Collections.sort(sortedComments);
        return Collections.unmodifiableList(sortedComments);
    }

    private void startLivePolling(@NonNull final BulletCommentsExtractor extractor,
                                  final long generation) {
        overlayView.showOverlay();
        livePollingDisposable.add(
                Observable.interval(0L, LIVE_POLL_INTERVAL_MILLIS,
                                TimeUnit.MILLISECONDS, Schedulers.io())
                        .map(ignored -> {
                            extractor.setCurrentPlayPosition(currentPositionMillis);
                            final List<BulletCommentsInfoItem> liveComments =
                                    extractor.getLiveMessages();
                            return liveComments == null
                                    ? Collections.<BulletCommentsInfoItem>emptyList()
                                    : liveComments;
                        })
                        .filter(liveComments -> !liveComments.isEmpty())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(
                                liveComments -> {
                                    if (!destroyed && !playbackInterrupted
                                            && generation == loadGeneration) {
                                        liveComments.forEach(overlayView::showComment);
                                    }
                                },
                                throwable -> handleLiveError(generation, throwable)));
    }

    private void handleLoadError(final long generation,
                                 @NonNull final String streamUrl,
                                 @NonNull final Throwable throwable) {
        if (destroyed || generation != loadGeneration) {
            return;
        }
        Log.w(TAG, "Could not load bullet comments for " + streamUrl, throwable);
        overlayView.hideAndReset();
    }

    private void handleLiveError(final long generation, @NonNull final Throwable throwable) {
        if (destroyed || generation != loadGeneration) {
            return;
        }
        Log.w(TAG, "Live bullet comments polling failed", throwable);
        clearCurrentExtractor();
    }

    private int findFirstCommentAtOrAfter(final long positionMillis) {
        int left = 0;
        int right = comments.size();
        while (left < right) {
            final int middle = (left + right) / 2;
            if (comments.get(middle).getDuration().toMillis() < positionMillis) {
                left = middle + 1;
            } else {
                right = middle;
            }
        }
        return left;
    }

    private void clearCurrentExtractor() {
        loadGeneration++;
        loadDisposable.set(null);
        livePollingDisposable.clear();
        comments = Collections.emptyList();
        nextCommentIndex = 0;
        lastProgressMillis = -1L;
        overlayView.hideAndReset();

        final BulletCommentsExtractor extractor = activeExtractor;
        activeExtractor = null;
        disconnectAsync(extractor);
    }

    private static void disconnectAsync(@Nullable final BulletCommentsExtractor extractor) {
        if (extractor == null) {
            return;
        }
        Schedulers.io().scheduleDirect(() -> disconnect(extractor));
    }

    private static void disconnect(@NonNull final BulletCommentsExtractor extractor) {
        try {
            extractor.disconnect();
        } catch (final Exception exception) {
            Log.w(TAG, "Could not disconnect bullet comments extractor", exception);
        }
    }

    private static final class LoadResult {
        @Nullable
        private final BulletCommentsExtractor extractor;
        @NonNull
        private final List<BulletCommentsInfoItem> comments;
        private final boolean live;

        private LoadResult(@Nullable final BulletCommentsExtractor extractor,
                           @NonNull final List<BulletCommentsInfoItem> comments,
                           final boolean live) {
            this.extractor = extractor;
            this.comments = comments;
            this.live = live;
        }

        @NonNull
        private static LoadResult disabled() {
            return new LoadResult(null, Collections.emptyList(), false);
        }
    }
}
