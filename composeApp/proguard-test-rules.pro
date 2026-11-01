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
# - JobSupport.getCompletionExceptionOrNull (a75a9b1 walk: AbstractCoroutine receiver)
# - ThreadSafeHeap.addLast (f25fed7 walk: TestCoroutineScheduler)
# - ComparisonsKt.compareValuesBy (11a4358 walk: TestDispatchEvent.compareTo)
# - Snapshot.hasPendingChanges (31328ac walk: ComposeIdlingResource / Espresso onIdle)
# - ViewRootForTest$Companion.setOnViewCreatedCallback (65a96b6 walk)
# - ViewRootForTest.getView (37b517a walk)
# - WindowRecomposerPolicy.getAndSetFactory (c4bc1df walk)
# - WindowRecomposerFactory (918aa6f: ClassCast to obfuscated platform.k)
# - ContinuationInterceptor$DefaultImpls.releaseInterceptedContinuation (918aa6f)
# No kotlinx.coroutines.internal.** package wildcard — exact class only.
# ComparisonsKt needs -keep (not -keepnames): coroutines-test calls
# compareValuesBy reflectively; shrinking the method yields NoSuchMethodError.
# Snapshot.hasPendingChanges is only referenced from Compose UI Test idle —
# R8 strips it from the shared app classloader and wedges collectAsState /
# performClick under minifiedTest (FirstRun never dismisses after Skip).
-keep class kotlinx.coroutines.DelayWithTimeoutDiagnostics { *; }
-keep class kotlinx.coroutines.internal.CoroutineExceptionHandlerImplKt { *; }
-keep class kotlinx.coroutines.JobSupport { *; }
-keep class kotlinx.coroutines.internal.ThreadSafeHeap { *; }
-keep class kotlin.comparisons.ComparisonsKt { *; }
-keep class kotlin.time.** { *; }
-keep class kotlin.coroutines.ContinuationInterceptor$DefaultImpls { *; }
-keep class androidx.compose.runtime.snapshots.Snapshot { *; }
# ComposeIdlingResource reads this getter only from androidTest. Without it,
# Espresso.onIdle fails before Skip/Settings interactions (#106/#117).
-keepclassmembers class androidx.compose.runtime.Recomposer {
    public boolean getHasPendingWork();
}
# ui-test-android 1.9.4's TestOwner/InputDispatcher call these exact RootForTest
# seams from the test APK. Preserve the interface ABI in the shared classloader.
-keepclassmembers interface androidx.compose.ui.node.RootForTest {
    androidx.compose.ui.semantics.SemanticsOwner getSemanticsOwner();
    boolean sendIndirectTouchEvent(androidx.compose.ui.input.indirect.IndirectTouchEvent);
    boolean sendKeyEvent*(android.view.KeyEvent);
}
-keepclassmembers class androidx.compose.ui.semantics.SemanticsOwner {
    androidx.compose.ui.semantics.SemanticsNode getRootSemanticsNode();
}
-keep class androidx.compose.ui.platform.InfiniteAnimationPolicy { *; }
-keep class androidx.compose.ui.platform.InfiniteAnimationPolicy$* { *; }
-keep class androidx.compose.ui.platform.ViewRootForTest { *; }
-keep class androidx.compose.ui.platform.ViewRootForTest$* { *; }
-keep class androidx.compose.ui.platform.WindowRecomposerPolicy { *; }
-keep class androidx.compose.ui.platform.WindowRecomposerFactory { *; }

# --- App instrumentation seams (must not be stripped or renamed) ---
-keep class dev.velum.app.devicewalk.** { *; }
-keep class dev.velum.app.VelumApplication { *; }
-keepclassmembers class dev.velum.app.VelumApplication { *; }
-keep class dev.velum.app.di.AppContainer { *; }
-keepclassmembers class dev.velum.app.di.AppContainer { *; }
# Instrumentation imports one fixture through the real processor and verifies
# its persisted result. Keep just those cross-APK calls; app names may obfuscate.
-keepclassmembers,allowobfuscation class dev.velum.ai.pipeline.RecordingProcessor {
    kotlinx.coroutines.flow.Flow process(java.lang.String,java.lang.String,boolean,dev.velum.core.settings.ApiModelOverride);
}
-keepclassmembers,allowobfuscation class dev.velum.core.data.VelumRepository {
    java.lang.Object getRecording(java.lang.String,kotlin.coroutines.Continuation);
}
-keepclassmembers,allowobfuscation class dev.velum.core.db.Recording {
    java.lang.String getStatus();
    java.lang.String getSummary_markdown();
}

# Keystore instrumented test (#92) constructs AndroidSecureStore via this ctor.
-keep class dev.velum.core.security.AndroidSecureStore {
    public <init>(android.content.Context, java.lang.String, java.lang.String);
}

# AndroidJUnitRunner 1.6+ resolves Trace in onCreate from the app APK.
-keep class androidx.tracing.Trace { *; }
-keep class androidx.tracing.** { *; }
-dontwarn androidx.tracing.**

# Exact shared-classloader ABI used by the minified androidTest APK.
# Derived from DEX method references and verified against the unshrunk app.
# No package keeps; only symbols removed by R8 from app dependencies.
-keepclassmembers class androidx.compose.runtime.ComposerKt {
    void sourceInformation(androidx.compose.runtime.Composer,java.lang.String);
    void sourceInformationMarkerEnd(androidx.compose.runtime.Composer);
    void sourceInformationMarkerStart(androidx.compose.runtime.Composer,int,java.lang.String);
}
-keepclassmembers class androidx.compose.ui.input.key.Key$Companion {
    long getAltLeft*();
    long getAltRight*();
    long getCapsLock*();
    long getCtrlLeft*();
    long getCtrlRight*();
    long getFunction*();
    long getMetaLeft*();
    long getMetaRight*();
    long getNumLock*();
    long getScrollLock*();
    long getShiftLeft*();
    long getShiftRight*();
}
-keepclassmembers class androidx.compose.ui.input.key.Key {
    androidx.compose.ui.input.key.Key box*(long);
    boolean equals*(long,java.lang.Object);
    java.lang.String toString*(long);
    long unbox*();
}
-keepclassmembers class androidx.compose.ui.input.pointer.util.VelocityTracker {
    long calculateVelocity*();
}
-keepclassmembers interface androidx.compose.ui.layout.LayoutInfo {
    androidx.compose.ui.unit.Density getDensity();
    androidx.compose.ui.layout.LayoutInfo getParentInfo();
    androidx.compose.ui.platform.ViewConfiguration getViewConfiguration();
}
-keepclassmembers class androidx.compose.ui.platform.PlatformTextInputModifierNodeKt {
    void InterceptPlatformTextInput(androidx.compose.ui.platform.PlatformTextInputInterceptor,kotlin.jvm.functions.Function2,androidx.compose.runtime.Composer,int);
}
-keep class androidx.compose.ui.semantics.CustomAccessibilityAction {
    kotlin.jvm.functions.Function0 getAction();
    java.lang.String getLabel();
}
-keepclassmembers class androidx.compose.ui.semantics.SemanticsNode {
    int getAlignmentLinePosition(androidx.compose.ui.layout.AlignmentLine);
    long getPositionInWindow*();
    androidx.compose.ui.node.RootForTest getRoot();
    boolean isRoot();
}
-keepclassmembers class androidx.compose.ui.semantics.SemanticsOwnerKt {
    java.util.List getAllSemanticsNodes(androidx.compose.ui.semantics.SemanticsOwner,boolean,boolean);
}
# Failure diagnostics in ui-test read these annotations. CharSequence does not
# supply them; preserve the exact methods even though it is a platform parent.
-keepclassmembers class androidx.compose.ui.text.AnnotatedString {
    java.util.List getParagraphStyles();
    java.util.List getStringAnnotations(int,int);
}
-keepclassmembers interface androidx.compose.ui.unit.Density {
    int roundToPx*(long);
    androidx.compose.ui.geometry.Rect toRect(androidx.compose.ui.unit.DpRect);
    long toSp*(int);
}
-keep class androidx.compose.ui.unit.DpRect {
    <init>(float,float,float,float,kotlin.jvm.internal.DefaultConstructorMarker);
    float getBottom*();
    float getLeft*();
    float getRight*();
    float getTop*();
}
-keepclassmembers class androidx.compose.ui.util.MathHelpersKt {
    long lerp(long,long,float);
}
-keep class androidx.compose.ui.viewinterop.AndroidView_androidKt {
    void AndroidView(kotlin.jvm.functions.Function1,androidx.compose.ui.Modifier,kotlin.jvm.functions.Function1,androidx.compose.runtime.Composer,int,int);
}
-keep interface androidx.compose.ui.window.DialogWindowProvider {
    android.view.Window getWindow();
}
-keepclassmembers class kotlin.collections.ArraysKt___ArraysKt {
    boolean contains(boolean[],boolean);
}
-keepclassmembers class kotlin.collections.CollectionsKt___CollectionsKt {
    int count(java.lang.Iterable);
}
-keepclassmembers class kotlin.collections.MapsKt__MapsKt {
    java.util.Map mutableMapOf(kotlin.Pair[]);
}
-keepclassmembers class kotlin.collections.unsigned.UArraysKt___UArraysKt {
    java.lang.String contentToString*(byte[]);
    java.lang.String contentToString*(int[]);
    java.lang.String contentToString*(short[]);
    java.lang.String contentToString*(long[]);
}
-keepclassmembers class kotlin.coroutines.ContinuationKt {
    kotlin.coroutines.Continuation createCoroutine(kotlin.jvm.functions.Function1,kotlin.coroutines.Continuation);
}
-keepclassmembers class kotlin.io.FilesKt__FileReadWriteKt {
    void writeText$default(java.io.File,java.lang.String,java.nio.charset.Charset,int,java.lang.Object);
}
-keepclassmembers interface kotlin.ranges.ClosedRange {
    boolean contains(java.lang.Comparable);
}
-keep interface kotlin.ranges.OpenEndRange {
    boolean contains(java.lang.Comparable);
}
-keep class kotlin.ranges.UIntRange {
    boolean contains*(int);
}
-keep class kotlin.ranges.ULongRange {
    boolean contains*(long);
}
-keepclassmembers class kotlin.sequences.SequencesKt___SequencesKt {
    boolean contains(kotlin.sequences.Sequence,java.lang.Object);
    kotlin.sequences.Sequence zipWithNext(kotlin.sequences.Sequence,kotlin.jvm.functions.Function2);
}
-keepclassmembers interface kotlinx.coroutines.CancellableContinuation {
    void resume(java.lang.Object,kotlin.jvm.functions.Function1);
}
-keep class kotlinx.coroutines.DebugKt {
    boolean getRECOVER_STACK_TRACES();
}
-keepclassmembers interface kotlinx.coroutines.Deferred {
    java.lang.Throwable getCompletionExceptionOrNull();
}
-keepclassmembers class kotlinx.coroutines.Delay$DefaultImpls {
    java.lang.Object delay(kotlinx.coroutines.Delay,long,kotlin.coroutines.Continuation);
}
-keepclassmembers interface kotlinx.coroutines.Delay {
    java.lang.Object delay(long,kotlin.coroutines.Continuation);
}
-keepclassmembers interface kotlinx.coroutines.channels.ReceiveChannel {
    kotlinx.coroutines.selects.SelectClause1 getOnReceive();
}
-keep class kotlinx.coroutines.debug.internal.DebugProbesImpl {
    kotlinx.coroutines.debug.internal.DebugProbesImpl INSTANCE;
    void dumpCoroutines(java.io.PrintStream);
    void install$kotlinx_coroutines_core();
    boolean isInstalled$kotlinx_coroutines_debug();
    void uninstall$kotlinx_coroutines_core();
}
-keep class kotlinx.coroutines.internal.MainDispatcherFactory$DefaultImpls {
    java.lang.String hintOnError(kotlinx.coroutines.internal.MainDispatcherFactory);
}
-keepclassmembers class kotlinx.coroutines.internal.StackTraceRecoveryKt {
    java.lang.Throwable unwrapImpl(java.lang.Throwable);
}
-keepclassmembers class kotlinx.coroutines.selects.OnTimeoutKt {
    void onTimeout*(kotlinx.coroutines.selects.SelectBuilder,long,kotlin.jvm.functions.Function1);
}

# Synthetic filesystem instrumentation calls only this exact WAV writer boundary.
# Test-only; shipping recovery stays fully optimized/obfuscated.
-keep class dev.velum.app.platform.WavWriter {
    public <init>(java.lang.String,int);
    public static final dev.velum.app.platform.WavWriter$Companion Companion;
    public void writePcm(byte[]);
    public dev.velum.app.platform.WavFinish finish();
}
-keep class dev.velum.app.platform.WavWriter$Companion {
    public dev.velum.app.platform.WavFinish recoverOrFinalize(java.lang.String,int);
}
-keep class dev.velum.app.platform.WavFinish {
    public java.lang.String getAbsolutePath();
}
