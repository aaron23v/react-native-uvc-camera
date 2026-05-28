package org.reactnative.sliptracker;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

/**
 * Owns the dedicated tracker thread, holds a Trial32Tracker instance, classifies
 * its output to a discrete state, and emits to a listener at (a) every state
 * transition and (b) a 5 Hz heartbeat.
 *
 * Threading:
 * - {@link #start()} / {@link #stop()} are called from RN's UI thread.
 * - {@link #submitFrame} is called from the UVC frame-callback thread.
 * - Everything else (state mutation, emission) runs on the dedicated worker.
 */
public final class RNSlipTracker {
    private static final String TAG = "RNSlipTracker";
    private static final long HEARTBEAT_NANOS = 200_000_000L; // 5 Hz
    private static final long JOIN_TIMEOUT_MS = 500L;
    // Frames older than this when the worker pulls them are skipped instead of
    // processed — prevents the tracker from "catching up" through stale data
    // after a per-frame latency spike (e.g. the 100–175 ms post-reset blip).
    private static final long FRAME_AGE_THRESHOLD_NS = 150_000_000L; // 150 ms

    public interface Listener {
        /** Called on the tracker worker thread. */
        void onTrackingEvent(SlipTrackerEvent event);
    }

    private static final class FrameItem {
        final byte[] yPlane;
        final int width;
        final int height;
        final long timestampNs;

        FrameItem(byte[] yPlane, int width, int height, long timestampNs) {
            this.yPlane = yPlane;
            this.width = width;
            this.height = height;
            this.timestampNs = timestampNs;
        }
    }

    private final Listener listener;

    @Nullable private HandlerThread thread;
    @Nullable private Handler handler;
    // Latest-frame-wins queue. submitFrame swaps a new FrameItem in and lets the
    // displaced one fall out of scope (counted as 'dropped'). The worker drains
    // it with getAndSet(null).
    private final AtomicReference<FrameItem> latestFrame = new AtomicReference<>(null);
    // True while a drainer Runnable is in flight or queued. Ensures at most one
    // drainer at a time without rejecting incoming frames.
    private final AtomicBoolean drainerScheduled = new AtomicBoolean(false);

    // Worker-thread only fields:
    @Nullable private Trial32Tracker tracker;
    @Nullable private Mat grayRaw;
    private boolean openCvUsable = false;
    private boolean userWantsReference = false;
    private String lastEmittedState = SlipTrackerEvent.STATE_IDLE;
    private long lastEmitNanos = 0L;

    // Debug counters. submittedFrames + droppedFrames + skippedStaleFrames = total
    // arrivals at submitFrame. processedFrames = how many actually ran through the tracker.
    private final AtomicLong submittedFrames = new AtomicLong();
    private final AtomicLong droppedFrames = new AtomicLong();
    private final AtomicLong skippedStaleFrames = new AtomicLong();
    private long processedFrames = 0L;
    private long sumLatencyNanos = 0L;

    public RNSlipTracker(Listener listener) {
        this.listener = listener;
    }

    /** Idempotent. Called on UI thread. */
    public synchronized void start() {
        if (thread != null) {
            SlipTrackerDebug.d("start: already running, ignored");
            return;
        }
        SlipTrackerDebug.i("start: spinning up tracker thread");
        HandlerThread t = new HandlerThread("SlipTrackerThread", Process.THREAD_PRIORITY_FOREGROUND);
        t.start();
        Handler h = new Handler(t.getLooper());
        thread = t;
        handler = h;
        h.post(this::initOnThread);
    }

    /** Idempotent. Called on UI thread. */
    public synchronized void stop() {
        HandlerThread t = thread;
        Handler h = handler;
        thread = null;
        handler = null;
        SlipTrackerDebug.i("stop: submitted=" + submittedFrames.get()
                + " dropped=" + droppedFrames.get()
                + " skipped=" + skippedStaleFrames.get()
                + " processed=" + processedFrames);
        if (h != null) {
            h.removeCallbacksAndMessages(null);
            h.post(this::teardownOnThread);
        }
        if (t != null) {
            t.quitSafely();
            try {
                t.join(JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        latestFrame.set(null);
        drainerScheduled.set(false);
    }

    /** Cheap check from the UVC callback thread — true whenever the tracker is running. */
    public boolean acceptsFrames() {
        return handler != null;
    }

    /**
     * Copy the Y plane out of the NV21 buffer into a fresh FrameItem and swap it
     * into {@link #latestFrame}. The displaced FrameItem (if any) is counted as
     * dropped — worker only ever sees the most recent frame. A drainer Runnable
     * is posted to the worker thread iff one isn't already scheduled.
     */
    public void submitFrame(ByteBuffer nv21, int width, int height) {
        Handler h = handler;
        if (h == null) return;
        submittedFrames.incrementAndGet();

        final int ySize = width * height;
        if (ySize <= 0) return;

        final byte[] yp = new byte[ySize];
        try {
            nv21.clear();
            if (nv21.remaining() < ySize) return;
            nv21.get(yp, 0, ySize);
        } catch (Throwable t) {
            return;
        }

        FrameItem item = new FrameItem(yp, width, height, System.nanoTime());
        FrameItem previous = latestFrame.getAndSet(item);
        if (previous != null) {
            droppedFrames.incrementAndGet();
        }

        if (drainerScheduled.compareAndSet(false, true)) {
            if (!h.post(this::drainLatestFrame)) {
                drainerScheduled.set(false);
            }
        }
    }

    /**
     * Drainer loop running on the worker thread. Processes the freshest frame,
     * skips frames older than {@link #FRAME_AGE_THRESHOLD_NS}, and re-arms itself
     * if a new frame arrived while the previous one was being processed.
     */
    private void drainLatestFrame() {
        try {
            while (true) {
                FrameItem item = latestFrame.getAndSet(null);
                if (item == null) break;

                long ageNs = System.nanoTime() - item.timestampNs;
                if (ageNs > FRAME_AGE_THRESHOLD_NS) {
                    skippedStaleFrames.incrementAndGet();
                    continue;
                }
                try {
                    processFrame(item);
                } catch (Throwable t) {
                    Log.w(TAG, "processFrame threw", t);
                }
            }
        } finally {
            drainerScheduled.set(false);
            // Catch the race where a frame landed between the drain-loop's null
            // read and the flag clear. If so, re-arm.
            Handler h = handler;
            if (h != null && latestFrame.get() != null
                    && drainerScheduled.compareAndSet(false, true)) {
                if (!h.post(this::drainLatestFrame)) {
                    drainerScheduled.set(false);
                }
            }
        }
    }

    /** Capture the most recent processed frame as the new reference. */
    public void setReference() {
        Handler h = handler;
        if (h == null) {
            SlipTrackerDebug.w("setReference ignored: tracker not started");
            return;
        }
        SlipTrackerDebug.i("setReference requested");
        h.post(this::setReferenceOnThread);
    }

    /** Clear the reference; tracker returns to idle. */
    public void reset() {
        Handler h = handler;
        if (h == null) {
            SlipTrackerDebug.w("reset ignored: tracker not started");
            return;
        }
        SlipTrackerDebug.i("reset requested");
        h.post(this::resetOnThread);
    }

    // ---------- worker-thread methods below ----------

    private void initOnThread() {
        try {
            openCvUsable = OpenCVLoader.initDebug();
        } catch (Throwable t) {
            openCvUsable = false;
            Log.w(TAG, "OpenCV init threw", t);
        }
        SlipTrackerDebug.i("initOnThread: openCvUsable=" + openCvUsable);
        if (!openCvUsable) {
            emit(SlipTrackerEvent.STATE_IDLE, 0, 1.0, "OpenCV failed to initialize");
            return;
        }
        tracker = new Trial32Tracker();
        userWantsReference = false;
        lastEmittedState = SlipTrackerEvent.STATE_IDLE;
        lastEmitNanos = 0L;
        processedFrames = 0L;
        sumLatencyNanos = 0L;
        submittedFrames.set(0L);
        droppedFrames.set(0L);
        skippedStaleFrames.set(0L);
    }

    private void teardownOnThread() {
        SlipTrackerDebug.i("teardownOnThread");
        if (grayRaw != null) {
            grayRaw.release();
            grayRaw = null;
        }
        tracker = null;
        openCvUsable = false;
    }

    private void setReferenceOnThread() {
        if (tracker == null || !openCvUsable) {
            SlipTrackerDebug.w("setReferenceOnThread: tracker=" + (tracker != null) + " openCV=" + openCvUsable);
            return;
        }
        if (tracker.getLastGrayForReference().empty()) {
            SlipTrackerDebug.d("setReferenceOnThread: no frame yet, emitting idle");
            emit(SlipTrackerEvent.STATE_IDLE, 0, tracker.last_scale_est, "Waiting for first frame");
            return;
        }
        tracker.setReferenceFromLastFrame();
        userWantsReference = true;
        lastEmittedState = SlipTrackerEvent.STATE_TRACKING;
        lastEmitNanos = System.nanoTime();
        SlipTrackerDebug.i("setReferenceOnThread: reference captured, status=" + tracker.last_status_message);
        listener.onTrackingEvent(new SlipTrackerEvent(
                SlipTrackerEvent.STATE_TRACKING, 0, tracker.last_scale_est, tracker.last_status_message));
    }

    private void resetOnThread() {
        if (tracker == null) return;
        userWantsReference = false;
        tracker.resetTrackingManual();
        lastEmittedState = SlipTrackerEvent.STATE_IDLE;
        lastEmitNanos = System.nanoTime();
        SlipTrackerDebug.i("resetOnThread: tracker cleared, status=" + tracker.last_status_message);
        listener.onTrackingEvent(new SlipTrackerEvent(
                SlipTrackerEvent.STATE_IDLE, 0, tracker.last_scale_est, tracker.last_status_message));
    }

    private void processFrame(FrameItem item) {
        if (tracker == null || !openCvUsable) return;
        int width = item.width;
        int height = item.height;
        if (grayRaw == null || grayRaw.rows() != height || grayRaw.cols() != width) {
            SlipTrackerDebug.d("processFrame: (re)allocating grayRaw " + width + "x" + height);
            if (grayRaw != null) grayRaw.release();
            grayRaw = new Mat(height, width, CvType.CV_8UC1);
        }
        grayRaw.put(0, 0, item.yPlane);

        long startNanos = System.nanoTime();
        Trial32Tracker.FrameState fs = tracker.process(grayRaw);
        long latencyNanos = System.nanoTime() - startNanos;
        processedFrames++;
        sumLatencyNanos += latencyNanos;

        String state = classify(fs);

        if (processedFrames % SlipTrackerDebug.LOG_EVERY_N_FRAMES == 0) {
            double avgMs = (sumLatencyNanos / SlipTrackerDebug.LOG_EVERY_N_FRAMES) / 1_000_000.0;
            SlipTrackerDebug.d("frame#" + processedFrames
                    + " state=" + state
                    + " dist=" + fs.distance
                    + " instab=" + String.format("%.2f", fs.instability)
                    + " scale=" + String.format("%.3f", fs.last_scale_est)
                    + " avgMs=" + String.format("%.1f", avgMs)
                    + " submitted=" + submittedFrames.get()
                    + " dropped=" + droppedFrames.get()
                    + " skipped=" + skippedStaleFrames.get()
                    + (fs.status_message != null ? " status=\"" + fs.status_message + "\"" : ""));
            sumLatencyNanos = 0L;
        }

        long now = System.nanoTime();
        boolean changed = !state.equals(lastEmittedState);
        boolean heartbeat = now - lastEmitNanos >= HEARTBEAT_NANOS;
        if (changed || heartbeat) {
            if (changed) {
                SlipTrackerDebug.i("state transition " + lastEmittedState + " -> " + state
                        + " (dist=" + fs.distance + " scale=" + String.format("%.3f", fs.last_scale_est)
                        + (fs.status_message != null ? " status=\"" + fs.status_message + "\"" : "") + ")");
            }
            listener.onTrackingEvent(new SlipTrackerEvent(state, fs.distance, fs.last_scale_est, fs.status_message));
            lastEmittedState = state;
            lastEmitNanos = now;
        }
    }

    private String classify(Trial32Tracker.FrameState fs) {
        if (tracker == null) return SlipTrackerEvent.STATE_IDLE;
        boolean referenceSet = tracker.reference_frame != null;
        if (!referenceSet) {
            return userWantsReference ? SlipTrackerEvent.STATE_LOST : SlipTrackerEvent.STATE_IDLE;
        }
        double distRatio = (double) fs.distance / (double) Trial32Tracker.DISTANCE_THRESHOLD;
        double score = Math.max(distRatio, fs.instability);

        // Asymmetric hysteresis — enter at 0.5/0.8, exit at 0.3/0.6.
        // Suppresses flapping at the 0.5 boundary (observed jitter band ~0.48-0.51)
        // so a raised alarm doesn't drop back to green until the signal clearly recovers.
        switch (lastEmittedState) {
            case SlipTrackerEvent.STATE_CRITICAL:
                if (score >= 0.6) return SlipTrackerEvent.STATE_CRITICAL;
                if (score >= 0.3) return SlipTrackerEvent.STATE_WARNING;
                return SlipTrackerEvent.STATE_TRACKING;
            case SlipTrackerEvent.STATE_WARNING:
                if (score >= 0.8) return SlipTrackerEvent.STATE_CRITICAL;
                if (score >= 0.3) return SlipTrackerEvent.STATE_WARNING;
                return SlipTrackerEvent.STATE_TRACKING;
            default:
                if (score >= 0.8) return SlipTrackerEvent.STATE_CRITICAL;
                if (score >= 0.5) return SlipTrackerEvent.STATE_WARNING;
                return SlipTrackerEvent.STATE_TRACKING;
        }
    }

    private void emit(String state, int distance, double scale, @Nullable String statusMessage) {
        lastEmittedState = state;
        lastEmitNanos = System.nanoTime();
        listener.onTrackingEvent(new SlipTrackerEvent(state, distance, scale, statusMessage));
    }
}
