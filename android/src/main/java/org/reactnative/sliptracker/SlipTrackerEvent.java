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
