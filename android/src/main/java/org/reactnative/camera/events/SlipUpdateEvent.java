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
    private int mLiveX;
    private int mLiveY;
    private int mRefX;
    private int mRefY;
    private int mFrameWidth;
    private int mFrameHeight;

    private SlipUpdateEvent() {}

    public static SlipUpdateEvent obtain(int viewTag, int distance, double scale,
                                          boolean isTracking, int threshold,
                                          boolean didReset, String resetReason,
                                          int liveX, int liveY, int refX, int refY,
                                          int frameWidth, int frameHeight) {
        SlipUpdateEvent event = EVENTS_POOL.acquire();
        if (event == null) {
            event = new SlipUpdateEvent();
        }
        event.init(viewTag, distance, scale, isTracking, threshold, didReset, resetReason,
                   liveX, liveY, refX, refY, frameWidth, frameHeight);
        return event;
    }

    private void init(int viewTag, int distance, double scale, boolean isTracking,
                      int threshold, boolean didReset, String resetReason,
                      int liveX, int liveY, int refX, int refY,
                      int frameWidth, int frameHeight) {
        super.init(viewTag);
        mDistance = distance;
        mScale = scale;
        mIsTracking = isTracking;
        mThreshold = threshold;
        mDidReset = didReset;
        mResetReason = resetReason;
        mLiveX = liveX;
        mLiveY = liveY;
        mRefX = refX;
        mRefY = refY;
        mFrameWidth = frameWidth;
        mFrameHeight = frameHeight;
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
        event.putInt("liveX", mLiveX);
        event.putInt("liveY", mLiveY);
        event.putInt("refX", mRefX);
        event.putInt("refY", mRefY);
        event.putInt("frameWidth", mFrameWidth);
        event.putInt("frameHeight", mFrameHeight);
        return event;
    }
}
