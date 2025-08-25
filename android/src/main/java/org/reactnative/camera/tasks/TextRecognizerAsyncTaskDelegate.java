package org.reactnative.camera.tasks;

public interface TextRecognizerAsyncTaskDelegate {
  void onTextRecognized(String text, int sourceWidth, int sourceHeight, int sourceRotation);
  void onTextRecognizerTaskCompleted();
}
