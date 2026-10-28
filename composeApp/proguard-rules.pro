# Keep kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class dev.velum.**$$serializer { *; }
-keepclassmembers class dev.velum.** {
    *** Companion;
}
-keepclasseswithmembers class dev.velum.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# SQLDelight generated queries / adapters
-keep class *.*.*.*.*Queries { *; }
-keep class *.*.*.*.*Adapter { *; }
-keep class app.cash.sqldelight.** { *; }

# Kable BLE
-keep class com.juul.kable.** { *; }

# WorkManager reflects on Worker / ListenableWorker subclasses
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context,androidx.work.WorkerParameters);
}

# SoftAP Wi‑Fi transfer WebSocket server (java_websocket)
-keep class org.java_websocket.** { *; }
-dontwarn org.java_websocket.**

# Ktor / OkHttp (release minify)
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class io.ktor.** { *; }
# Ktor debug detector references JVM-only management APIs absent on Android.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# commons-compress optional zstd codec (we only gunzip/tar model archives).
-dontwarn com.github.luben.zstd.**

# LiteRT / on-device LLM (reflection-heavy)
-keep class com.google.ai.edge.** { *; }
-keep class org.tensorflow.lite.** { *; }

# Device-walk CI flag + fake engines (flipped via instrumentation runner).
-keep class dev.velum.app.devicewalk.** { *; }

# AndroidJUnitRunner 1.6+ calls androidx.tracing.Trace in onCreate. Without a keep,
# R8 strips it from the release APK and connectedReleaseAndroidTest crashes the
# instrumentation process before any test runs (NoClassDefFoundError).
-keep class androidx.tracing.** { *; }
-dontwarn androidx.tracing.**

# connectedReleaseAndroidTest loads shared libs from the app APK first.
# R8 must not shrink them or androidTest / Compose UI test hits
# NoClassDefFoundError (LazyKt, JobKt, InfiniteAnimationPolicy, …).
-keep class kotlin.** { *; }
-keepclassmembers class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keep class kotlinx.** { *; }
-keepclassmembers class kotlinx.** { *; }
-dontwarn kotlinx.**
-keep class androidx.compose.** { *; }
-keep interface androidx.compose.** { *; }
-keepclassmembers class androidx.compose.** { *; }
-dontwarn androidx.compose.**
-keep class androidx.lifecycle.** { *; }
-keep class androidx.activity.** { *; }
-keep class androidx.savedstate.** { *; }
-dontwarn androidx.lifecycle.**
-dontwarn androidx.activity.**

# sherpa-onnx local ASR / diarization (JNI + reflection-heavy)
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**
