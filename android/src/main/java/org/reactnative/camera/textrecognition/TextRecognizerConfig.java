package org.reactnative.camera.textrecognition;

/**
 * Configuration for text recognition engines.
 */
public class TextRecognizerConfig {

    /**
     * Available recognition engines.
     */
    public enum Engine {
        MLKIT("mlkit"),
        TFLITE("tflite");

        private final String value;

        Engine(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        /**
         * Convert string to Engine enum.
         *
         * @param text Engine name as string
         * @return Corresponding Engine enum, defaults to MLKIT if not found
         */
        public static Engine fromString(String text) {
            if (text == null) {
                return MLKIT;
            }

            for (Engine e : Engine.values()) {
                if (e.value.equalsIgnoreCase(text)) {
                    return e;
                }
            }
            return MLKIT; // Default fallback
        }
    }

    private Engine engine = Engine.MLKIT;
    private String modelPath;
    private float confidenceThreshold = 0.5f;
    private float iouThreshold = 0.5f;
    private boolean useGpu = true;

    public Engine getEngine() {
        return engine;
    }

    public void setEngine(Engine engine) {
        this.engine = engine;
    }

    public String getModelPath() {
        return modelPath;
    }

    public void setModelPath(String modelPath) {
        this.modelPath = modelPath;
    }

    public float getConfidenceThreshold() {
        return confidenceThreshold;
    }

    public void setConfidenceThreshold(float confidenceThreshold) {
        this.confidenceThreshold = confidenceThreshold;
    }

    public float getIouThreshold() {
        return iouThreshold;
    }

    public void setIouThreshold(float iouThreshold) {
        this.iouThreshold = iouThreshold;
    }

    public boolean isUseGpu() {
        return useGpu;
    }

    public void setUseGpu(boolean useGpu) {
        this.useGpu = useGpu;
    }
}
