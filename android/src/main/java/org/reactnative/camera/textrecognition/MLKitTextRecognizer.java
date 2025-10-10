package org.reactnative.camera.textrecognition;

import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

/**
 * MLKit-based text recognizer wrapper.
 * Wraps Google's MLKit text recognition with structured output.
 */
public class MLKitTextRecognizer implements BaseTextRecognizer {
    private static final String TAG = "MLKitTextRecognizer";
    private TextRecognizer recognizer;

    public MLKitTextRecognizer() {
        this.recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        Log.d(TAG, "MLKit text recognizer initialized");
    }

    @Override
    public void process(Bitmap image, int rotation, final OnTextRecognizedListener listener) {
        long startTime = System.currentTimeMillis();

        InputImage inputImage = InputImage.fromBitmap(image, rotation);

        Task<Text> result = recognizer.process(inputImage)
            .addOnSuccessListener(new OnSuccessListener<Text>() {
                @Override
                public void onSuccess(Text visionText) {
                    WritableArray textBlocks = Arguments.createArray();

                    for (Text.TextBlock block : visionText.getTextBlocks()) {
                        WritableMap blockData = Arguments.createMap();
                        blockData.putString("text", block.getText());

                        // Add bounding box
                        if (block.getBoundingBox() != null) {
                            WritableMap bounds = Arguments.createMap();
                            bounds.putInt("left", block.getBoundingBox().left);
                            bounds.putInt("top", block.getBoundingBox().top);
                            bounds.putInt("width", block.getBoundingBox().width());
                            bounds.putInt("height", block.getBoundingBox().height());
                            blockData.putMap("bounds", bounds);
                        }

                        // Add corner points
                        if (block.getCornerPoints() != null) {
                            WritableArray corners = Arguments.createArray();
                            for (android.graphics.Point point : block.getCornerPoints()) {
                                WritableMap cornerPoint = Arguments.createMap();
                                cornerPoint.putInt("x", point.x);
                                cornerPoint.putInt("y", point.y);
                                corners.pushMap(cornerPoint);
                            }
                            blockData.putArray("cornerPoints", corners);
                        }

                        // Add lines
                        WritableArray linesArray = Arguments.createArray();
                        for (Text.Line line : block.getLines()) {
                            WritableMap lineData = Arguments.createMap();
                            lineData.putString("text", line.getText());

                            if (line.getBoundingBox() != null) {
                                WritableMap lineBounds = Arguments.createMap();
                                lineBounds.putInt("left", line.getBoundingBox().left);
                                lineBounds.putInt("top", line.getBoundingBox().top);
                                lineBounds.putInt("width", line.getBoundingBox().width());
                                lineBounds.putInt("height", line.getBoundingBox().height());
                                lineData.putMap("bounds", lineBounds);
                            }

                            // Add elements (words)
                            WritableArray elementsArray = Arguments.createArray();
                            for (Text.Element element : line.getElements()) {
                                WritableMap elementData = Arguments.createMap();
                                elementData.putString("text", element.getText());

                                if (element.getBoundingBox() != null) {
                                    WritableMap elementBounds = Arguments.createMap();
                                    elementBounds.putInt("left", element.getBoundingBox().left);
                                    elementBounds.putInt("top", element.getBoundingBox().top);
                                    elementBounds.putInt("width", element.getBoundingBox().width());
                                    elementBounds.putInt("height", element.getBoundingBox().height());
                                    elementData.putMap("bounds", elementBounds);
                                }

                                elementsArray.pushMap(elementData);
                            }
                            lineData.putArray("elements", elementsArray);
                            linesArray.pushMap(lineData);
                        }
                        blockData.putArray("lines", linesArray);
                        textBlocks.pushMap(blockData);
                    }

                    long endTime = System.currentTimeMillis();
                    Log.d(TAG, String.format("MLKit inference complete: %dms, detected %d blocks",
                        endTime - startTime, textBlocks.size()));

                    listener.onSuccess(textBlocks);
                }
            })
            .addOnFailureListener(new OnFailureListener() {
                @Override
                public void onFailure(@NonNull Exception e) {
                    Log.e(TAG, "MLKit text recognition failed", e);
                    listener.onFailure(e);
                }
            });
    }

    @Override
    public void release() {
        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
            Log.d(TAG, "Resources released");
        }
    }

    @Override
    public String getEngineName() {
        return "MLKit (Google Play Services)";
    }
}
