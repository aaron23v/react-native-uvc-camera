package org.reactnative.camera.events;

import androidx.core.util.Pools;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.uimanager.events.Event;
import com.facebook.react.uimanager.events.RCTEventEmitter;

import org.reactnative.camera.CameraViewManager;

public class SlipUpdateEvent extends Event<SlipUpdateEvent> {

    private static final Pools.SynchronizedPool<SlipUpdateEvent> EVENTS_POOL =
        new Pools.SynchronizedPool<>(5);

    private int mDistance;
    private double mScale;
    private boolean mIsTracking;
    private int mThreshold;
    private boolean mDidReset;
    private String mResetReason;

    private SlipUpdateEvent() {}

    public static SlipUpdateEvent obtain(int viewTag, int distance, double scale,
                                          boolean isTracking, int threshold,
                                          boolean didReset, String resetReason) {
        SlipUpdateEvent event = EVENTS_POOL.acquire();
        if (event == null) {
            event = new SlipUpdateEvent();
        }
        event.init(viewTag, distance, scale, isTracking, threshold, didReset, resetReason);
        return event;
    }

    private void init(int viewTag, int distance, double scale, boolean isTracking,
                      int threshold, boolean didReset, String resetReason) {
        super.init(viewTag);
        mDistance = distance;
        mScale = scale;
        mIsTracking = isTracking;
        mThreshold = threshold;
        mDidReset = didReset;
        mResetReason = resetReason;
    }

    @Override
    public String getEventName() {
        return CameraViewManager.Events.EVENT_ON_SLIP_UPDATE.toString();
    }

    @Override
    public void dispatch(RCTEventEmitter rctEventEmitter) {
        rctEventEmitter.receiveEvent(getViewTag(), getEventName(), serializeEventData());
    }

    private WritableMap serializeEventData() {
        WritableMap event = Arguments.createMap();
        event.putInt("distance", mDistance);
        event.putDouble("scale", mScale);
        event.putBoolean("isTracking", mIsTracking);
        event.putInt("threshold", mThreshold);
        event.putBoolean("didReset", mDidReset);
        if (mResetReason != null) {
            event.putString("resetReason", mResetReason);
        }
        return event;
    }
}
