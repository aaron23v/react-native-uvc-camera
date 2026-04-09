package org.reactnative.camera.slip;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

public class SlipOverlayView extends View {
    private static final int MARKER_SIZE = 12;
    private static final int DISTANCE_THRESHOLD = 260;

    private final Paint refMarkerPaint;
    private final Paint liveMarkerPaint;
    private final Paint arrowPaint;
    private final Paint redOverlayPaint;

    private SlipResult currentResult;
    private int overlayCounter = 0;

    // Scale factors: camera frame coords → view coords
    private int frameWidth = 0;
    private int frameHeight = 0;

    public SlipOverlayView(Context context) {
        super(context);
        setWillNotDraw(false);
        setBackgroundColor(Color.TRANSPARENT);

        refMarkerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        refMarkerPaint.setColor(Color.BLUE);
        refMarkerPaint.setStyle(Paint.Style.FILL);

        liveMarkerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        liveMarkerPaint.setColor(Color.GREEN);
        liveMarkerPaint.setStyle(Paint.Style.FILL);

        arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arrowPaint.setStrokeWidth(3f);
        arrowPaint.setStyle(Paint.Style.STROKE);

        redOverlayPaint = new Paint();
        redOverlayPaint.setColor(Color.RED);
    }

    public void updateResult(SlipResult result, int overlayCounter, int frameW, int frameH) {
        this.currentResult = result;
        this.overlayCounter = overlayCounter;
        this.frameWidth = frameW;
        this.frameHeight = frameH;
        postInvalidate();
    }

    private float scaleX(int frameCoord) {
        if (frameWidth <= 0) return frameCoord;
        return (float) frameCoord / frameWidth * getWidth();
    }

    private float scaleY(int frameCoord) {
        if (frameHeight <= 0) return frameCoord;
        return (float) frameCoord / frameHeight * getHeight();
    }

    private float scaleSize(int size) {
        if (frameWidth <= 0) return size;
        return (float) size / frameWidth * getWidth();
    }

    private int getDistanceColor(int distance) {
        double ratio = (double) distance / DISTANCE_THRESHOLD;
        if (ratio < 0.5) return Color.GREEN;
        else if (ratio < 0.8) return Color.YELLOW;
        else return Color.RED;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (currentResult == null || !currentResult.isTracking) return;

        float refX = scaleX(currentResult.refX);
        float refY = scaleY(currentResult.refY);
        float markerSize = scaleSize(MARKER_SIZE);

        // Reference marker (blue circle at center)
        canvas.drawCircle(refX, refY, markerSize, refMarkerPaint);

        // Live tracking point and arrow
        if (currentResult.distance > 0) {
            float liveX = scaleX(currentResult.liveX);
            float liveY = scaleY(currentResult.liveY);

            canvas.drawCircle(liveX, liveY, markerSize, liveMarkerPaint);

            int color = getDistanceColor(currentResult.distance);
            arrowPaint.setColor(color);
            arrowPaint.setStyle(Paint.Style.STROKE);
            arrowPaint.setStrokeWidth(scaleSize(3));

            canvas.drawLine(liveX, liveY, refX, refY, arrowPaint);
            drawArrowhead(canvas, liveX, liveY, refX, refY, color);
        }

        // Red flash overlay on reset
        if (overlayCounter > 0) {
            int alpha = (int)(0.4 * 255 * ((double) overlayCounter / 20));
            redOverlayPaint.setAlpha(Math.min(alpha, 102));
            canvas.drawRect(0, 0, getWidth(), getHeight(), redOverlayPaint);
        }
    }

    private void drawArrowhead(Canvas canvas, float fromX, float fromY, float toX, float toY, int color) {
        double angle = Math.atan2(toY - fromY, toX - fromX);
        double lineLen = Math.sqrt(Math.pow(toX - fromX, 2) + Math.pow(toY - fromY, 2));
        if (lineLen < 20) return;

        double tipRatio = 0.7;
        double tipX = fromX + (toX - fromX) * tipRatio;
        double tipY = fromY + (toY - fromY) * tipRatio;

        double arrowLen = scaleSize(15);
        double arrowAngle = Math.toRadians(25);

        float x1 = (float)(tipX - arrowLen * Math.cos(angle - arrowAngle));
        float y1 = (float)(tipY - arrowLen * Math.sin(angle - arrowAngle));
        float x2 = (float)(tipX - arrowLen * Math.cos(angle + arrowAngle));
        float y2 = (float)(tipY - arrowLen * Math.sin(angle + arrowAngle));

        Paint headPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        headPaint.setColor(color);
        headPaint.setStyle(Paint.Style.FILL);

        Path path = new Path();
        path.moveTo((float) tipX, (float) tipY);
        path.lineTo(x1, y1);
        path.lineTo(x2, y2);
        path.close();
        canvas.drawPath(path, headPaint);
    }
}
