# NativeParams fields are read by name from C++ (jni_bridge.cpp)
-keep class com.spektralab.android.engine.NativeParams { *; }
-keep class com.spektralab.android.engine.SpektraNative { native <methods>; }
