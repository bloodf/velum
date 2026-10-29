# Test-only R8 rules for the minifiedTest instrumented build type (#106).
# Never merge into shipping proguard-rules.pro.
#
# Minify + obfuscation stay ON for app code (dev.velum.** except seams below).
# No -dontobfuscate. No blanket -keep class kotlin.** / kotlinx.** / androidx.**.

# Shared classloader: androidTest does not ship its own kotlin/androidx copies.
# Renaming library classes (e.g. kotlin.time.Duration → k5.a) yields
# NoSuchFieldError inside AndroidComposeTestRule. -keepnames preserves names
# for classes that remain after shrink; unused code still shrinks away.

-keepnames class kotlin.** { *; }
-keepnames class kotlinx.** { *; }
-keepnames class androidx.** { *; }

# Targeted keeps for Compose UI Test / coroutines-test seams the app never
# references (would otherwise be shrunk from the shared app classloader):
# - DelayWithTimeoutDiagnostics (ce8d7db)
# - kotlin.time.** / AbstractLongTimeSource (4406be9)
# - InfiniteAnimationPolicy$DefaultImpls (af87f76)
# - CoroutineExceptionHandlerImplKt.ensurePlatformExceptionHandlerLoaded (9e0742c)
# No kotlinx.coroutines.internal.** package wildcard — exact class only.
-keep class kotlinx.coroutines.DelayWithTimeoutDiagnostics { *; }
-keep class kotlinx.coroutines.internal.CoroutineExceptionHandlerImplKt { *; }
-keep class kotlin.time.** { *; }
-keep class androidx.compose.ui.platform.InfiniteAnimationPolicy { *; }
-keep class androidx.compose.ui.platform.InfiniteAnimationPolicy$* { *; }

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
