# Keep JNI native bridge and data holders intact
-keep class com.zaagforge.studio.dsp.NativeDspBridge { *; }
-keep class com.zaagforge.studio.dsp.NativeAnalysisOutput { *; }
-keep class com.zaagforge.studio.dsp.NativeRemixOutput { *; }
