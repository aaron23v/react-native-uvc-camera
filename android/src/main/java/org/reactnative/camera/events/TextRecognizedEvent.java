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
    event.putArray("textBlocks", mTextBlocks);
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
