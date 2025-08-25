# React Native 0.76+ Migration Guide for react-native-uvc-camera

## The Problem

When upgrading to React Native 0.76+, you may encounter:
```
UnsatisfiedLinkError: dlopen failed: library "libjpeg-turbo1500.so" not found
```

This occurs because React Native 0.76 consolidated its native libraries and changed how SoLoader initializes.

## Required Changes in Your App

### 1. Update MainApplication.java (or MainApplication.kt)

**Before (RN < 0.76):**
```java
import com.facebook.soloader.SoLoader;

@Override
public void onCreate() {
  super.onCreate();
  SoLoader.init(this, /* native exopackage */ false);
  // ... rest of initialization
}
```

**After (RN >= 0.76):**
```java
import com.facebook.react.soloader.OpenSourceMergedSoMapping;
import com.facebook.soloader.SoLoader;

@Override
public void onCreate() {
  super.onCreate();
  SoLoader.init(this, OpenSourceMergedSoMapping);
  // ... rest of initialization
}
```

For Kotlin:
```kotlin
import com.facebook.react.soloader.OpenSourceMergedSoMapping
import com.facebook.soloader.SoLoader

override fun onCreate() {
  super.onCreate()
  SoLoader.init(this, OpenSourceMergedSoMapping)
  // ... rest of initialization
}
```

### 2. Ensure Proper Gradle Configuration

The library already includes the necessary packaging configurations, but verify your app's `android/app/build.gradle` includes:

```gradle
android {
  packagingOptions {
    pickFirst '**/libjpeg-turbo1500.so'
    pickFirst '**/libusb100.so'
    pickFirst '**/libuvc.so'
    pickFirst '**/libUVCCamera.so'
  }
}
```

### 3. Clean and Rebuild

After making these changes:
```bash
cd android
./gradlew clean
cd ..
npx react-native run-android
```

## Why This Happens

React Native 0.76 merged many of its native libraries into `libreactnative.so` for performance. This change requires:
1. Updated SoLoader initialization with `OpenSourceMergedSoMapping`
2. Proper packaging configurations to ensure third-party native libraries are included
3. Correct ABI filters to match device architectures

## Troubleshooting

If you still encounter issues:

1. **Verify ABI compatibility**: Check that your device architecture matches the library's supported ABIs (armeabi-v7a, arm64-v8a)

2. **Check ProGuard/R8**: Ensure native libraries aren't being stripped in release builds

3. **Force SoLoader version** (if needed):
```gradle
// In android/build.gradle
allprojects {
  configurations.all {
    resolutionStrategy {
      force 'com.facebook.soloader:soloader:0.11.0'
    }
  }
}
```

4. **Verify library inclusion**: Unzip your APK and check if the .so files are present in the `lib/` directory