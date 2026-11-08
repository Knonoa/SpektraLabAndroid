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
-keep class com.spektralab.android.PhotoIO$* { *; }
-keep class com.spektralab.android.camera.CameraInventory { *; }
-keep class com.spektralab.android.camera.CameraDevice { *; }
-keep class com.spektralab.android.camera.Lens { *; }
-keep class com.spektralab.android.FullSizeExport { *; }
-keep class com.spektralab.android.tether.** { *; }
-keep class com.spektralab.android.EditorViewModel { *; }
-keep class com.spektralab.android.MediaLibrary { *; }
-keep class com.spektralab.android.MediaLibrary$* { *; }
-keep class com.spektralab.android.MediaItem { *; }
-keep class com.spektralab.android.MediaSource { *; }
-keep class com.spektralab.android.MediaKind { *; }
-keep class com.spektralab.android.MediaLibraryKt { *; }
-keep class com.spektralab.android.RecipeStore { *; }
-keep class com.spektralab.android.RecipeStore$* { *; }
-keep class com.spektralab.android.FilmRecipe { *; }
-keep class com.spektralab.android.Photo { *; }
-keep class com.spektralab.android.RenderResult { *; }
-keep class com.spektralab.android.UiMessage$* { *; }
-keep interface com.spektralab.android.UiMessage { *; }
-keep class com.spektralab.android.ExportSize { *; }
-keep class androidx.lifecycle.ViewModel { *; }
-keep class androidx.lifecycle.AndroidViewModel { *; }
-keep class androidx.lifecycle.ViewModelStore { *; }
-keep class androidx.lifecycle.ViewModelProvider { *; }
-keep class androidx.lifecycle.ViewModelProvider$* { *; }
# Coroutine calls also cross the independent release instrumentation boundary.
-keep class kotlinx.coroutines.** { *; }

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
