# TensorFlow Lite Task Vision
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.task.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }
-dontwarn org.tensorflow.lite.**
-dontwarn org.tensorflow.lite.task.**
-keepclassmembers class * { native <methods>; }
-keepattributes *Annotation*

# CameraX
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ML Kit
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
