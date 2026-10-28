# Test-only R8 rules for the minifiedTest instrumented build type (#106).
# Never merge into shipping proguard-rules.pro.
#
# Minify + obfuscation stay ON. androidTest shares the app classloader, so
# only the facades historically missing under connected*AndroidTest are kept
# by name — no -dontobfuscate, no blanket kotlin.** / kotlinx.** / androidx.**.

# --- App instrumentation seams (DeviceWalkUiTest / DeviceWalkTestRunner) ---
-keep class dev.velum.app.devicewalk.** { *; }
-keep class dev.velum.app.VelumApplication { *; }
-keepclassmembers class dev.velum.app.VelumApplication { *; }
-keep class dev.velum.app.di.AppContainer { *; }
-keepclassmembers class dev.velum.app.di.AppContainer { *; }

# --- AndroidJUnitRunner 1.6+ resolves Trace in onCreate from the app APK ---
-keep class androidx.tracing.Trace { *; }
-keep class androidx.tracing.** { *; }
-dontwarn androidx.tracing.**

# --- kotlin stdlib facades resolved from the app APK first (historical NSME) ---
# LazyKt: TestDirCalculator / AndroidJUnitRunner
-keep class kotlin.Metadata { *; }
-keep class kotlin.LazyKt { *; }
-keep class kotlin.LazyKt__** { *; }
-keep class kotlin.jvm.internal.** { *; }
# StringsKt.equals(...): androidTest / runner linkage
-keep class kotlin.text.StringsKt { *; }
-keep class kotlin.text.StringsKt__** { *; }
# Suspend bridges (awaitContainer Continuation)
-keep class kotlin.coroutines.Continuation { *; }
-keep class kotlin.coroutines.** { *; }

# --- kotlinx.coroutines used by runBlocking + awaitContainer / Flow.collect ---
-keep class kotlinx.coroutines.JobKt { *; }
-keep class kotlinx.coroutines.JobKt__** { *; }
-keep class kotlinx.coroutines.BuildersKt { *; }
-keep class kotlinx.coroutines.BuildersKt__** { *; }
-keep class kotlinx.coroutines.CoroutineScopeKt { *; }
-keep class kotlinx.coroutines.flow.FlowKt { *; }
-keep class kotlinx.coroutines.flow.FlowKt__** { *; }
-dontwarn kotlinx.coroutines.**

# --- Compose UI test animation policy (InfiniteAnimationPolicy NSME) ---
-keep class androidx.compose.animation.core.InfiniteAnimationPolicy { *; }
-keep class androidx.compose.animation.core.InfiniteAnimationPolicyKt { *; }

# --- androidx.collection.mutableIntObjectMapOf (Compose / UI test) ---
-keep class androidx.collection.CollectionPlatform_jvmKt { *; }
-keep class androidx.collection.Collections_jvmKt { *; }
-keep class androidx.collection.** { *; }
