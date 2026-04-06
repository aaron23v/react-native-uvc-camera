package org.reactnative.camera.slip;

public class SlipResult {
    public final int distance;
    public final double scale;
    public final boolean isTracking;
    public final boolean didReset;
    public final String resetReason;
    public final int liveX;
    public final int liveY;
    public final int refX;
    public final int refY;

    public SlipResult(int distance, double scale, boolean isTracking, boolean didReset,
                      String resetReason, int liveX, int liveY, int refX, int refY) {
        this.distance = distance;
        this.scale = scale;
        this.isTracking = isTracking;
        this.didReset = didReset;
        this.resetReason = resetReason;
        this.liveX = liveX;
        this.liveY = liveY;
        this.refX = refX;
        this.refY = refY;
    }

    public static SlipResult notTracking() {
        return new SlipResult(0, 1.0, false, false, null, 0, 0, 0, 0);
    }
}
