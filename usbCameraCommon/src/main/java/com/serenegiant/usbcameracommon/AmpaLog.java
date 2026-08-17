package com.serenegiant.usbcameracommon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mirrors android.util.Log calls into the SLF4J root logger so they land in the same
 * rolling file react-native-file-logger writes JS logs to (see FileLoggerModule#renewAppender,
 * which attaches its appender to the SLF4J root logger process-wide).
 * Only call this from lifecycle/error paths, never per-frame/per-buffer hot paths.
 */
public final class AmpaLog {
    private static final Logger logger = LoggerFactory.getLogger("AMPA-UVC");

    public static void d(String tag, String msg) {
        android.util.Log.d(tag, msg);
        logger.debug(tag + ": " + msg);
    }

    public static void i(String tag, String msg) {
        android.util.Log.i(tag, msg);
        logger.info(tag + ": " + msg);
    }

    public static void w(String tag, String msg) {
        android.util.Log.w(tag, msg);
        logger.warn(tag + ": " + msg);
    }

    public static void w(String tag, String msg, Throwable t) {
        android.util.Log.w(tag, msg, t);
        logger.warn(tag + ": " + msg, t);
    }

    public static void e(String tag, String msg) {
        android.util.Log.e(tag, msg);
        logger.error(tag + ": " + msg);
    }

    public static void e(String tag, String msg, Throwable t) {
        android.util.Log.e(tag, msg, t);
        logger.error(tag + ": " + msg, t);
    }

    private AmpaLog() {}
}
