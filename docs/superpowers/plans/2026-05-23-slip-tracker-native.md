# Slip Tracker (Native) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the standalone `on_off_slip_feature_android_implementation` tracker into the `react-native-uvc-camera` library, exposing a discrete `state` (`idle | tracking | warning | critical | lost`) to JS via a prop, event, and two imperative methods. Native is the single source of truth for the state classifier; JS draws the UI.

**Architecture:** A new package `org.reactnative.sliptracker` holds a verbatim port of `Trial32Tracker.java` plus a thin `RNSlipTracker` wrapper that owns a dedicated `HandlerThread` (drop-on-busy), classifies tracker output to a discrete state, and emits at state-change + 5 Hz heartbeat. The wrapper is owned by `RNCameraView`. Frames flow in via a new `setExternalFrameCallback` on `AbstractUVCCameraHandler` (NV21 Y-plane only). Imperative JS methods route through `@ReactMethod`s on `CameraModule` taking a `viewTag`, matching this lib's existing pattern.

**Tech Stack:** Java 17, Android library (`com.android.library`), React Native 0.76+ bridge, OpenCV `org.opencv:opencv:4.13.0` (new dependency), Saki4510t UVCCamera fork (existing `libuvccamera` + `usbCameraCommon` modules).

**Spec:** `docs/superpowers/specs/2026-05-22-slip-tracker-native-design.md`

**Verification model:** This project has no automated test suite (per `CLAUDE.md`). Each task's verification is build / lint, plus visual diff review. The library module (`android/`) is not in `settings.gradle` and is only built when consumed by an RN app, so for `android/src/` changes the per-task verification is "code reads correctly and matches the linked spec"; the final task does an end-to-end build inside a consuming RN app.

**Commit policy:** No auto-commits. The final task stages changes and waits for explicit user approval before committing.

---

## File Plan

### New files
| Path | Purpose |
|---|---|
| `android/src/main/java/org/reactnative/sliptracker/Trial32Tracker.java` | Verbatim port of the standalone tracker algorithm. Pure algorithm; no Android imports. |
| `android/src/main/java/org/reactnative/sliptracker/SlipTrackerEvent.java` | POJO payload (state, distance, scale, statusMessage). Decouples internals from JS shape. |
| `android/src/main/java/org/reactnative/sliptracker/RNSlipTracker.java` | Threading + state classifier + emission gate. Owns `HandlerThread`, reusable buffers, and the Trial32 instance. |
| `android/src/main/java/org/reactnative/camera/events/TrackingFrameEvent.java` | RN `Event<T>` subclass for `onTrackingFrame`. Matches the existing event-class style in this package. |

### Modified files
| Path | Change |
|---|---|
| `android/build.gradle` | Add OpenCV dep; bump `minSdkVersion` 16 → 21. |
| `usbCameraCommon/src/main/java/com/serenegiant/usbcameracommon/AbstractUVCCameraHandler.java` | Add public `setExternalFrameCallback(IFrameCallback, int)`. Remember + re-apply across recording. |
| `android/src/main/java/com/google/android/cameraview/CameraUvc.java` | Add `enableTrackingFrames/disableTrackingFrames`; clear in `closeCamera`; pending-flag on `onOpen`. |
| `android/src/main/java/org/reactnative/camera/CameraViewManager.java` | Add `EVENT_ON_TRACKING_FRAME` to `Events` enum; register `trackingEnabled` `@ReactProp`. |
| `android/src/main/java/org/reactnative/camera/RNCameraViewHelper.java` | Add `emitTrackingEvent` helper. |
| `android/src/main/java/org/reactnative/camera/RNCameraView.java` | Own `RNSlipTracker`, implement listener, plumb prop + commands + lifecycle. |
| `android/src/main/java/org/reactnative/camera/CameraModule.java` | Add `@ReactMethod setSlipReference(int)` + `resetSlipTracker(int)`. |
| `android/src/main/java/com/google/android/cameraview/CameraView.java` | Add public `getImpl()` accessor so cross-package `RNCameraView` can reach `CameraUvc`. One-line addition. |
| `src/UvcCamera.js` | Add `trackingEnabled` prop, `onTrackingFrame` event handler, `setSlipReference()` + `resetSlipTracker()` ref methods. |

---

## Task 1: Add OpenCV dependency and bump minSdk

**Files:**
- Modify: `android/build.gradle`

- [ ] **Step 1: Bump `minSdkVersion` 16 → 21 and add OpenCV implementation**

Edit `android/build.gradle`. Change `minSdkVersion 16` to `minSdkVersion 21`, and add `implementation 'org.opencv:opencv:4.13.0'` to the dependencies block.

Final state of the two edited sections:

```gradle
  defaultConfig {
    minSdkVersion 21
    targetSdkVersion rootProject.hasProperty('targetSdkVersion') ? rootProject.targetSdkVersion : DEFAULT_TARGET_SDK_VERSION

    versionCode 2
    versionName "3.0.1"
    
    ndk {
      abiFilters "armeabi-v7a", "arm64-v8a"
    }
    
    consumerProguardFiles 'consumer-rules.pro'
  }
```

```gradle
dependencies {
  def googlePlayServicesVersion = rootProject.hasProperty('googlePlayServicesVersion')  ? rootProject.googlePlayServicesVersion : DEFAULT_GOOGLE_PLAY_SERVICES_VERSION
  def supportLibVersion = rootProject.hasProperty('supportLibVersion')  ? rootProject.supportLibVersion : DEFAULT_SUPPORT_LIBRARY_VERSION

  implementation 'com.facebook.react:react-native:+'
  implementation "com.google.zxing:core:3.2.1"
  implementation "com.drewnoakes:metadata-extractor:2.9.1"
  implementation "com.google.android.gms:play-services-vision:$googlePlayServicesVersion"
  implementation "androidx.exifinterface:exifinterface:$supportLibVersion"
  implementation "androidx.annotation:annotation:$supportLibVersion"
  implementation "androidx.legacy:legacy-support-v4:$supportLibVersion"

  implementation 'org.opencv:opencv:4.13.0'

  implementation project(':libuvccamera')
  implementation project(':usbCameraCommon')
}
```

- [ ] **Step 2: Verify via visual diff**

Re-read `android/build.gradle` and confirm only the two changes above are present. There is no per-module gradle path to compile the library standalone (`android/` is not in `settings.gradle`), so functional verification happens in the consuming RN app at Task 12.

---

## Task 2: Port `Trial32Tracker.java` verbatim

**Files:**
- Create: `android/src/main/java/org/reactnative/sliptracker/Trial32Tracker.java`
- Source: `/Users/admin/Desktop/Projects/Opsfuse/on_off_slip_feature_android_implementation/app/src/main/java/com/example/uvcviewer/Trial32Tracker.java`

- [ ] **Step 1: Copy the file**

Run:
```bash
cp /Users/admin/Desktop/Projects/Opsfuse/on_off_slip_feature_android_implementation/app/src/main/java/com/example/uvcviewer/Trial32Tracker.java \
   /Users/admin/Desktop/Projects/Opsfuse/react-native-uvc-camera/android/src/main/java/org/reactnative/sliptracker/Trial32Tracker.java
```

If the destination directory doesn't exist, the `cp` will fail — create it first:
```bash
mkdir -p /Users/admin/Desktop/Projects/Opsfuse/react-native-uvc-camera/android/src/main/java/org/reactnative/sliptracker
```

- [ ] **Step 2: Change the package declaration**

Open `android/src/main/java/org/reactnative/sliptracker/Trial32Tracker.java`. Change the first line from `package com.example.uvcviewer;` to `package org.reactnative.sliptracker;`. No other edits.

- [ ] **Step 3: Visual diff verification**

Read the file head (first 5 lines). Confirm `package org.reactnative.sliptracker;` and `import org.opencv.calib3d.Calib3d;` (the imports start at the top, unchanged).

---

## Task 3: Create `SlipTrackerEvent` POJO

**Files:**
- Create: `android/src/main/java/org/reactnative/sliptracker/SlipTrackerEvent.java`

- [ ] **Step 1: Write the file**

```java
package org.reactnative.sliptracker;

import androidx.annotation.Nullable;

/**
 * Immutable snapshot of a single tracker emission.
 * Crossing the JS bridge is performed by {@code TrackingFrameEvent.serializeEventData}.
 */
public final class SlipTrackerEvent {

    public static final String STATE_IDLE = "idle";
    public static final String STATE_TRACKING = "tracking";
    public static final String STATE_WARNING = "warning";
    public static final String STATE_CRITICAL = "critical";
    public static final String STATE_LOST = "lost";

    public final String state;
    public final int distance;
    public final double scale;
    @Nullable public final String statusMessage;

    public SlipTrackerEvent(String state, int distance, double scale, @Nullable String statusMessage) {
        this.state = state;
        this.distance = distance;
        this.scale = scale;
        this.statusMessage = statusMessage;
    }
}
```

- [ ] **Step 2: Visual verification**

Read the file. Confirm it compiles in your head: no missing imports, no unresolved symbols, fields are `public final`, constants spelled exactly `"idle"`, `"tracking"`, `"warning"`, `"critical"`, `"lost"`.

---

## Task 4: Create `RNSlipTracker` wrapper

**Files:**
- Create: `android/src/main/java/org/reactnative/sliptracker/RNSlipTracker.java`

- [ ] **Step 1: Write the file**

```java
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

    public RNSlipTracker(Listener listener) {
        this.listener = listener;
    }

    /** Idempotent. Called on UI thread. */
    public synchronized void start() {
        if (thread != null) return;
        HandlerThread t = new HandlerThread("SlipTrackerThread", Process.THREAD_PRIORITY_BACKGROUND);
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
        if (!busy.compareAndSet(false, true)) return;

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
        if (h == null) return;
        h.post(this::setReferenceOnThread);
    }

    /** Clear the reference; tracker returns to idle. */
    public void reset() {
        Handler h = handler;
        if (h == null) return;
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
        if (!openCvUsable) {
            emit(SlipTrackerEvent.STATE_IDLE, 0, 1.0, "OpenCV failed to initialize");
            return;
        }
        tracker = new Trial32Tracker();
        userWantsReference = false;
        lastEmittedState = SlipTrackerEvent.STATE_IDLE;
        lastEmitNanos = 0L;
    }

    private void teardownOnThread() {
        if (grayRaw != null) {
            grayRaw.release();
            grayRaw = null;
        }
        yPlane = new byte[0];
        tracker = null;
        openCvUsable = false;
    }

    private void setReferenceOnThread() {
        if (tracker == null || !openCvUsable) return;
        if (tracker.getLastGrayForReference().empty()) {
            emit(SlipTrackerEvent.STATE_IDLE, 0, tracker.last_scale_est, "Waiting for first frame");
            return;
        }
        tracker.setReferenceFromLastFrame();
        userWantsReference = true;
        lastEmittedState = SlipTrackerEvent.STATE_TRACKING;
        lastEmitNanos = System.nanoTime();
        listener.onTrackingEvent(new SlipTrackerEvent(
                SlipTrackerEvent.STATE_TRACKING, 0, tracker.last_scale_est, tracker.last_status_message));
    }

    private void resetOnThread() {
        if (tracker == null) return;
        userWantsReference = false;
        tracker.resetTrackingManual();
        lastEmittedState = SlipTrackerEvent.STATE_IDLE;
        lastEmitNanos = System.nanoTime();
        listener.onTrackingEvent(new SlipTrackerEvent(
                SlipTrackerEvent.STATE_IDLE, 0, tracker.last_scale_est, tracker.last_status_message));
    }

    private void processFrame(int width, int height) {
        if (tracker == null || !openCvUsable) return;
        if (grayRaw == null || grayRaw.rows() != height || grayRaw.cols() != width) {
            if (grayRaw != null) grayRaw.release();
            grayRaw = new Mat(height, width, CvType.CV_8UC1);
        }
        grayRaw.put(0, 0, yPlane);

        Trial32Tracker.FrameState fs = tracker.process(grayRaw);
        String state = classify(fs);

        long now = System.nanoTime();
        boolean changed = !state.equals(lastEmittedState);
        boolean heartbeat = now - lastEmitNanos >= HEARTBEAT_NANOS;
        if (changed || heartbeat) {
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
        double ratio = (double) fs.distance / (double) Trial32Tracker.DISTANCE_THRESHOLD;
        if (ratio < 0.5) return SlipTrackerEvent.STATE_TRACKING;
        if (ratio < 0.8) return SlipTrackerEvent.STATE_WARNING;
        return SlipTrackerEvent.STATE_CRITICAL;
    }

    private void emit(String state, int distance, double scale, @Nullable String statusMessage) {
        lastEmittedState = state;
        lastEmitNanos = System.nanoTime();
        listener.onTrackingEvent(new SlipTrackerEvent(state, distance, scale, statusMessage));
    }
}
```

- [ ] **Step 2: Visual verification**

Re-read the file end-to-end. Confirm:
- The class compiles in your head: no missing imports, no typos, all field/method references resolve.
- `Trial32Tracker.FrameState` field names used here match exactly: `distance`, `last_scale_est`, `status_message`. (Verify by re-reading `Trial32Tracker.java:141–157`.)
- `Trial32Tracker.DISTANCE_THRESHOLD` and `Trial32Tracker.last_scale_est`, `Trial32Tracker.last_status_message`, `Trial32Tracker.reference_frame` are all public on the ported class.
- The classifier returns one of the five state constants for every code path.
- `busy.set(false)` is set in every error path so the gate isn't stuck.
- `posted = h.post(...)` falls back to `busy.set(false)` if the looper has stopped between the `h != null` check and the post.

---

## Task 5: Add `setExternalFrameCallback` to `AbstractUVCCameraHandler`

**Files:**
- Modify: `usbCameraCommon/src/main/java/com/serenegiant/usbcameracommon/AbstractUVCCameraHandler.java`

The handler keeps `mUVCCamera` private inside an inner thread class. Add a public method that posts to the internal handler thread, remembers the external callback, and re-applies it after the encoder's internal frame callback is torn down (`handleStopRecording`).

- [ ] **Step 1: Add the public method to `AbstractUVCCameraHandler`**

`AbstractUVCCameraHandler` itself extends `android.os.Handler` (its target is the `CameraThread` looper). Existing public methods like `startPreview`, `stopPreview`, `open`, `close` use `sendMessage`/`obtainMessage` for state-machine messages routed via `handleMessage` in `CameraThread`. For a one-shot "invoke this on the worker thread" we use the simpler `post(Runnable)` already provided by `Handler`.

Insert near the other public methods (between `stopPreview` and the recording-related methods — search for `public void stopPreview` and put this method below it):

```java
/**
 * Installs (or clears) a frame callback consumed by code outside this handler
 * (e.g. slip tracking). When recording starts, the encoder's own frame callback
 * temporarily replaces it; the original is re-applied automatically when
 * recording ends. Pass {@code null} to clear.
 */
public void setExternalFrameCallback(final IFrameCallback callback, final int pixelFormat) {
    final CameraThread thread = mWeakThread.get();
    if (thread == null) return;
    post(new Runnable() {
        @Override
        public void run() {
            thread.applyExternalFrameCallback(callback, pixelFormat);
        }
    });
}
```

The `post(Runnable)` runs on the CameraThread's looper because `AbstractUVCCameraHandler` is constructed with that looper (see how `mWeakThread` is wired around line 105–110).

- [ ] **Step 2: Add the matching method on the inner `CameraThread`**

Inside the inner `CameraThread` class (the one that owns `mUVCCamera`), add new fields **near the top** of the class (next to the other `mXxx` fields) and the worker method **anywhere in the class body**:

Fields (place near other private fields like `mUVCCamera`):

```java
// External frame callback (e.g. slip tracker). Saved so we can re-apply it
// after recording temporarily replaces the callback for the encoder.
private IFrameCallback mExternalFrameCallback;
private int mExternalFramePixelFormat;
```

Method (place anywhere in `CameraThread`, e.g. just before `handleStopRecording`):

```java
/**
 * Worker-thread method. Called via {@code AbstractUVCCameraHandler.setExternalFrameCallback}.
 * Records the request and applies it immediately unless recording is active —
 * in which case the recording callback owns the slot until {@code handleStopRecording}
 * re-applies the external callback.
 */
void applyExternalFrameCallback(final IFrameCallback callback, final int pixelFormat) {
    mExternalFrameCallback = callback;
    mExternalFramePixelFormat = pixelFormat;
    if (mUVCCamera != null && mMuxer == null) {
        try {
            mUVCCamera.setFrameCallback(callback, pixelFormat);
        } catch (final Throwable t) {
            Log.w(TAG_THREAD, "applyExternalFrameCallback: setFrameCallback failed", t);
        }
    }
}
```

The method visibility is package-private (no modifier) so that the outer `AbstractUVCCameraHandler` can call it. `mMuxer` is the existing field that's non-null while recording (see the recording start/stop code paths).

- [ ] **Step 3: Re-apply external callback on `handleStopRecording`**

Find `handleStopRecording` (~line 802 currently). At the very end of the method — after `mUVCCamera.setFrameCallback(null, 0)` (~line 820) — re-apply the external callback if one was registered:

```java
if (muxer != null) {
    muxer.stopRecording();
    mUVCCamera.setFrameCallback(null, 0);
    // Re-apply external (tracking) frame callback if one was registered.
    if (mExternalFrameCallback != null && mUVCCamera != null) {
        try {
            mUVCCamera.setFrameCallback(mExternalFrameCallback, mExternalFramePixelFormat);
        } catch (final Throwable t) {
            Log.w(TAG_THREAD, "re-apply external frame callback failed", t);
        }
    }
    callOnStopRecording();
}
```

- [ ] **Step 4: Build the `usbCameraCommon` module**

Run from project root:
```bash
./gradlew :usbCameraCommon:build
```
Expected: `BUILD SUCCESSFUL`. If the build fails on `mWeakThread.get()` access or similar, inspect the actual private field name (likely `mWeakThread` based on the code patterns we already saw, but verify) and fix the reference. The change must compile cleanly because `usbCameraCommon` IS in `settings.gradle:25`.

---

## Task 6: Wire `enableTrackingFrames` / `disableTrackingFrames` into `CameraUvc`

**Files:**
- Modify: `android/src/main/java/com/google/android/cameraview/CameraUvc.java`

- [ ] **Step 1: Add fields for tracking callback + pending registration**

Near the other private fields in `CameraUvc` (around the existing `mCameraHandler`, `mUVCCameraView` declarations at ~line 85–95), add:

```java
// --- Slip tracking integration ---
private com.serenegiant.usb.IFrameCallback mTrackingFrameCallback;
private boolean mTrackingPending = false;
```

- [ ] **Step 2: Add `enableTrackingFrames` / `disableTrackingFrames`**

Add these as public/package-private methods on `CameraUvc` (place them near the bottom of the class, after the existing UVC-related helpers):

```java
/**
 * Install or replace the frame callback used by the slip tracker.
 * If the camera isn't open yet, the registration is deferred until {@code onOpen}.
 */
public void enableTrackingFrames(com.serenegiant.usb.IFrameCallback callback) {
    mTrackingFrameCallback = callback;
    if (mCameraHandler != null && mCameraHandler.isOpened()) {
        try {
            mCameraHandler.setExternalFrameCallback(callback, com.serenegiant.usb.UVCCamera.PIXEL_FORMAT_NV21);
        } catch (final IllegalArgumentException e) {
            android.util.Log.w("AMPA", "enableTrackingFrames: device does not support NV21 frames", e);
        } catch (final Throwable t) {
            android.util.Log.w("AMPA", "enableTrackingFrames: failed", t);
        }
    } else {
        mTrackingPending = true;
    }
}

public void disableTrackingFrames() {
    mTrackingFrameCallback = null;
    mTrackingPending = false;
    if (mCameraHandler != null) {
        try {
            mCameraHandler.setExternalFrameCallback(null, 0);
        } catch (final Throwable t) {
            android.util.Log.w("AMPA", "disableTrackingFrames: failed", t);
        }
    }
}
```

- [ ] **Step 3: Install the pending callback in `onOpen`**

In the existing `mCameraDeviceCallback.onOpen()` body (currently at `CameraUvc.java:100–115`), at the very end of the method, add:

```java
if (mTrackingPending && mTrackingFrameCallback != null && mCameraHandler != null) {
    mTrackingPending = false;
    try {
        mCameraHandler.setExternalFrameCallback(mTrackingFrameCallback, com.serenegiant.usb.UVCCamera.PIXEL_FORMAT_NV21);
    } catch (final Throwable t) {
        android.util.Log.w("AMPA", "onOpen: deferred tracking-frame registration failed", t);
    }
}
```

- [ ] **Step 4: Clear the callback in `closeCamera`**

Find the existing `closeCamera()` method in `CameraUvc.java` (search for `closeCamera`). Before the existing close/release logic runs, add:

```java
if (mCameraHandler != null) {
    try {
        mCameraHandler.setExternalFrameCallback(null, 0);
    } catch (final Throwable ignored) {
    }
}
mTrackingPending = false;
```

The clear is unconditional regardless of whether tracking was on — it's a no-op when no external callback is installed.

- [ ] **Step 5: Visual verification**

Re-read each of the four edits in `CameraUvc.java`. Confirm:
- Imports for `com.serenegiant.usb.IFrameCallback` and `com.serenegiant.usb.UVCCamera` are already present (search the file) — if not, add them at the top.
- `mCameraHandler` is a `UVCCameraHandler` field (it is, per `CameraUvc.java:90`) — `setExternalFrameCallback` was added to its base class `AbstractUVCCameraHandler` in Task 5.
- The `onOpen` body doesn't lose any existing code; the new block is appended.

---

## Task 7: Add `TrackingFrameEvent` class

**Files:**
- Create: `android/src/main/java/org/reactnative/camera/events/TrackingFrameEvent.java`

- [ ] **Step 1: Write the file**

```java
package org.reactnative.camera.events;

import androidx.annotation.Nullable;
import androidx.core.util.Pools;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.uimanager.events.Event;
import com.facebook.react.uimanager.events.RCTEventEmitter;

import org.reactnative.camera.CameraViewManager;

public class TrackingFrameEvent extends Event<TrackingFrameEvent> {

    private static final Pools.SynchronizedPool<TrackingFrameEvent> EVENTS_POOL =
            new Pools.SynchronizedPool<>(8);

    private String mState;
    private int mDistance;
    private double mScale;
    @Nullable private String mStatusMessage;

    private TrackingFrameEvent() {}

    public static TrackingFrameEvent obtain(int viewTag, String state, int distance, double scale, @Nullable String statusMessage) {
        TrackingFrameEvent event = EVENTS_POOL.acquire();
        if (event == null) {
            event = new TrackingFrameEvent();
        }
        event.init(viewTag, state, distance, scale, statusMessage);
        return event;
    }

    private void init(int viewTag, String state, int distance, double scale, @Nullable String statusMessage) {
        super.init(viewTag);
        mState = state;
        mDistance = distance;
        mScale = scale;
        mStatusMessage = statusMessage;
    }

    /**
     * Frequent events; let the bridge coalesce equal-state updates within a frame.
     * Different states must NOT coalesce, so include state in the key.
     */
    @Override
    public short getCoalescingKey() {
        // Hash the state string so consecutive same-state events can coalesce,
        // but different states do not.
        int hash = mState == null ? 0 : mState.hashCode();
        return (short) (hash % Short.MAX_VALUE);
    }

    @Override
    public String getEventName() {
        return CameraViewManager.Events.EVENT_ON_TRACKING_FRAME.toString();
    }

    @Override
    public void dispatch(RCTEventEmitter rctEventEmitter) {
        rctEventEmitter.receiveEvent(getViewTag(), getEventName(), serializeEventData());
    }

    private WritableMap serializeEventData() {
        WritableMap map = Arguments.createMap();
        map.putString("state", mState);
        map.putInt("distance", mDistance);
        map.putDouble("scale", mScale);
        if (mStatusMessage != null) {
            map.putString("statusMessage", mStatusMessage);
        } else {
            map.putNull("statusMessage");
        }
        return map;
    }
}
```

- [ ] **Step 2: Visual verification**

Confirm:
- The class extends `Event<TrackingFrameEvent>` with a pool size of 8 (heartbeat is 5 Hz so 8 in-flight is plenty).
- `getEventName()` returns `CameraViewManager.Events.EVENT_ON_TRACKING_FRAME.toString()` — note this enum value doesn't exist yet; it's added in Task 8.
- All four payload fields are serialized: `state`, `distance`, `scale`, `statusMessage`.

---

## Task 8: Register `EVENT_ON_TRACKING_FRAME` and `trackingEnabled` prop in `CameraViewManager`

**Files:**
- Modify: `android/src/main/java/org/reactnative/camera/CameraViewManager.java`

- [ ] **Step 1: Add enum value**

In the `Events` enum (`CameraViewManager.java:16–35`), add a new entry after the last existing one. Final enum:

```java
public enum Events {
    EVENT_CAMERA_READY("onCameraReady"),
    EVENT_ON_MOUNT_ERROR("onMountError"),
    EVENT_ON_BAR_CODE_READ("onBarCodeRead"),
    EVENT_ON_FACES_DETECTED("onFacesDetected"),
    EVENT_ON_BARCODES_DETECTED("onGoogleVisionBarcodesDetected"),
    EVENT_ON_FACE_DETECTION_ERROR("onFaceDetectionError"),
    EVENT_ON_BARCODE_DETECTION_ERROR("onGoogleVisionBarcodeDetectionError"),
    EVENT_ON_TRACKING_FRAME("onTrackingFrame");

    private final String mName;

    Events(final String name) {
      mName = name;
    }

    @Override
    public String toString() {
      return mName;
    }
}
```

- [ ] **Step 2: Add `trackingEnabled` `@ReactProp`**

Append to the end of `CameraViewManager.java` (before the closing `}` of the class), adjacent to the other `@ReactProp` methods:

```java
@ReactProp(name = "trackingEnabled")
public void setTrackingEnabled(RNCameraView view, boolean enabled) {
    view.setTrackingEnabled(enabled);
}
```

- [ ] **Step 3: Visual verification**

Confirm the enum is iterated by `getExportedCustomDirectEventTypeConstants` (`CameraViewManager.java:58`) — adding to the enum automatically registers the event. No other change needed.

---

## Task 9: Add `emitTrackingEvent` helper in `RNCameraViewHelper`

**Files:**
- Modify: `android/src/main/java/org/reactnative/camera/RNCameraViewHelper.java`

- [ ] **Step 1: Add the helper method**

At the end of `RNCameraViewHelper.java`, just before the class's closing `}`, add:

```java
// Slip tracker event

public static void emitTrackingEvent(
    ViewGroup view,
    String state,
    int distance,
    double scale,
    String statusMessage
) {
    TrackingFrameEvent event = TrackingFrameEvent.obtain(
        view.getId(), state, distance, scale, statusMessage);
    ReactContext reactContext = (ReactContext) view.getContext();
    reactContext.getNativeModule(UIManagerModule.class).getEventDispatcher().dispatchEvent(event);
}
```

The existing import `import org.reactnative.camera.events.*;` (already at line 20) covers `TrackingFrameEvent`.

- [ ] **Step 2: Visual verification**

Confirm:
- The wildcard `events.*` import is already present at `RNCameraViewHelper.java:20` — no new import needed.
- The method signature mirrors `emitFacesDetectedEvent` and `emitBarcodesDetectedEvent` (lines 179, 209).

---

## Task 10: Wire tracker ownership into `RNCameraView`

**Files:**
- Modify: `android/src/main/java/org/reactnative/camera/RNCameraView.java`

The view owns the `RNSlipTracker`, registers the NV21 frame callback with `CameraUvc`, forwards tracker events through `emitTrackingEvent`, and manages lifecycle.

- [ ] **Step 1: Add imports**

At the existing import block (`RNCameraView.java:1–40`), add:

```java
import org.reactnative.sliptracker.RNSlipTracker;
import org.reactnative.sliptracker.SlipTrackerEvent;
import com.serenegiant.usb.IFrameCallback;
import com.google.android.cameraview.CameraUvc;
```

The `CameraUvc` import is required because we'll downcast `getImpl()` (or similar) to access `enableTrackingFrames`. See Step 3 for the access pattern.

- [ ] **Step 2: Make `CameraView.mImpl` accessible**

`CameraView.mImpl` is package-private (`com.google.android.cameraview.CameraView:79`). `RNCameraView` lives in `org.reactnative.camera`, so we need a public getter. Add one line to `CameraView.java` next to other public methods:

```java
public CameraViewImpl getImpl() {
    return mImpl;
}
```

Also widen visibility of `CameraUvc.PREVIEW_WIDTH` and `CameraUvc.PREVIEW_HEIGHT` so `RNCameraView` (different package) can read them. Edit `CameraUvc.java` lines 65 and 71:

```java
public static final int PREVIEW_WIDTH = 1920;
// ...
public static final int PREVIEW_HEIGHT = 1080;
```

- [ ] **Step 3: Implement `RNSlipTracker.Listener` and add fields**

Modify the class declaration to add the new listener interface:

```java
public class RNCameraView extends CameraView implements LifecycleEventListener, BarCodeScannerAsyncTaskDelegate, FaceDetectorAsyncTaskDelegate,
    BarcodeDetectorAsyncTaskDelegate, RNSlipTracker.Listener {
```

Below the existing private fields (after `mGoogleVisionBarCodeType` at line 70 currently), add:

```java
  // --- Slip tracker ---
  @androidx.annotation.Nullable
  private RNSlipTracker mSlipTracker;
  private boolean mTrackingEnabled = false;
  private final IFrameCallback mTrackingFrameCallback = new IFrameCallback() {
      @Override
      public void onFrame(final java.nio.ByteBuffer frame) {
          final RNSlipTracker t = mSlipTracker;
          if (t == null || !t.acceptsFrames()) return;
          t.submitFrame(frame, CameraUvc.PREVIEW_WIDTH, CameraUvc.PREVIEW_HEIGHT);
      }
  };
```

- [ ] **Step 4: Add `setTrackingEnabled` and the two imperative methods**

Append these methods to `RNCameraView` (alphabetical placement near the other setters is fine; e.g. just after `setShouldDetectFaces`):

```java
  public void setTrackingEnabled(boolean enabled) {
    if (mTrackingEnabled == enabled) return;
    mTrackingEnabled = enabled;
    if (enabled) {
        if (mSlipTracker == null) {
            mSlipTracker = new RNSlipTracker(this);
        }
        mSlipTracker.start();
        installTrackingFrameCallback();
    } else {
        uninstallTrackingFrameCallback();
        if (mSlipTracker != null) {
            mSlipTracker.stop();
            mSlipTracker = null;
        }
    }
  }

  public void setSlipReference() {
    if (mSlipTracker != null) mSlipTracker.setReference();
  }

  public void resetSlipTracker() {
    if (mSlipTracker != null) mSlipTracker.reset();
  }

  private void installTrackingFrameCallback() {
    com.google.android.cameraview.CameraViewImpl impl = getImpl();
    if (impl instanceof CameraUvc) {
        ((CameraUvc) impl).enableTrackingFrames(mTrackingFrameCallback);
    }
  }

  private void uninstallTrackingFrameCallback() {
    com.google.android.cameraview.CameraViewImpl impl = getImpl();
    if (impl instanceof CameraUvc) {
        ((CameraUvc) impl).disableTrackingFrames();
    }
  }

  // --- RNSlipTracker.Listener ---

  @Override
  public void onTrackingEvent(SlipTrackerEvent event) {
    RNCameraViewHelper.emitTrackingEvent(
        this, event.state, event.distance, event.scale, event.statusMessage);
  }
```

`getImpl()` was added in Step 2; the `installTrackingFrameCallback` / `uninstallTrackingFrameCallback` methods use it to reach the active `CameraUvc` instance. The `instanceof CameraUvc` guard makes the code defensive even though this library only ever uses the UVC implementation.

- [ ] **Step 5: Lifecycle hooks**

Modify the existing `onHostPause`, `onHostResume`, and `onHostDestroy` methods (lines 405–440):

```java
  @Override
  public void onHostResume() {
    Log.d("AMPA", "ONHOSTRESUME");
    if (hasCameraPermissions()) {
      if ((mIsPaused && !isCameraOpened()) || mIsNew) {
        mIsPaused = false;
        mIsNew = false;
        start();
      }
    } else {
      RNCameraViewHelper.emitMountErrorEvent(this, "Camera permissions not granted - component could not be rendered.");
    }
    if (mTrackingEnabled && mSlipTracker == null) {
        mSlipTracker = new RNSlipTracker(this);
        mSlipTracker.start();
        installTrackingFrameCallback();
    }
  }

  @Override
  public void onHostPause() {
    Log.d("AMPA", "ONHOSTPAUSE");
    if (!mIsPaused && isCameraOpened()) {
      mIsPaused = true;
      stop();
    }
    if (mSlipTracker != null) {
        uninstallTrackingFrameCallback();
        mSlipTracker.stop();
        mSlipTracker = null;
    }
  }

  @Override
  public void onHostDestroy() {
    if (mFaceDetector != null) {
      mFaceDetector.release();
    }
    if (mGoogleBarcodeDetector != null) {
      mGoogleBarcodeDetector.release();
    }
    mMultiFormatReader = null;
    if (mSlipTracker != null) {
        uninstallTrackingFrameCallback();
        mSlipTracker.stop();
        mSlipTracker = null;
    }
    stop();
    destroy();
    cleanup();
  }
```

- [ ] **Step 6: Visual verification**

Confirm:
- `implements ... RNSlipTracker.Listener` is on the class declaration.
- The `onFrame` IFrameCallback uses `CameraUvc.PREVIEW_WIDTH` / `PREVIEW_HEIGHT` (now made `public static final` in Step 2).
- `getImpl()` returns the underlying `CameraViewImpl` (added in Step 2); the access pattern compiles.
- Lifecycle hooks tear down the tracker BEFORE `stop()/destroy()` so callbacks don't fire mid-destruction.

---

## Task 11: Add `@ReactMethod`s to `CameraModule`

**Files:**
- Modify: `android/src/main/java/org/reactnative/camera/CameraModule.java`

- [ ] **Step 1: Add the two methods**

Append after the existing `getSupportedRatios` method (~line 276), before the class's closing `}`:

```java
  @ReactMethod
  public void setSlipReference(final int viewTag) {
      final ReactApplicationContext context = getReactApplicationContext();
      UIManagerModule uiManager = context.getNativeModule(UIManagerModule.class);
      uiManager.addUIBlock(new UIBlock() {
          @Override
          public void execute(NativeViewHierarchyManager nativeViewHierarchyManager) {
              try {
                  RNCameraView cameraView = (RNCameraView) nativeViewHierarchyManager.resolveView(viewTag);
                  cameraView.setSlipReference();
              } catch (Exception e) {
                  e.printStackTrace();
              }
          }
      });
  }

  @ReactMethod
  public void resetSlipTracker(final int viewTag) {
      final ReactApplicationContext context = getReactApplicationContext();
      UIManagerModule uiManager = context.getNativeModule(UIManagerModule.class);
      uiManager.addUIBlock(new UIBlock() {
          @Override
          public void execute(NativeViewHierarchyManager nativeViewHierarchyManager) {
              try {
                  RNCameraView cameraView = (RNCameraView) nativeViewHierarchyManager.resolveView(viewTag);
                  cameraView.resetSlipTracker();
              } catch (Exception e) {
                  e.printStackTrace();
              }
          }
      });
  }
```

- [ ] **Step 2: Visual verification**

Confirm:
- Both methods are `@ReactMethod`-annotated and take `final int viewTag` — matching the signature pattern of `stopRecording` (CameraModule.java:230).
- No `Promise` parameter — these are fire-and-forget.
- `resolveView` is wrapped in try/catch (matches `stopRecording` style).

---

## Task 12: Add the JS API

**Files:**
- Modify: `src/UvcCamera.js`

- [ ] **Step 1: Add `propTypes` entries**

Find the `static propTypes = { ... }` block (line 168) and add `trackingEnabled` and `onTrackingFrame`:

```js
  static propTypes = {
    rotation: PropTypes.number,
    zoom: PropTypes.number,
    ratio: PropTypes.string,
    focusDepth: PropTypes.number,
    onMountError: PropTypes.func,
    onCameraReady: PropTypes.func,
    onBarCodeRead: PropTypes.func,
    onGoogleVisionBarcodesDetected: PropTypes.func,
    onFacesDetected: PropTypes.func,
    onTextRecognized: PropTypes.func,
    onTrackingFrame: PropTypes.func,
    trackingEnabled: PropTypes.bool,
    faceDetectionMode: PropTypes.number,
    faceDetectionLandmarks: PropTypes.number,
    faceDetectionClassifications: PropTypes.number,
    barCodeTypes: PropTypes.arrayOf(PropTypes.string),
    googleVisionBarcodeType: PropTypes.number,
    type: PropTypes.oneOfType([PropTypes.string, PropTypes.number]),
    flashMode: PropTypes.oneOfType([PropTypes.string, PropTypes.number]),
    whiteBalance: PropTypes.oneOfType([PropTypes.string, PropTypes.number]),
    autoFocus: PropTypes.oneOfType([PropTypes.string, PropTypes.number, PropTypes.bool]),
    permissionDialogTitle: PropTypes.string,
    permissionDialogMessage: PropTypes.string,
    notAuthorizedView: PropTypes.element,
    pendingAuthorizationView: PropTypes.element,
    captureAudio: PropTypes.bool,
    useCamera2Api: PropTypes.bool,
    playSoundOnCapture: PropTypes.bool,
  };
```

- [ ] **Step 2: Add ref methods**

Find the `takePictureAsync` method (around line 244). Right after `recordAsync` (line 262), add:

```js
  setSlipReference = () => {
    if (this._cameraHandle == null) return;
    CameraManager.setSlipReference(this._cameraHandle);
  };

  resetSlipTracker = () => {
    if (this._cameraHandle == null) return;
    CameraManager.resetSlipTracker(this._cameraHandle);
  };
```

The exact placement is below `recordAsync` and above any internal helper methods. If you can't find that boundary, place them anywhere between `recordAsync` and the JSX `render()` method.

- [ ] **Step 3: Route the native event**

Find the existing `_on*` event handlers (search for `_onFacesDetected` or `_onCameraReady` in the file — they are bound near the bottom). Add the same shape:

```js
  _onTrackingFrame = (event) => {
    if (this.props.onTrackingFrame) {
      this.props.onTrackingFrame(event.nativeEvent);
    }
  };
```

Then in the JSX where the native `UvcCamera` view is rendered, add the event handler prop next to the existing ones (`onFacesDetected={this._onFacesDetected}`, etc.):

```jsx
onTrackingFrame={this._onTrackingFrame}
```

- [ ] **Step 4: Forward the `trackingEnabled` prop**

The native component receives all props automatically via `requireNativeComponent`. Verify by reading the section where props are passed to the native view (search for `requireNativeComponent` and how the wrapper passes props through). If the wrapper filters props (some implementations do), make sure `trackingEnabled` is whitelisted/passed through. If it just spreads the props, no change needed.

- [ ] **Step 5: Visual verification**

Confirm:
- `propTypes` has `trackingEnabled` and `onTrackingFrame`.
- `setSlipReference` and `resetSlipTracker` arrow-function methods exist and check `this._cameraHandle` before calling.
- `_onTrackingFrame` extracts `event.nativeEvent` (matches the pattern of other handlers in the file).
- The native view receives `onTrackingFrame={this._onTrackingFrame}`.

---

## Task 13: End-to-end build and smoke test

**Files:** none — verification only.

- [ ] **Step 1: Clean usbCameraCommon build**

```bash
cd /Users/admin/Desktop/Projects/Opsfuse/react-native-uvc-camera
./gradlew :usbCameraCommon:clean :usbCameraCommon:build
```

Expected: `BUILD SUCCESSFUL`. If the build fails, the error will point at Task 5 — fix and re-run.

- [ ] **Step 2: Integration verification in a consuming RN app**

The library's `android/` module isn't built by `./gradlew build` from this repo (it's not in `settings.gradle`). To verify the full integration, link this library into an RN app that consumes it:

1. In the consuming RN app:
   ```bash
   cd /path/to/consuming/rn/app/android
   ./gradlew clean
   cd ..
   npx react-native run-android
   ```
2. Watch for compilation errors in `react-native-uvc-camera` — all the Java/Kotlin files we added/modified will compile here.
3. Confirm Metro picks up `UvcCamera.js` changes (restart with `--reset-cache` if needed).

- [ ] **Step 3: Manual smoke test on device**

In the consuming RN app, render a `<UvcCamera>` with the new props:

```jsx
import UvcCamera from 'react-native-uvc-camera';

<UvcCamera
  ref={(r) => { this.camera = r; }}
  trackingEnabled
  onTrackingFrame={(e) => console.log('TRACKING', e)}
  style={{ flex: 1 }}
/>
<Button title="Set ref" onPress={() => this.camera.setSlipReference()} />
<Button title="Reset"   onPress={() => this.camera.resetSlipTracker()} />
```

Verify on a device with a USB camera attached:
1. With `trackingEnabled={false}` (or omitted) → preview works as before; no events fire; no `SlipTrackerThread` in `adb shell ps -T | grep <pid>`.
2. With `trackingEnabled={true}` → preview still works; `SlipTrackerThread` is present; no events until "Set ref" is tapped.
3. After "Set ref" → events arrive at ~5 Hz with `state: 'tracking'` and `distance: 0`.
4. Move the camera slightly → state stays `tracking`; distance grows; new event only when state would change OR every 200 ms.
5. Move more → state transitions to `warning` immediately (event fires on transition).
6. Move much more → state transitions to `critical`.
7. Yank the camera or jolt sharply → state transitions to `lost` with a non-null `statusMessage`.
8. "Reset" → state returns to `idle`. Tap "Set ref" again → tracking resumes from the current view.
9. Toggle prop to `false` → events stop; `SlipTrackerThread` disappears within ~500 ms.
10. Background the app → tracker thread stops; foreground → resumes automatically.

If any of the above fails, do not declare done — re-trace through the data-flow diagram in the spec to find which boundary the issue lives at.

- [ ] **Step 4: Stage all changes**

```bash
cd /Users/admin/Desktop/Projects/Opsfuse/react-native-uvc-camera
git add android/build.gradle \
        android/src/main/java/com/google/android/cameraview/CameraUvc.java \
        android/src/main/java/com/google/android/cameraview/CameraView.java \
        android/src/main/java/org/reactnative/camera/CameraModule.java \
        android/src/main/java/org/reactnative/camera/CameraViewManager.java \
        android/src/main/java/org/reactnative/camera/RNCameraView.java \
        android/src/main/java/org/reactnative/camera/RNCameraViewHelper.java \
        android/src/main/java/org/reactnative/camera/events/TrackingFrameEvent.java \
        android/src/main/java/org/reactnative/sliptracker \
        src/UvcCamera.js \
        usbCameraCommon/src/main/java/com/serenegiant/usbcameracommon/AbstractUVCCameraHandler.java \
        docs/superpowers/specs/2026-05-22-slip-tracker-native-design.md \
        docs/superpowers/plans/2026-05-23-slip-tracker-native.md
git status
```

Expected: all listed paths shown as staged. **Do not commit yet** — wait for explicit user approval per the user's "never commit unless I explicitly ask" rule.

---

## Open follow-ups (out of scope for this plan)
- Configurable thresholds from JS.
- Bitmap-path fallback when NV21 isn't supported by the device.
- TypeScript `.d.ts` definitions.
- Persisting the reference across process restarts.
- Stress-testing multiple simultaneous `<UvcCamera>` instances each with tracking on.
