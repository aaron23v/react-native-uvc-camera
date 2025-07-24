# ProGuard rules for react-native-uvc-camera

# Keep native libraries from being stripped
-keep class com.serenegiant.usb.** { *; }
-keep class com.google.android.cameraview.** { *; }

# Prevent stripping of native method declarations
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep SoLoader references
-keep class com.facebook.soloader.** { *; }

# Ensure native libraries are not removed
-keep class **.so
-dontwarn com.serenegiant.usb.**