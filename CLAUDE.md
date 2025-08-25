# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

React Native library for accessing USB Video Class (UVC) cameras on Android devices. Combines React Native Camera with UVCCamera library to provide USB camera access without root permissions.

## Development Commands

### Build Commands
```bash
# Clean build artifacts
./gradlew clean

# Build the library
./gradlew build

# Build release version
./gradlew assembleRelease

# Build debug version
./gradlew assembleDebug

# NDK specific commands (for native code)
./gradlew ndkBuild     # Compile JNI source
./gradlew ndkClean     # Clean JNI libraries

# Run Android lint
./gradlew lint

# Build specific modules
./gradlew :libuvccamera:build
./gradlew :usbCameraCommon:build

# Test applications (for manual testing)
./gradlew :usbCameraTest:assembleDebug
./gradlew :usbCameraTest2:assembleDebug
# ... through usbCameraTest8
```

### Setup Requirements
1. Install Android NDK r14b (specifically this version) from [NDK Archives](https://developer.android.google.cn/ndk/downloads/older_releases)
2. Configure `local.properties` with absolute NDK path:
   ```
   ndk.dir=/absolute/path/to/android-ndk-r14b
   ```

## Architecture

### Multi-Layer Structure
```
React Native JavaScript API (/src/)
    ↓
Android Native Module (/android/src/)
    ↓
JNI Bridge (libuvccamera)
    ↓
Native C++ USB Camera Access
```

### Key Components

1. **JavaScript Layer** (`/src/`)
   - `UvcCamera.js`: Main React component exposing camera functionality
   - `handlePermissions.js`: Permission management utilities
   - `FaceDetector.js`: Face detection integration

2. **Android Native Layer** (`/android/`)
   - `CameraUvc.java`: Core UVC camera implementation
   - Supports both standard Android cameras (Camera1/Camera2) and USB cameras
   - Event-driven architecture using Android Handlers

3. **Native Libraries** (`/libuvccamera/`)
   - JNI-based implementation for USB camera access
   - Pre-built `.so` files for arm64-v8a and armeabi-v7a architectures
   - Dependencies: libjpeg-turbo (v1.5.0 and v3.0.4), libusb, libuvc
   - Native C++ code in `/src/main/jni/UVCCamera/`
   - Multiple Android.mk files for modular JNI compilation

4. **Common Utilities** (`/usbCameraCommon/`)
   - `UVCCameraHandler`: Main handler for camera operations
   - `AbstractUVCCameraHandler`: Base class for camera handling
   - Media encoders for video/audio recording
   - UI widgets for camera preview and controls

### Camera APIs

The library supports multiple camera implementations:
- **UVC Camera**: USB cameras via libuvccamera
- **Camera1 API**: Legacy Android camera API
- **Camera2 API**: Modern Android camera API

Selection is automatic based on device capabilities and camera type.

## Development Patterns

### Adding New Camera Features
1. Implement in native Android layer (`/android/src/`)
2. Expose through React Native bridge methods
3. Add JavaScript API in `/src/UvcCamera.js`
4. Update TypeScript definitions if applicable

### Native Code Modifications
When modifying JNI/native code:
1. Changes in `/libuvccamera/src/main/jni/` require NDK rebuild
2. Use `./gradlew ndkClean && ./gradlew ndkBuild`
3. Test on both arm64-v8a and armeabi-v7a devices
4. Native code structure:
   - `/UVCCamera/`: Core UVC camera JNI implementation
   - `/libjpeg-turbo-*/`: JPEG compression libraries (dual versions)
   - `/libusb/`: USB device communication
   - `/libuvc/`: USB Video Class protocol implementation
5. Each component has its own `Android.mk` for modular compilation

### Event Communication
- Native to JS: Use `WritableMap` and `sendEvent()`
- JS to Native: Use `@ReactMethod` annotated methods
- Camera events: "onCameraReady", "onBarcodeRead", "onFacesDetected", etc.

## Testing

No automated test suite exists. Testing approach:
1. Use test applications in `/usbCameraTest*` directories (8 different test apps)
2. Each test app focuses on specific features:
   - `usbCameraTest`: Basic UVC camera functionality
   - `usbCameraTest2`: Video recording with surface encoding
   - `usbCameraTest3`: Advanced camera controls
   - `usbCameraTest4`: Camera service integration
   - `usbCameraTest5-8`: Various specialized testing scenarios
3. Manual testing required for USB camera functionality
4. Build test apps individually: `./gradlew :usbCameraTestX:assembleDebug`

## Important Notes

- Android-only library (no iOS support)
- Requires USB host mode support on device
- USB permissions must be handled at runtime
- Camera preview limited to 1920x1080 resolution
- Supports YUYV and MJPEG formats

## React Native 0.76+ Compatibility

**IMPORTANT**: React Native 0.76+ requires changes to your app's MainApplication. See `REACT_NATIVE_0.76_MIGRATION.md` for details.

If you encounter "dlopen failed: library not found" errors with React Native 0.76+:

1. **Update your app's MainApplication.java**:
   ```java
   import com.facebook.react.soloader.OpenSourceMergedSoMapping;
   import com.facebook.soloader.SoLoader;
   
   @Override
   public void onCreate() {
     super.onCreate();
     SoLoader.init(this, OpenSourceMergedSoMapping); // Changed from SoLoader.init(this, false)
   }
   ```

2. **Library Configuration** (already applied):
   - ABI Filters in `/android/build.gradle` for correct architectures
   - Packaging options to include and preserve native libraries
   - Consumer ProGuard rules to prevent stripping

3. **Clean and rebuild** after making changes:
   ```bash
   cd android && ./gradlew clean && cd .. && npx react-native run-android
   ```