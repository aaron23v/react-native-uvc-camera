package org.reactnative.sliptracker;

import android.util.Log;
import androidx.annotation.Nullable;

/**
 * Always-on debug logger for the slip-tracker pipeline.
 * Tag matches {@code RNSlipTracker.TAG} so existing logcat filters capture it.
 * Remove (along with all call sites) once the pipeline is verified in production.
 */
public final class SlipTrackerDebug {
    public static final String TAG = "RNSlipTracker";
    /** Sample cadence for per-frame traces (~1 Hz at 30 fps). */
    public static final int LOG_EVERY_N_FRAMES = 30;

    public static void d(String msg) { Log.d(TAG, msg); }
    public static void d(String msg, @Nullable Throwable t) { Log.d(TAG, msg, t); }
    public static void i(String msg) { Log.i(TAG, msg); }
    public static void w(String msg) { Log.w(TAG, msg); }

    private SlipTrackerDebug() {}
}
