package org.reactnative.camera.textrecognition;

import android.graphics.Bitmap;
import com.facebook.react.bridge.WritableArray;

/**
 * Base interface for text recognition engines.
 * Supports both MLKit and custom TFLite models.
 */
public interface BaseTextRecognizer {

    /**
     * Process an image frame for text recognition.
     *
     * @param image The bitmap image to process
     * @param rotation The rotation angle in degrees
     * @param listener Callback for recognition results
     */
    void process(Bitmap image, int rotation, OnTextRecognizedListener listener);

    /**
     * Release resources held by the recognizer.
     * Must be called when recognizer is no longer needed.
     */
    void release();

    /**
     * Get the name of the recognition engine.
     *
     * @return Engine name (e.g., "MLKit", "TFLite")
     */
    String getEngineName();

    /**
     * Callback interface for text recognition results.
     */
    interface OnTextRecognizedListener {
        /**
         * Called when recognition succeeds.
         *
         * @param textBlocks Array of recognized text blocks with metadata
         * @param concatenatedText All detected text concatenated into a single string
         */
        void onSuccess(WritableArray textBlocks, String concatenatedText);

        /**
         * Called when recognition fails.
         *
         * @param e The exception that caused the failure
         */
        void onFailure(Exception e);
    }
}
