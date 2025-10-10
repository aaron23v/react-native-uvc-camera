package org.reactnative.camera.textrecognition;

import android.content.Context;
import android.util.Log;

/**
 * Factory for creating text recognizer instances.
 * Supports MLKit and custom TFLite models.
 */
public class TextRecognizerFactory {
    private static final String TAG = "TextRecognizerFactory";

    /**
     * Create a text recognizer based on configuration.
     *
     * @param context Android context
     * @param config Recognizer configuration
     * @return Configured text recognizer instance
     */
    public static BaseTextRecognizer create(Context context, TextRecognizerConfig config) {
        TextRecognizerConfig.Engine engine = config.getEngine();
        Log.d(TAG, "Creating text recognizer: " + engine.getValue());

        switch (engine) {
            case TFLITE:
                Log.d(TAG, String.format("TFLite config: confidence=%.2f, iou=%.2f, gpu=%b",
                    config.getConfidenceThreshold(),
                    config.getIouThreshold(),
                    config.isUseGpu()));
                return new TFLiteTextRecognizer(context, config);

            case MLKIT:
            default:
                return new MLKitTextRecognizer();
        }
    }
}
