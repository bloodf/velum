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

# LiteRT / on-device LLM (reflection-heavy)
-keep class com.google.ai.edge.** { *; }
-keep class org.tensorflow.lite.** { *; }
