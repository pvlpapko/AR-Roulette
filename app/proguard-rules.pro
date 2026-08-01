# ARCore and SceneView expose classes through JNI/reflection.
-keep class com.google.ar.core.** { *; }
-keep class com.google.android.filament.** { *; }
-keep class io.github.sceneview.** { *; }
-dontwarn com.google.ar.core.**
-dontwarn com.google.android.filament.**
