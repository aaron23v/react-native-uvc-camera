package org.reactnative.camera.textrecognition;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * TensorFlow Lite text recognizer for custom EEG electrode detection model.
 * Implements rotated bounding box detection with NMS.
 */
public class TFLiteTextRecognizer implements BaseTextRecognizer {
    private static final String TAG = "TFLiteTextRecognizer";

    // Model constants (from camera.py)
    private static final int NUM_CLASSES = 13;
    private static final String[] LABELS = {
        "A2", "C1", "A3", "A4", "B4", "C4",
        "AF7", "AF8", "AFZ", "FP1", "FP2", "FZ", "PF3"
    };

    private Interpreter interpreter;
    private GpuDelegate gpuDelegate;
    private int inputWidth;
    private int inputHeight;
    private float confidenceThreshold;
    private float iouThreshold;

    public TFLiteTextRecognizer(Context context, TextRecognizerConfig config) {
        this.confidenceThreshold = config.getConfidenceThreshold();
        this.iouThreshold = config.getIouThreshold();

        try {
            // Load model
            String modelPath = config.getModelPath() != null
                ? config.getModelPath()
                : "models/best_float32.tflite";

            MappedByteBuffer modelBuffer = loadModelFile(context, modelPath);

            // Configure interpreter with GPU if available
            Interpreter.Options options = new Interpreter.Options();

            if (config.isUseGpu()) {
                CompatibilityList compatList = new CompatibilityList();

                if (compatList.isDelegateSupportedOnThisDevice()) {
                    GpuDelegate.Options delegateOptions = compatList.getBestOptionsForThisDevice();
                    gpuDelegate = new GpuDelegate(delegateOptions);
                    options.addDelegate(gpuDelegate);
                    Log.d(TAG, "GPU acceleration enabled");
                } else {
                    options.setNumThreads(4);
                    Log.d(TAG, "GPU not available, using CPU with 4 threads");
                }
            } else {
                options.setNumThreads(4);
                Log.d(TAG, "Using CPU with 4 threads");
            }

            interpreter = new Interpreter(modelBuffer, options);

            // Get input dimensions
            int[] inputShape = interpreter.getInputTensor(0).shape();
            inputHeight = inputShape[1];
            inputWidth = inputShape[2];

            Log.d(TAG, String.format("Model loaded: %dx%d, confidence=%.2f, iou=%.2f",
                inputWidth, inputHeight, confidenceThreshold, iouThreshold));

        } catch (IOException e) {
            Log.e(TAG, "Failed to load TFLite model", e);
        }
    }

    @Override
    public void process(Bitmap image, int rotation, OnTextRecognizedListener listener) {
        if (interpreter == null) {
            listener.onFailure(new IllegalStateException("Interpreter not initialized"));
            return;
        }

        try {
            long startTime = System.currentTimeMillis();

            // 1. Preprocess image (Python lines 16-22)
            Bitmap resizedImage = Bitmap.createScaledBitmap(image, inputWidth, inputHeight, true);
            ByteBuffer inputBuffer = bitmapToByteBuffer(resizedImage);

            // 2. Run inference (Python lines 25-27)
            float[][] outputs = new float[1][18 * 8400]; // [batch, features * detections]
            interpreter.run(inputBuffer, outputs);

            // 3. Post-process outputs (Python lines 29-62)
            List<DetectionBox> boxes = postProcessOutputs(
                outputs[0],
                image.getWidth(),
                image.getHeight()
            );

            // 4. Apply NMS (Python lines 64-92)
            List<DetectionBox> filteredBoxes = applyNMS(boxes);

            // 5. Convert to React Native format
            WritableArray textBlocks = convertToTextBlocks(filteredBoxes);

            long endTime = System.currentTimeMillis();
            Log.d(TAG, String.format("Inference complete: %dms, detected %d objects",
                endTime - startTime, filteredBoxes.size()));

            listener.onSuccess(textBlocks);

        } catch (Exception e) {
            Log.e(TAG, "TFLite inference failed", e);
            listener.onFailure(e);
        }
    }

    /**
     * Convert bitmap to normalized float buffer (Python line 21)
     */
    private ByteBuffer bitmapToByteBuffer(Bitmap bitmap) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(4 * inputWidth * inputHeight * 3);
        buffer.order(ByteOrder.nativeOrder());

        int[] pixels = new int[inputWidth * inputHeight];
        bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight);

        // Normalize to [0, 1] (Python line 21: / 255.0)
        for (int pixel : pixels) {
            buffer.putFloat(((pixel >> 16) & 0xFF) / 255.0f); // R
            buffer.putFloat(((pixel >> 8) & 0xFF) / 255.0f);  // G
            buffer.putFloat((pixel & 0xFF) / 255.0f);         // B
        }

        return buffer;
    }

    /**
     * Post-process model outputs to extract detections (Python lines 29-62)
     */
    private List<DetectionBox> postProcessOutputs(float[] outputs, int imageWidth, int imageHeight) {
        List<DetectionBox> boxes = new ArrayList<>();

        int numElements = outputs.length / 18; // 8400 detections

        for (int i = 0; i < numElements; i++) {
            // Extract class scores (Python lines 34-36)
            float[] classScores = new float[NUM_CLASSES];
            for (int c = 0; c < NUM_CLASSES; c++) {
                classScores[c] = outputs[(4 + c) * numElements + i];
            }

            // Find max score (Python lines 35-36)
            int maxIdx = 0;
            float maxScore = classScores[0];
            for (int c = 1; c < NUM_CLASSES; c++) {
                if (classScores[c] > maxScore) {
                    maxScore = classScores[c];
                    maxIdx = c;
                }
            }

            // Filter by confidence threshold (Python line 38)
            if (maxScore > confidenceThreshold) {
                // Extract box parameters (Python lines 39-43)
                float cx = outputs[i];
                float cy = outputs[numElements + i];
                float w = outputs[2 * numElements + i];
                float h = outputs[3 * numElements + i];
                float angle = outputs[17 * numElements + i];

                // Angle adjustment (Python lines 45-47)
                if (angle >= 0.5 * Math.PI && angle <= 0.75 * Math.PI) {
                    angle -= Math.PI;
                }

                // Calculate corners (Python lines 49-52)
                float x1 = cx - w/2;
                float y1 = cy - h/2;
                float x2 = cx + w/2;
                float y2 = cy + h/2;

                // Boundary check (Python line 54)
                if (x1 >= 0 && y1 >= 0 && x2 <= 1 && y2 <= 1) {
                    boxes.add(new DetectionBox(
                        cx, cy, w, h, angle,
                        maxScore, maxIdx, LABELS[maxIdx],
                        imageWidth, imageHeight
                    ));
                }
            }
        }

        Log.d(TAG, String.format("Post-processing: %d detections before NMS", boxes.size()));
        return boxes;
    }

    /**
     * Apply Non-Maximum Suppression (Python lines 64-92)
     */
    private List<DetectionBox> applyNMS(List<DetectionBox> boxes) {
        if (boxes.isEmpty()) {
            return new ArrayList<>();
        }

        // Sort by confidence descending (Python line 68)
        Collections.sort(boxes, new Comparator<DetectionBox>() {
            @Override
            public int compare(DetectionBox a, DetectionBox b) {
                return Float.compare(b.confidence, a.confidence);
            }
        });

        List<DetectionBox> selected = new ArrayList<>();

        // NMS algorithm (Python lines 71-75)
        for (DetectionBox box : boxes) {
            boolean shouldSelect = true;

            for (DetectionBox selectedBox : selected) {
                if (computeIoU(box, selectedBox) >= iouThreshold) {
                    shouldSelect = false;
                    break;
                }
            }

            if (shouldSelect) {
                selected.add(box);
            }
        }

        Log.d(TAG, String.format("NMS: %d -> %d detections", boxes.size(), selected.size()));
        return selected;
    }

    /**
     * Compute Intersection over Union (Python lines 77-90)
     */
    private float computeIoU(DetectionBox box1, DetectionBox box2) {
        // Intersection coordinates (Python lines 79-82)
        float x1Inter = Math.max(box1.x1, box2.x1);
        float y1Inter = Math.max(box1.y1, box2.y1);
        float x2Inter = Math.min(box1.x2, box2.x2);
        float y2Inter = Math.min(box1.y2, box2.y2);

        // Intersection area (Python line 84)
        float interArea = Math.max(0, x2Inter - x1Inter) * Math.max(0, y2Inter - y1Inter);

        // Union area (Python lines 86-88)
        float area1 = (box1.x2 - box1.x1) * (box1.y2 - box1.y1);
        float area2 = (box2.x2 - box2.x1) * (box2.y2 - box2.y1);
        float unionArea = area1 + area2 - interArea;

        // IoU (Python line 90)
        return unionArea > 0 ? interArea / unionArea : 0;
    }

    /**
     * Convert detections to React Native format
     */
    private WritableArray convertToTextBlocks(List<DetectionBox> boxes) {
        WritableArray textBlocks = Arguments.createArray();

        for (DetectionBox box : boxes) {
            WritableMap blockData = Arguments.createMap();

            // Text is the EEG electrode label
            blockData.putString("text", box.label);
            blockData.putDouble("confidence", box.confidence);
            blockData.putInt("classIndex", box.classIdx);

            // Rotated bounding box (Python lines 97-106, 115-126)
            WritableMap bounds = Arguments.createMap();
            bounds.putInt("centerX", (int)(box.cx * box.imageWidth));
            bounds.putInt("centerY", (int)(box.cy * box.imageHeight));
            bounds.putInt("width", (int)(box.w * box.imageWidth));
            bounds.putInt("height", (int)(box.h * box.imageHeight));
            bounds.putDouble("angle", Math.toDegrees(box.angle));
            blockData.putMap("bounds", bounds);

            // Corner points for rendering (Python lines 115-126)
            WritableArray corners = box.getCornerPoints();
            blockData.putArray("cornerPoints", corners);

            textBlocks.pushMap(blockData);
        }

        return textBlocks;
    }

    /**
     * Load TFLite model from assets
     */
    private MappedByteBuffer loadModelFile(Context context, String modelPath) throws IOException {
        AssetManager assetManager = context.getAssets();
        AssetFileDescriptor fileDescriptor = assetManager.openFd(modelPath);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    @Override
    public void release() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
        if (gpuDelegate != null) {
            gpuDelegate.close();
            gpuDelegate = null;
        }
        Log.d(TAG, "Resources released");
    }

    @Override
    public String getEngineName() {
        return "TFLite (Custom EEG Electrode Detection)";
    }

    /**
     * Helper class for detection results
     */
    private static class DetectionBox {
        float cx, cy, w, h, angle;
        float x1, y1, x2, y2;
        float confidence;
        int classIdx;
        String label;
        int imageWidth, imageHeight;

        DetectionBox(float cx, float cy, float w, float h, float angle,
                    float confidence, int classIdx, String label,
                    int imageWidth, int imageHeight) {
            this.cx = cx;
            this.cy = cy;
            this.w = w;
            this.h = h;
            this.angle = angle;
            this.confidence = confidence;
            this.classIdx = classIdx;
            this.label = label;
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;

            // Calculate bounding box corners
            this.x1 = cx - w/2;
            this.y1 = cy - h/2;
            this.x2 = cx + w/2;
            this.y2 = cy + h/2;
        }

        /**
         * Calculate rotated corners (Python lines 104-126)
         */
        WritableArray getCornerPoints() {
            int centerX = (int)(cx * imageWidth);
            int centerY = (int)(cy * imageHeight);
            int width = (int)(w * imageWidth);
            int height = (int)(h * imageHeight);

            double cos = Math.cos(angle);
            double sin = Math.sin(angle);

            // Calculate 4 corners of rotated rectangle (Python lines 115-124)
            Point[] corners = new Point[4];
            corners[0] = rotatePoint(-width/2, -height/2, cos, sin, centerX, centerY);
            corners[1] = rotatePoint(width/2, -height/2, cos, sin, centerX, centerY);
            corners[2] = rotatePoint(width/2, height/2, cos, sin, centerX, centerY);
            corners[3] = rotatePoint(-width/2, height/2, cos, sin, centerX, centerY);

            WritableArray cornerArray = Arguments.createArray();
            for (Point corner : corners) {
                WritableMap point = Arguments.createMap();
                point.putInt("x", corner.x);
                point.putInt("y", corner.y);
                cornerArray.pushMap(point);
            }

            return cornerArray;
        }

        private Point rotatePoint(int x, int y, double cos, double sin, int cx, int cy) {
            int rotatedX = (int)(x * cos - y * sin + cx);
            int rotatedY = (int)(x * sin + y * cos + cy);
            return new Point(rotatedX, rotatedY);
        }
    }
}
