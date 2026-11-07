# NativeParams fields are read by name from C++ (jni_bridge.cpp)
-keep class com.spektralab.android.engine.NativeParams { *; }
-keep class com.spektralab.android.engine.SpektraNative { *; }
-keep class com.spektralab.android.engine.SpektraNative$LinearImage { *; }

# These image/parameter APIs are also called across the separately compiled
# release instrumentation boundary. Keep their callable surface in the app.
-keep class com.spektralab.android.EditState { *; }
-keep class com.spektralab.android.EditState$* { *; }
-keep class com.spektralab.android.FilmEdgeSettings { *; }
-keep class com.spektralab.android.FilmEdgeSettings$* { *; }
-keep class com.spektralab.android.DateBackSettings { *; }
-keep class com.spektralab.android.DateBackSettings$* { *; }
-keep class com.spektralab.android.ShootingData { *; }
-keep class com.spektralab.android.ShootingData$* { *; }
-keep class com.spektralab.android.PhotoIO { *; }
-keep class com.spektralab.android.camera.CameraInventory { *; }
-keep class com.spektralab.android.camera.CameraDevice { *; }
-keep class com.spektralab.android.camera.Lens { *; }

# AndroidJUnitRunner calls Trace from the separate release instrumentation APK.
# Preserve this small shared API when shrinking the app independently of tests.
-keep class androidx.tracing.Trace { *; }
-keep class androidx.camera.lifecycle.ProcessCameraProvider { *; }
-keep class androidx.camera.lifecycle.ProcessCameraProvider$Companion { *; }
-keep class androidx.camera.camera2.interop.Camera2CameraInfo { *; }
-keep class androidx.camera.camera2.interop.Camera2CameraInfo$Companion { *; }

# Kotlin facades are referenced by the separate AndroidX Test runner, outside
# the application's reachability graph. Its API must survive app-only R8.
-keep class kotlin.** { *; }
