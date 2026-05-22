# Slip Tracker — Native-Side Design

**Status:** Approved — ready for implementation plan
**Date:** 2026-05-22
**Scope:** Port the standalone `on_off_slip_feature_android_implementation` tracker (Java/OpenCV port of `trial_32.py`) into the `react-native-uvc-camera` library. Expose tracker state to JS via props, events, and imperative methods. JS draws the UI; native does not draw anything.

## Goals

- Run the existing Trial32 multi-method consensus tracker (ORB + RANSAC homography, multi-scale template matching, phase correlation, optical flow) on UVC preview frames.
- Surface a discrete `state` (`idle | tracking | warning | critical | lost`) to JS so consumers can render an "on target / off target" border around the preview and trigger treatment-related actions.
- Keep the existing preview path untouched when tracking is disabled (zero overhead opt-in).

## Constraints / breaking changes

- **`minSdkVersion` bumps from 16 → 21.** OpenCV 4.x is built against `minSdk = 21`. Adding the dependency forces this library's `minSdk` upward, which is a breaking change for downstream consumers still supporting Android 4.x. Worth flagging in the next release notes. (`android/build.gradle:26` currently sets `minSdkVersion 16`.)
- **`compileSdk` / `targetSdk` already at 35 / 34** — compatible with OpenCV 4.13.
- **APK size impact:** OpenCV adds roughly 40–60 MB of native code (per ABI, before split-APK). Consumer apps that bundle both `armeabi-v7a` and `arm64-v8a` should expect ~80–120 MB of size growth in the resulting APK/AAB unless they use ABI splits.
- **Tracking and video recording are mutually exclusive** — see the error-handling table. Trying to do both at once will simply pause tracking until recording ends.

## Non-goals

- iOS support.
- Drawing markers, arrows, or bar gauges on the native preview surface.
- Configurable thresholds from JS (Trial32 constants stay hardcoded).
- TypeScript definitions.
- Saving/restoring the reference frame across process restarts.
- Multiple simultaneous tracker instances per app (each `<UvcCamera>` view gets its own tracker; not stress-tested).

## Architecture

```
┌──────────────────────────────────────────────┐
│                JS (UvcCamera.js)             │
│  props: trackingEnabled                      │
│  events: onTrackingFrame                     │
│  imperative: ref.setSlipReference()          │
│              ref.resetSlipTracker()          │
└────────────┬──────────────────┬──────────────┘
             │ prop              │ command
             ▼                   ▼
┌──────────────────────────────────────────────┐
│   RNCameraView (org.reactnative.camera)      │
│   - owns RNSlipTracker instance              │
│   - emits via RNCameraViewHelper             │
└────────────┬─────────────────────────────────┘
             │ enable/disable, setRef, reset
             ▼
┌──────────────────────────────────────────────┐
│   RNSlipTracker (org.reactnative.sliptracker)│
│   - HandlerThread "SlipTrackerThread"        │
│   - Trial32Tracker (Java/OpenCV port)        │
│   - state classifier + emitter gate          │
└────────────▲─────────────────────────────────┘
             │ byte[] Y plane (1920×1080, resized to 960×720 inside tracker)
             │ posted from UVC frame callback
┌────────────┴─────────────────────────────────┐
│   CameraUvc (com.google.android.cameraview)  │
│   - existing UVCCameraHandler                │
│   - NEW: secondary IFrameCallback (NV21)     │
│     registered ONLY when tracker enabled     │
└──────────────────────────────────────────────┘
```

## Key decisions

| Decision | Choice | Reason |
|---|---|---|
| Scope of port | Tracker logic only | UI/overlay is JS-side per consumer requirements |
| OpenCV dependency | Required transitive (`org.opencv:opencv:4.13.0`) | Tracker is impossible without it; bundled UX is simplest for consumers |
| Frame input | Raw NV21 `IFrameCallback` | Single ~2 MB Y-plane memcpy at 1920×1080 vs ~16 MB of Bitmap allocation + cvtColor at ARGB_8888 — ~5× cheaper; matches the standalone's known-good wiring |
| Threading | Single dedicated `HandlerThread` with drop-on-busy | Trial32 carries per-frame state (`prev_gray`, counters) — parallel/out-of-order processing breaks it. AsyncTask + lock would be equivalent but uglier and uses a deprecated API |
| JS API shape | Prop + event + imperative methods | Matches existing face/barcode detector patterns in this lib |
| Payload | `{ state, distance, scale, statusMessage }` | Native owns the threshold mapping (single source of truth); discrete state means React only re-renders on transitions; raw numbers carried for logging |
| Emission rate | On state change + 5 Hz heartbeat | Immediate alerts on transitions; light bridge traffic |

## Components

### New: `Trial32Tracker.java` — `org.reactnative.sliptracker`

Verbatim port from `on_off_slip_feature_android_implementation/app/src/main/java/com/example/uvcviewer/Trial32Tracker.java`. Only edit: package declaration. Pure algorithm; no Android imports, no threading. Public surface:

- `process(Mat gray_raw) → FrameState` — one frame in, one state out.
- `setReferenceFromLastFrame()` — captures the most recent processed frame as the new reference.
- `resetTrackingManual()` — clears the reference.
- `getLastGrayForReference()` — exposed only so the wrapper can check `.empty()` before allowing `setReference` to take effect.

### New: `RNSlipTracker.java` — `org.reactnative.sliptracker`

Wrapper that owns threading, frame plumbing, and state classification.

- Lifecycle:
  - `start()` — spins up `HandlerThread "SlipTrackerThread"` at `THREAD_PRIORITY_BACKGROUND`, allocates the reusable `byte[] yPlane` and `Mat grayRaw`, lazily calls `OpenCVLoader.initDebug()`.
  - `stop()` — `handler.removeCallbacksAndMessages(null)`, `quitSafely()`, joins, releases `Mat`, drops `byte[]`.
- Frame intake:
  - `acceptsFrames()` returns `false` if currently processing (atomic flag) or thread not running.
  - `submitFrame(ByteBuffer nv21, int w, int h)` — copies the first `w*h` bytes (Y plane) into the reusable buffer and posts a `Runnable` to the handler. Drops if already busy.
- Wrapper-side state flag `userWantsReference` — distinguishes user-initiated reset (→ `idle`) from auto-reset-mid-tracking (→ `lost`), since `Trial32Tracker` clears `reference_frame` in both cases.
  - `setReference()` flips it true (after a successful reference capture).
  - `reset()` flips it false before calling `tracker.resetTrackingManual()`.
  - `process(frame)` does not touch it; auto-resets inside Trial32 leave it true.
- Controls (posted to handler so they happen between frames):
  - `setReference()` — if `last_gray.empty()` emits a synthetic `{ state: 'idle', statusMessage: 'Waiting for first frame' }` event; otherwise calls `tracker.setReferenceFromLastFrame()`, sets `userWantsReference = true`, emits a state event.
  - `reset()` — sets `userWantsReference = false`, calls `tracker.resetTrackingManual()`, emits.
- State classifier (single source of truth for the discrete state mapping):
  - `reference_frame == null && !userWantsReference` → `"idle"` (user-paused or never started).
  - `reference_frame == null && userWantsReference` → `"lost"` (tracker auto-reset during processing — slip detected, low entropy, etc.).
  - `reference_frame != null`: `ratio = distance / Trial32Tracker.DISTANCE_THRESHOLD` ; `ratio < 0.5` → `"tracking"` ; `< 0.8` → `"warning"` ; else → `"critical"`.
- Emission gate:
  - Track `lastEmittedState` and `lastEmitNanos`.
  - Emit if state changed OR `≥ 200 ms` since last emit.
- Listener interface:
  - `Listener.onTrackingEvent(SlipTrackerEvent)` — called on the tracker thread; consumer (RNCameraView) forwards to RN event bridge.

### New: `SlipTrackerEvent.java` — `org.reactnative.sliptracker`

Plain POJO carrying the four payload fields. Decouples Trial32 internals from the JS-bound shape.

```java
public final class SlipTrackerEvent {
    public final String state;        // "idle"|"tracking"|"warning"|"critical"|"lost"
    public final int distance;        // pixels from ref center
    public final double scale;        // last_scale_est
    public final String statusMessage; // nullable
}
```

### Touched: `AbstractUVCCameraHandler.java` — `com.serenegiant.usbcameracommon` (in `usbCameraCommon` module)

`mUVCCamera` is private inside the handler's inner thread class; `setFrameCallback(...)` is not exposed to external callers. Add a thread-safe public method:

```java
public void setExternalFrameCallback(final IFrameCallback callback, final int pixelFormat) {
    // posts to the internal handler thread; calls mUVCCamera.setFrameCallback(callback, pixelFormat)
    // null callback clears it
}
```

The handler also installs its own frame callback during video recording (`AbstractUVCCameraHandler.java:789`). To keep things simple and avoid a multiplexer:
- **Tracking and video recording are mutually exclusive in this iteration.** Document this. Starting recording while tracking is active emits one `{ statusMessage: 'Tracking paused during recording' }` event and pauses frame dispatch to the tracker until recording stops.
- Implementation: `AbstractUVCCameraHandler` tracks whether an external callback is installed and re-applies it when `handleStopRecording` resets the callback to null.

### Touched: `CameraUvc.java` — `com.google.android.cameraview`

- New field `IFrameCallback mTrackingFrameCallback`.
- New methods `enableTrackingFrames(IFrameCallback)` / `disableTrackingFrames()`:
  - Register/unregister via `mCameraHandler.setExternalFrameCallback(cb, UVCCamera.PIXEL_FORMAT_NV21)` (the new method added on `AbstractUVCCameraHandler` above).
  - Wrapped in try/catch — if NV21 isn't supported by the device firmware (`IllegalArgumentException` from JNI), log once and leave the handler-thread idle (tracker emits one `{ statusMessage: 'Camera does not support NV21 frames' }` and stays at `idle`).
  - If called before the camera is open, set a pending flag and register inside the existing `mCameraDeviceCallback.onOpen` block (`CameraUvc.java:98–115`).
- `closeCamera()` adds an unconditional `mCameraHandler.setExternalFrameCallback(null, 0)`.
- **Frame size note:** preview is configured at 1920×1080 (`CameraUvc.java:65–71`), so each NV21 frame's Y plane is 1920 × 1080 = ~2 MB. The tracker internally resizes to 960×720 inside `Trial32Tracker.process` (unchanged from the standalone port), so the algorithmic behavior is identical.

### Touched: `RNCameraView.java` — `org.reactnative.camera`

- New fields: `RNSlipTracker mSlipTracker` (lazy), `boolean mTrackingEnabled`.
- New setter `setTrackingEnabled(boolean)`:
  - false → if tracker exists, `stop()`, null out, call `CameraUvc.disableTrackingFrames()`.
  - true → create tracker if null, `start()`, register an `IFrameCallback` that calls `mSlipTracker.submitFrame(...)` if `acceptsFrames()`.
- New methods invoked by view manager:
  - `setSlipReference()` → forwards to tracker (or no-ops if `mTrackingEnabled == false`).
  - `resetSlipTracker()` → forwards to tracker.
- Implements `RNSlipTracker.Listener.onTrackingEvent(...)` → forwards to `RNCameraViewHelper.emitTrackingEvent(this, ev)`.
- Lifecycle hooks: `onHostPause()` → `mSlipTracker.stop()` (if running); `onHostResume()` → restart if `mTrackingEnabled`; `onHostDestroy()` → unconditional `stop()`.

### Touched: `RNCameraViewHelper.java`

- New `emitTrackingEvent(ViewGroup view, SlipTrackerEvent ev)` — builds a `WritableMap` (`state`, `distance`, `scale`, `statusMessage`) and dispatches via `RCTEventEmitter.receiveEvent(view.getId(), CameraViewManager.Events.EVENT_ON_TRACKING_FRAME.toString(), map)` — same pattern as the other emitters in this file.

### Touched: `CameraViewManager.java`

- Add `EVENT_ON_TRACKING_FRAME("onTrackingFrame")` to the `Events` enum (`CameraViewManager.java:16–35`). This is the single source of truth for event names in this library — `Constants.java` is unrelated (it only holds camera config constants, not events).
- The existing `getExportedCustomDirectEventTypeConstants` (`CameraViewManager.java:58`) iterates the enum, so the new event is registered automatically once added to the enum. **Direct event, not bubbling** — matches every other event in this library.
- Register the prop:
  ```java
  @ReactProp(name = "trackingEnabled")
  public void setTrackingEnabled(RNCameraView view, boolean enabled) {
      view.setTrackingEnabled(enabled);
  }
  ```

### Touched: `CameraModule.java`

This library does **not** use the React Native view-manager command pattern (`getCommandsMap` / `receiveCommand` / `dispatchViewManagerCommand`). Imperative view-targeted operations are exposed as `@ReactMethod`s on `CameraModule` that take a `viewTag` and resolve the `RNCameraView` via `UIManagerModule.addUIBlock(...).resolveView(viewTag)` — see existing `takePicture` (`CameraModule.java:183`), `record`, `stopRecording`, `getSupportedRatios`.

Add two parallel methods following the existing pattern:

```java
@ReactMethod
public void setSlipReference(final int viewTag) {
    // resolveView(viewTag) → RNCameraView, call view.setSlipReference()
}

@ReactMethod
public void resetSlipTracker(final int viewTag) {
    // resolveView(viewTag) → RNCameraView, call view.resetSlipTracker()
}
```

No `Promise` — both methods are fire-and-forget (mirrors `stopRecording`).

### Touched: `android/build.gradle`

- `implementation 'org.opencv:opencv:4.13.0'`.

### Touched: `src/UvcCamera.js`

- Add `trackingEnabled: PropTypes.bool` and `onTrackingFrame: PropTypes.func` to `propTypes`.
- Add ref methods using the existing `CameraManager` (`NativeModules.UvcCameraModule`) pattern — same as `takePictureAsync` (`src/UvcCamera.js:244–251`):
  ```js
  setSlipReference = () => CameraManager.setSlipReference(this._cameraHandle);
  resetSlipTracker = () => CameraManager.resetSlipTracker(this._cameraHandle);
  ```
  Both fire-and-forget, no `Promise`. Calls before the view mounts (`this._cameraHandle == null`) silently no-op.
- Route the native `onTrackingFrame` event to the prop in the same way existing detector events are routed (e.g. `_onFacesDetected`).

## Data flow (per frame)

```
USB camera (MJPEG @ 30 fps)
  │
  ▼  UVCCameraHandler decodes on its own thread
IFrameCallback.onFrame(ByteBuffer nv21)        ← UVC callback thread
  │
  │  if !tracker.acceptsFrames()  → return    (drop-on-busy, microseconds)
  │  copy first w*h bytes into reused yPlane  (~2 MB at 1920×1080, sequential memcpy)
  │  handler.post(processRunnable)             (reused Runnable, no allocation)
  │
  ▼  callback returns immediately
SlipTrackerThread (HandlerThread)              ← single dedicated worker
  │
  │  busy = true
  │  grayRaw.put(0, 0, yPlane)                 (reuse Mat)
  │  FrameState = Trial32Tracker.process(grayRaw)
  │  state = classify(reference_set, distance)
  │  if (state != lastEmittedState) || (now - lastEmit ≥ 200 ms):
  │     listener.onTrackingEvent(event)
  │     lastEmittedState = state; lastEmit = now
  │  busy = false
  │
  ▼
RNCameraView (listener runs on tracker thread)
  │  RNCameraViewHelper.emitTrackingEvent(this, ev)
  │     → ReactContext.getJSModule(RCTEventEmitter).receiveEvent(...)
  │
  ▼
JS: onTrackingFrame({ state, distance, scale, statusMessage })
```

## Allocation budget (hot path, per frame)

- 1× `byte[]` copy of Y plane (1920 × 1080 = ~2 MB) into a **reused** buffer — zero new allocations. The Y plane copy is a single sequential `ByteBuffer.get` and takes <1 ms even on mid-range hardware.
- 1× `Mat.put` into a **reused** 1920×1080 `grayRaw` Mat — zero new allocations.
- The tracker's first step is `Imgproc.resize` to 960×720 (an explicit step inside `Trial32Tracker.process`), so all subsequent OpenCV work happens at the smaller size.
- Inside `Trial32Tracker.process`: several short-lived `Mat` instances (homography mask, phase-correlation downsample, etc.), each explicitly `.release()`'d in the same method — already correct in the verbatim port.
- Emission path: 1× `WritableMap` only when emitting (max ~5/s + state transitions).

## Threading invariants

- UVC callback thread is never blocked beyond a `byte[].get` + `handler.post` — sub-millisecond.
- All Trial32 state mutation happens on a single thread (the tracker `HandlerThread`) → no synchronization needed inside `Trial32Tracker`.
- `setReference()` / `reset()` are also posted to the tracker thread → safe, ordered with frame processing.
- `start()` / `stop()` are called from RN's UI thread; `quitSafely()` + `join(500ms)` guarantee clean shutdown.

## JS API

### Props

```js
<UvcCamera
  trackingEnabled={true}              // bool, default false
  onTrackingFrame={(e) => {...}}      // optional
/>
```

### Event payload

```js
{
  state: 'idle' | 'tracking' | 'warning' | 'critical' | 'lost',
  distance: 142,        // integer pixels
  scale: 1.03,          // double, last_scale_est
  statusMessage: null,  // string when tracker sets one, else null
}
```

### Imperative ref methods

```js
cameraRef.current.setSlipReference();   // fire-and-forget
cameraRef.current.resetSlipTracker();   // fire-and-forget
```

### UX mapping (consumer-side, illustrative)

| `state` | Border | Alert |
|---|---|---|
| `idle` | none | — |
| `tracking` | blue `#2A547E` | — |
| `warning` | amber `#FFAF03` | — |
| `critical` | red `#FF0000` | "Treatment Paused — Coil Off Target", auto-hide 10 s |
| `lost` | red `#FF0000` | same as `critical` |

JS only re-renders on `state` transitions (heartbeat carries fresh numbers but if `state` is unchanged React skips re-render).

## Error handling & edge cases

| Case | Behavior |
|---|---|
| OpenCV `initDebug()` returns false | Emit one `{ state: 'idle', statusMessage: 'OpenCV failed to initialize' }`. `submitFrame` becomes a no-op. No retries, no crash. |
| Device firmware rejects NV21 frame callback | Log once at `Log.w`. Tracker thread stays running but starved. Emit one `{ state: 'idle', statusMessage: 'Camera does not support NV21 frames' }`. No Bitmap fallback (additive follow-up). |
| `trackingEnabled = true` before camera is open | Defer registration: set pending flag; `CameraUvc.onOpen` callback registers the NV21 callback when the camera actually opens. |
| USB disconnect mid-frame | `frame.remaining() < ySize` guard returns from the callback without posting. `closeCamera()` explicitly calls `mCameraHandler.setExternalFrameCallback(null, 0)` before the existing `releaseCamera()` so libuvc never invokes a stale callback. |
| `setSlipReference()` before any frame arrives | Synthetic event `{ state: 'idle', statusMessage: 'Waiting for first frame' }`. Tracker state unchanged. |
| Rapid toggle off/on | Prop setter on UI thread serializes; `removeCallbacksAndMessages(null)` + `quitSafely` + `join(500ms)` guarantee clean shutdown before restart. Reference is **not** preserved across off/on. |
| Tracker auto-reset (low entropy, scale outlier, lost consensus, large jump, …) | Trial32 sets a `last_status_message`. Wrapper classifies as `lost`, forwards the message via `statusMessage`. |
| View destroyed | `onHostDestroy` → unconditional `stop()`. Tracker reference dropped. |
| Video recording starts while tracking active | `AbstractUVCCameraHandler.handleStartRecording` already installs its own NV21 frame callback for the encoder, which would clobber the tracker's. Tracking pauses for the duration of recording: emit one `{ statusMessage: 'Tracking paused during recording' }`, suppress `submitFrame`. When `handleStopRecording` clears the recording callback, `AbstractUVCCameraHandler` re-applies the saved external callback, and a `{ statusMessage: 'Tracking resumed' }` event fires. |

## Lifecycle ↔ existing patterns

- Mounting without `trackingEnabled` → zero overhead (no thread, no OpenCV symbols touched beyond static class load).
- Toggling true → false → resources released; toggling back true → fresh start, no preserved reference (matches "off means off" mental model).
- App backgrounded → `onHostPause` stops the tracker. App foregrounded → `onHostResume` restarts iff prop is still true.

## Testing

The library has no automated test suite (per `CLAUDE.md`). Testing strategy is structural + manual.

### Structural / compile
- `./gradlew :react-native-uvc-camera:assembleDebug` must succeed.
- `./gradlew lint` introduces no new errors.
- AAR contains OpenCV `.so` files for both `armeabi-v7a` and `arm64-v8a`.

### Manual verification (on device with USB camera attached)
1. **Baseline preservation** — mount without `trackingEnabled`. Preview works as before; no tracking events; no extra threads.
2. **Enable path** — set `trackingEnabled={true}`. "SlipTrackerThread" appears in profiler; no events until `setSlipReference()` is called.
3. **State transitions** — physically move the camera and observe transitions: small motion stays `tracking`; moderate → `warning`; large → `critical`; abrupt large jump → `lost` with a `statusMessage`.
4. **Reset path** — `resetSlipTracker()` returns to `idle`; next `setSlipReference()` resumes.
5. **Disable path** — `trackingEnabled={false}` stops events; "SlipTrackerThread" disappears; preview unaffected.
6. **Unplug** — pull camera mid-tracking; no crash; events stop.
7. **Performance** — 60 s of preview with tracking on: ~25–40 % busy tracker thread on mid-range hardware; stable heap; preview frame rate unchanged.

## Files

### New
- `android/src/main/java/org/reactnative/sliptracker/Trial32Tracker.java` (~1080 LOC, verbatim port + package change)
- `android/src/main/java/org/reactnative/sliptracker/RNSlipTracker.java` (~150 LOC)
- `android/src/main/java/org/reactnative/sliptracker/SlipTrackerEvent.java` (~15 LOC)

### Touched
- `android/build.gradle` — add OpenCV dependency; bump `minSdkVersion` 16 → 21
- `usbCameraCommon/src/main/java/com/serenegiant/usbcameracommon/AbstractUVCCameraHandler.java` — add `setExternalFrameCallback(IFrameCallback, int)`; remember and re-apply across recording start/stop
- `android/src/main/java/com/google/android/cameraview/CameraUvc.java` — `enableTrackingFrames` / `disableTrackingFrames` wrapping the new handler method; symmetric teardown in `closeCamera`; pending-flag plumbing through `onOpen`
- `android/src/main/java/org/reactnative/camera/RNCameraView.java` — own tracker; plumb prop, imperative methods, listener; lifecycle hooks (`onHostPause`/`onHostResume`/`onHostDestroy`)
- `android/src/main/java/org/reactnative/camera/CameraViewManager.java` — add `EVENT_ON_TRACKING_FRAME("onTrackingFrame")` to the `Events` enum; add `@ReactProp("trackingEnabled")`
- `android/src/main/java/org/reactnative/camera/CameraModule.java` — add `@ReactMethod setSlipReference(int viewTag)` and `@ReactMethod resetSlipTracker(int viewTag)` following the `takePicture`/`record` pattern
- `android/src/main/java/org/reactnative/camera/RNCameraViewHelper.java` — `emitTrackingEvent` builder + dispatch
- `src/UvcCamera.js` — `trackingEnabled` prop, `onTrackingFrame` event handler, `setSlipReference()`/`resetSlipTracker()` ref methods using existing `CameraManager` pattern

### Not touched (despite earlier draft)
- `Constants.java` — only holds camera config constants (FACING_*, FLASH_*, WB_*), not events. Events live in `CameraViewManager.Events`.

## Open follow-ups (not in scope, but tracked)

- Configurable thresholds from JS.
- Bitmap-path fallback when NV21 isn't supported.
- TypeScript definitions.
- Persisting the reference across process restarts.
