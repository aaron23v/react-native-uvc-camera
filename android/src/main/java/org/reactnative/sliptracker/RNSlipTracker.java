package org.reactnative.sliptracker;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

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

    public interface Listener {
        /** Called on the tracker worker thread. */
        void onTrackingEvent(SlipTrackerEvent event);
    }

    private final Listener listener;

    @Nullable private HandlerThread thread;
    @Nullable private Handler handler;
    private final AtomicBoolean busy = new AtomicBoolean(false);

    // Worker-thread only fields:
    @Nullable private Trial32Tracker tracker;
    @Nullable private Mat grayRaw;
    private byte[] yPlane = new byte[0];
    private boolean openCvUsable = false;
    private boolean userWantsReference = false;
    private String lastEmittedState = SlipTrackerEvent.STATE_IDLE;
    private long lastEmitNanos = 0L;

    // Debug counters (worker-thread reads; submitFrame writes 'submitted'/'dropped' from UVC thread).
    private final java.util.concurrent.atomic.AtomicLong submittedFrames = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong droppedFrames = new java.util.concurrent.atomic.AtomicLong();
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
        busy.set(false);
    }

    /** Cheap check from the UVC callback thread. */
    public boolean acceptsFrames() {
        return handler != null && !busy.get();
    }

    /**
     * Copy the Y plane from the NV21 buffer and post to the worker.
     * Drops the frame if the worker is still chewing on the previous one.
     */
    public void submitFrame(ByteBuffer nv21, int width, int height) {
        Handler h = handler;
        if (h == null) return;
        if (!busy.compareAndSet(false, true)) {
            droppedFrames.incrementAndGet();
            return;
        }
        submittedFrames.incrementAndGet();

        final int ySize = width * height;
        if (ySize <= 0) {
            busy.set(false);
            return;
        }
        if (yPlane.length != ySize) {
            yPlane = new byte[ySize];
        }
        try {
            nv21.clear();
            if (nv21.remaining() < ySize) {
                busy.set(false);
                return;
            }
            nv21.get(yPlane, 0, ySize);
        } catch (Throwable t) {
            busy.set(false);
            return;
        }

        final int w = width;
        final int hgt = height;
        boolean posted = h.post(() -> {
            try {
                processFrame(w, hgt);
            } catch (Throwable t) {
                Log.w(TAG, "processFrame threw", t);
            } finally {
                busy.set(false);
            }
        });
        if (!posted) {
            busy.set(false);
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
    }

    private void teardownOnThread() {
        SlipTrackerDebug.i("teardownOnThread");
        if (grayRaw != null) {
            grayRaw.release();
            grayRaw = null;
        }
        yPlane = new byte[0];
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

    private void processFrame(int width, int height) {
        if (tracker == null || !openCvUsable) return;
        if (grayRaw == null || grayRaw.rows() != height || grayRaw.cols() != width) {
            SlipTrackerDebug.d("processFrame: (re)allocating grayRaw " + width + "x" + height);
            if (grayRaw != null) grayRaw.release();
            grayRaw = new Mat(height, width, CvType.CV_8UC1);
        }
        grayRaw.put(0, 0, yPlane);

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
