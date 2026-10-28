# Test-only keeps for the minifiedWalk instrumented build type.
# Never merge into shipping proguard-rules.pro.
#
# androidTest and the app share a classloader on connected*AndroidTest, so
# R8 cannot shrink/obfuscate facades the test APK still references by name.

-dontobfuscate

# AndroidJUnitRunner 1.6+ needs Trace on the app classpath.
-keep class androidx.tracing.** { *; }
-dontwarn androidx.tracing.**

# Device-walk runner flag + fake engines (also referenced from AppContainer).
-keep class dev.velum.app.devicewalk.** { *; }

# Instrumentation seams used by DeviceWalkUiTest (also annotated @Keep).
-keep class dev.velum.app.VelumApplication { *; }
-keepclassmembers class dev.velum.app.VelumApplication { *; }
-keep class dev.velum.app.di.AppContainer { *; }
-keepclassmembers class dev.velum.app.di.AppContainer { *; }

# Shared kotlin / kotlinx / androidx facades for Compose UI tests.
-keep class kotlin.** { *; }
-keepclassmembers class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keep class kotlinx.** { *; }
-keepclassmembers class kotlinx.** { *; }
-dontwarn kotlinx.**
-keep class androidx.** { *; }
-keep interface androidx.** { *; }
-keepclassmembers class androidx.** { *; }
-dontwarn androidx.**
