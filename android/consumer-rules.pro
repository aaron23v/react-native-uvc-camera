# Consumer ProGuard rules for react-native-uvc-camera
# These rules will be applied to apps that use this library

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep UVC Camera classes
-keep class com.serenegiant.usb.** { *; }
-keep class com.google.android.cameraview.** { *; }

# Don't warn about missing classes
-dontwarn com.serenegiant.**