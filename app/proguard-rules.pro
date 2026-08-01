# ARCore and Filament expose native entry points through JNI.
# SceneView ships its own consumer ProGuard/R8 rules; do not keep its entire
# package because that also retains optional collaborative/XR transports.
-keep class com.google.ar.core.** { *; }
-keep class com.google.android.filament.** { *; }
-dontwarn com.google.ar.core.**
-dontwarn com.google.android.filament.**
