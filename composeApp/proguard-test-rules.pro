# Test-only R8 rules for the minifiedTest instrumented build type (#106).
# Never merge into shipping proguard-rules.pro.
#
# Minify + obfuscation stay ON for app code (dev.velum.** except seams below).
# No -dontobfuscate. No -keep class kotlin.** / kotlinx.** / androidx.**
# (those full keeps also block shrinking).
#
# Root cause of instrumented R8 failures: androidTest shares the app
# classloader and does not ship its own kotlin/androidx copies. Renaming
# library classes (e.g. kotlin.time.Duration → k5.a) yields NoSuchFieldError /
# NoSuchMethodError inside AndroidComposeTestRule / AndroidJUnitRunner.
# -keepnames preserves library names for classes that remain after shrink;
# unused library code still shrinks away; Velum app code still obfuscates.

-keepnames class kotlin.** { *; }
-keepnames class kotlinx.** { *; }
-keepnames class androidx.** { *; }

# --- App instrumentation seams (must not be stripped or renamed) ---
-keep class dev.velum.app.devicewalk.** { *; }
-keep class dev.velum.app.VelumApplication { *; }
-keepclassmembers class dev.velum.app.VelumApplication { *; }
-keep class dev.velum.app.di.AppContainer { *; }
-keepclassmembers class dev.velum.app.di.AppContainer { *; }

# AndroidJUnitRunner 1.6+ resolves Trace in onCreate from the app APK.
-keep class androidx.tracing.Trace { *; }
-keep class androidx.tracing.** { *; }
-dontwarn androidx.tracing.**
