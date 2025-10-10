package org.reactnative.camera.events;

import androidx.core.util.Pools;
import android.util.SparseArray;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.uimanager.events.Event;
import com.facebook.react.uimanager.events.RCTEventEmitter;
import com.google.android.cameraview.CameraView;
import org.reactnative.camera.CameraViewManager;
import org.reactnative.camera.utils.ImageDimensions;
import org.reactnative.facedetector.FaceDetectorUtils;


public class TextRecognizedEvent extends Event<TextRecognizedEvent> {

  private static final Pools.SynchronizedPool<TextRecognizedEvent> EVENTS_POOL =
      new Pools.SynchronizedPool<>(3);


  private double mScaleX;
  private double mScaleY;
  private WritableArray mTextBlocks;
  private ImageDimensions mImageDimensions;

  private TextRecognizedEvent() {}

  public static TextRecognizedEvent obtain(
      int viewTag,
      WritableArray textBlocks,
      ImageDimensions dimensions,
      double scaleX,
      double scaleY) {
    TextRecognizedEvent event = EVENTS_POOL.acquire();
    if (event == null) {
      event = new TextRecognizedEvent();
    }
    event.init(viewTag, textBlocks, dimensions, scaleX, scaleY);
    return event;
  }

  private void init(
      int viewTag,
      WritableArray textBlocks,
      ImageDimensions dimensions,
      double scaleX,
      double scaleY) {
    super.init(viewTag);
    mTextBlocks = textBlocks;
    mImageDimensions = dimensions;
    mScaleX = scaleX;
    mScaleY = scaleY;
  }

  @Override
  public String getEventName() {
    return CameraViewManager.Events.EVENT_ON_TEXT_RECOGNIZED.toString();
  }

  @Override
  public void dispatch(RCTEventEmitter rctEventEmitter) {
    rctEventEmitter.receiveEvent(getViewTag(), getEventName(), serializeEventData());
  }

  private WritableMap serializeEventData() {
    WritableMap event = Arguments.createMap();
    event.putString("type", "textRecognition");

    // Create a copy of textBlocks to avoid ObjectAlreadyConsumedException
    // when event is reused from the pool
    WritableArray textBlocksCopy = Arguments.createArray();
    StringBuilder concatenatedText = new StringBuilder();

    try {
      for (int i = 0; i < mTextBlocks.size(); i++) {
        ReadableMap block = mTextBlocks.getMap(i);
        if (block != null) {
          // Create a new WritableMap copy to avoid consumption issues
          WritableMap blockCopy = Arguments.createMap();
          blockCopy.merge(block);
          textBlocksCopy.pushMap(blockCopy);

          // Concatenate all text for backward compatibility
          try {
            if (block.hasKey("text")) {
              String text = block.getString("text");
              if (text != null) {
                concatenatedText.append(text);
              }
            }
          } catch (Exception e) {
            // Ignore individual block errors
          }
        }
      }
    } catch (Exception e) {
      // If there's an error reading blocks, just continue with empty text
    }

    event.putString("text", concatenatedText.toString());
    event.putArray("textBlocks", textBlocksCopy);
    event.putInt("target", getViewTag());

    // Add image dimensions for coordinate transformation
    WritableMap dimensions = Arguments.createMap();
    dimensions.putInt("width", mImageDimensions.getWidth());
    dimensions.putInt("height", mImageDimensions.getHeight());
    dimensions.putInt("rotation", mImageDimensions.getRotation());
    event.putMap("dimensions", dimensions);

    // Add scale factors for coordinate transformation
    WritableMap scale = Arguments.createMap();
    scale.putDouble("x", mScaleX);
    scale.putDouble("y", mScaleY);
    event.putMap("scale", scale);

    return event;
  }


}
