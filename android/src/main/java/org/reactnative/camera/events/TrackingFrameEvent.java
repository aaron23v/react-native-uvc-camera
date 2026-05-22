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
