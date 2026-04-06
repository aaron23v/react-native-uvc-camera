package org.reactnative.camera.slip;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

public class SlipOverlayRenderer {
    private static final int MARKER_SIZE = 12;
    private static final int DISTANCE_THRESHOLD = 260;

    private final Paint refMarkerPaint;
    private final Paint liveMarkerPaint;
    private final Paint arrowPaint;
    private final Paint redOverlayPaint;

    public SlipOverlayRenderer() {
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

    private int getDistanceColor(int distance) {
        double ratio = (double) distance / DISTANCE_THRESHOLD;
        if (ratio < 0.5) return Color.GREEN;
        else if (ratio < 0.8) return Color.YELLOW;
        else return Color.RED;
    }

    public void draw(Bitmap bitmap, SlipResult result, int overlayCounter) {
        if (!result.isTracking) return;

        Canvas canvas = new Canvas(bitmap);

        // Reference marker (blue circle at center)
        canvas.drawCircle(result.refX, result.refY, MARKER_SIZE, refMarkerPaint);

        // Live tracking point and arrow
        if (result.distance > 0) {
            canvas.drawCircle(result.liveX, result.liveY, MARKER_SIZE, liveMarkerPaint);

            int color = getDistanceColor(result.distance);
            arrowPaint.setColor(color);
            arrowPaint.setStyle(Paint.Style.STROKE);

            // Draw line
            canvas.drawLine(result.liveX, result.liveY, result.refX, result.refY, arrowPaint);

            // Draw arrowhead pointing toward refCenter
            drawArrowhead(canvas, result.liveX, result.liveY, result.refX, result.refY, color);
        }

        // Red flash overlay on reset
        if (overlayCounter > 0) {
            int alpha = (int)(0.4 * 255 * ((double) overlayCounter / 20));
            redOverlayPaint.setAlpha(Math.min(alpha, 102));
            canvas.drawRect(0, 0, bitmap.getWidth(), bitmap.getHeight(), redOverlayPaint);
        }
    }

    private void drawArrowhead(Canvas canvas, int fromX, int fromY, int toX, int toY, int color) {
        double angle = Math.atan2(toY - fromY, toX - fromX);
        double lineLen = Math.sqrt(Math.pow(toX - fromX, 2) + Math.pow(toY - fromY, 2));
        if (lineLen < 20) return;

        double tipRatio = 0.7;
        double tipX = fromX + (toX - fromX) * tipRatio;
        double tipY = fromY + (toY - fromY) * tipRatio;

        double arrowLen = 15;
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
