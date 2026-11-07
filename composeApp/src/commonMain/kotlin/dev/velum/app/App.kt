package dev.velum.app

import dev.velum.app.ui.settings.ModelFailureNotifier
import dev.velum.app.ui.settings.modelFailureNotice
import dev.velum.ai.local.newlyFailed
import dev.velum.ai.local.LocalModelStatus
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.savedstate.read
import dev.velum.ai.pipeline.AutoFlowUiAction
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.En
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.i18n.ModelCatalogUi
import dev.velum.app.i18n.PtBr
import dev.velum.app.i18n.Strings
import dev.velum.app.ui.ask.AskAcrossScreen
import dev.velum.app.ui.library.LibraryAdaptiveScreen
import dev.velum.app.ui.library.LibraryRailDestination
import dev.velum.app.importing.AudioImportPipeline
import dev.velum.app.importing.ImportResult
import dev.velum.app.navigation.Routes
import dev.velum.app.platform.AppPermission
import dev.velum.app.platform.ExportFolderResult
import dev.velum.app.platform.LocalHaptics
import dev.velum.app.platform.MicStartResult
import dev.velum.app.platform.PermissionOutcome
import dev.velum.app.platform.SharedFile
import dev.velum.app.platform.awaitIncomingSharePath
import dev.velum.app.platform.observeMicFailures
import dev.velum.app.platform.MicStopResult
import dev.velum.app.platform.finalizeMicRecording
import dev.velum.app.platform.openCalendarInsert
import dev.velum.app.platform.recordingsDirectoryPath
import dev.velum.app.platform.rememberAudioImporter
import dev.velum.app.platform.rememberHaptics
import dev.velum.app.platform.rememberImportFolderPicker
import dev.velum.app.platform.rememberPermissionRequester
import dev.velum.app.platform.shareText
import dev.velum.app.platform.syncWatchedFolders
import dev.velum.app.platform.systemLanguageTag
import dev.velum.app.ui.settings.ModelDownloadNotification
import dev.velum.app.ui.DeviceScreen
import dev.velum.app.ui.importing.ImportHubScreen
import dev.velum.app.ui.motion.Motion
import dev.velum.app.ui.autoflow.AutoFlowEditScreen
import dev.velum.app.ui.autoflow.AutoFlowListScreen
import dev.velum.app.ui.autoflow.AutoFlowTemplateLibraryScreen
import dev.velum.app.ui.explore.ExploreScreen
import dev.velum.app.ui.files.FilesScreen
import dev.velum.app.ui.integrations.IntegrationsScreen
import dev.velum.app.ui.onboarding.FirstRunScreen
import dev.velum.app.ui.recording.RecordingDetailScreen
import dev.velum.app.ui.settings.AboutScreen
import dev.velum.app.ui.settings.AiProcessingScreen
import dev.velum.app.ui.settings.ApiSettingsScreen
import dev.velum.app.ui.settings.AppearanceScreen
import dev.velum.app.ui.settings.DevicesSettingsScreen
import dev.velum.app.ui.settings.ImportDeviceKeysScreen
import dev.velum.app.ui.settings.MemoryScreen
import dev.velum.app.ui.settings.ModelManagerScreen
import dev.velum.app.ui.settings.PersonalizationScreen
import dev.velum.app.ui.settings.PreferencesScreen
import dev.velum.app.ui.settings.RecordingSettingsScreen
import dev.velum.app.ui.settings.SettingsHubScreen
import dev.velum.app.ui.settings.StoragePrivacyScreen
import dev.velum.app.ui.settings.VoiceProfilesScreen
import dev.velum.app.ui.shell.FabOverlay
import dev.velum.app.ui.shell.ShellTab
import dev.velum.app.ui.shell.VelumBottomBar
import dev.velum.app.ui.templates.TemplateDetailScreen
import dev.velum.app.ui.templates.TemplatesScreen
import dev.velum.app.ui.theme.VelumTheme
import dev.velum.core.model.ComputeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun App(
    container: AppContainer,
    /** Optional override for screenshots / desktop demos; null = follow preferences. */
    stringsOverride: Strings? = null,
    darkThemeOverride: Boolean? = null,
) {
    val prefs by container.settingsRepository.preferences.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val darkTheme = darkThemeOverride ?: when (prefs.themeMode) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }
    val strings = stringsOverride ?: when (prefs.appLanguage) {
        "en" -> En
        "pt-BR" -> PtBr
        else -> if (systemLanguageTag().lowercase().startsWith("pt")) PtBr else En
    }
    VelumTheme(darkTheme = darkTheme) {
        val haptics = rememberHaptics()
        CompositionLocalProvider(
            LocalStrings provides strings,
            LocalHaptics provides haptics,
        ) {
            VelumRoot(container)
        }
    }
}

@Composable
private fun VelumRoot(container: AppContainer) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route.orEmpty()
    var fabOpen by remember { mutableStateOf(false) }
    // Container StateFlow via collectAsState (first paint = flow.current; recreate
    // re-seeds from the flow so FirstRun stays dismissed before DB persist).
    // Local hide latch: UiAutomator Skip runs onClick on Android main, but the
    // collectAsState collector sits on the Compose UI Test scheduler and may not
    // recompose until idle — latch hides FirstRun on the same frame (#106).
    val firstRunPending by container.firstRunPending.collectAsState()
    var firstRunDismissed by remember { mutableStateOf(false) }
    // (needed, free) bytes when the recommended pack does not fit; resolved to copy at render so a language change applies.
    var firstRunShort by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var firstRunDownloading by remember { mutableStateOf(false) }
    val showFirstRun = firstRunPending && !firstRunDismissed
    fun dismissFirstRun() {
        firstRunDismissed = true
        container.markFirstRunDone()
    }
    var librarySelectedId by remember { mutableStateOf<String?>(null) }
    var librarySeekSeconds by remember { mutableStateOf(0L) }
    var libraryRail by remember { mutableStateOf(LibraryRailDestination.Library) }
    val scope = rememberCoroutineScope()
    val strings = LocalStrings.current
    val snackbar = remember { SnackbarHostState() }
    val reduceMotion = Motion.reduceMotion()
    val navEnter = fadeIn(tween(if (reduceMotion) 0 else Motion.NavMs, easing = Motion.EaseOut))
    val navExit = fadeOut(tween(if (reduceMotion) 0 else 120, easing = Motion.EaseOut))


    val requestMic = rememberPermissionRequester(
        permission = AppPermission.RecordAudio,
        rationaleTitle = strings.micPermissionTitle,
        rationaleBody = strings.micPermissionRationale,
    )
    val requestNotifications = rememberPermissionRequester(
        permission = AppPermission.PostNotifications,
        rationaleTitle = strings.ux.notificationsPermissionTitle,
        rationaleBody = strings.ux.notificationsPermissionRationale,
    )
    val requestCalendar = rememberPermissionRequester(
        permission = AppPermission.ReadCalendar,
        rationaleTitle = strings.calendarPermissionTitle,
        rationaleBody = strings.calendarPermissionRationale,
    )

    val importPipeline = remember(container) { AudioImportPipeline(container) }
    LaunchedEffect(container, importPipeline) {
        val loaded = container.settingsRepository.loadState.first {
            it == dev.velum.core.settings.SettingsLoadState.Ready ||
                it == dev.velum.core.settings.SettingsLoadState.Unavailable
        }
        if (loaded == dev.velum.core.settings.SettingsLoadState.Ready &&
            container.settingsRepository.preferences.value.autoSyncWatchedFolders
        ) {
            runCatching { syncWatchedFolders(importPipeline) }.getOrNull()?.let { results ->
                if (results.any { it is ImportResult.InTrash }) {
                    snackbar.showSnackbar(strings.added.importMatchInTrash)
                }
            }
        }
    }
    ModelDownloadNotification(container)
    ModelFailureNotifier(
        states = container.localModelManager.states,
        snackbar = snackbar,
        currentRoute = { navController.currentDestination?.route },
        onOpenModelManager = { navController.navigate(Routes.MODEL_MANAGER) { launchSingleTop = true } },
    )
    val activeMicOwner by container.micRecordings.owner.collectAsState()
    val activeMicId = activeMicOwner?.recordingId
    LaunchedEffect(container) {
        observeMicFailures(container) { message -> snackbar.showSnackbar(message.ifBlank { strings.micStartFailed }) }
    }
    val storageState by container.settingsRepository.loadState.collectAsState()
    LaunchedEffect(container, storageState) {
        if (storageState == dev.velum.core.settings.SettingsLoadState.Unavailable) {
            val result = snackbar.showSnackbar(
                message = strings.ux.secureStorageUnavailable,
                actionLabel = strings.ux.retryProcessing,
                withDismissAction = true,
                duration = SnackbarDuration.Indefinite,
            )
            if (result == SnackbarResult.ActionPerformed) container.settingsViewModel.reloadSettings()
        }
    }
    val importAudio = rememberAudioImporter(onFailed = { scope.launch { snackbar.showSnackbar(strings.ux.importCopyFailed) } }) { path ->
        scope.launch {
            fabOpen = false
            when (val result = importPipeline.importFile(path, source = "import")) {
                is ImportResult.Created -> {
                    navController.navigate(Routes.recording(result.recordingId))
                }
                is ImportResult.Duplicate -> {
                    snackbar.showSnackbar(strings.ux.importDuplicate)
                    navController.navigate(Routes.recording(result.existingRecordingId))
                }
                is ImportResult.Failed -> {
                    snackbar.showSnackbar(strings.ux.genericError)
                }
                is ImportResult.InTrash -> snackbar.showSnackbar(strings.added.importMatchInTrash)
                is ImportResult.Deleted -> Unit // watched-folder sync only
            }
        }
    }
    var watchedFoldersVersion by remember { mutableStateOf(0) }
    val pickWatchedFolder = rememberImportFolderPicker { _ ->
        // onPicked runs after the folder write committed, so the Import hub can re-read its count now.
        watchedFoldersVersion++
        scope.launch {
            snackbar.showSnackbar(strings.ux.folderSavedResync)
        }
    }

    // Save the pending identity, never approval. Recovery and recreation must ask again.
    var consentPath by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var consentRecoveredAt by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Long?>(null) }
    var shareConsent by remember { mutableStateOf(dev.velum.app.platform.ExternalShareConsent()) }
    if (consentPath != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { shareConsent.decide(false) },
            title = { androidx.compose.material3.Text(strings.externalShareConsentTitle) },
            text = { androidx.compose.material3.Text(strings.externalShareConsentBody) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { shareConsent.decide(true) }) {
                    androidx.compose.material3.Text(strings.confirm)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { shareConsent.decide(false) }) {
                    androidx.compose.material3.Text(strings.cancel)
                }
            },
        )
    }

    // Only a started (visible) instance takes a share: with several instances (Recents) a hidden one must not import and navigate.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(container, lifecycleOwner) {
      lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
        suspend fun report(result: ImportResult, recovered: Boolean) {
            if (recovered) {
                // A share re-offered after a restart can be up to a day old: say what happened, never move the user.
                when (result) {
                    is ImportResult.Created -> snackbar.showSnackbar(strings.ux.imported)
                    is ImportResult.Duplicate -> snackbar.showSnackbar(strings.ux.importDuplicate)
                    is ImportResult.Failed -> snackbar.showSnackbar(strings.ux.genericError)
                    is ImportResult.InTrash -> snackbar.showSnackbar(strings.added.importMatchInTrash)
                    is ImportResult.Deleted -> Unit
                }
                return
            }
            when (result) {
                is ImportResult.Created -> navController.navigate(Routes.recording(result.recordingId))
                is ImportResult.Duplicate -> {
                    snackbar.showSnackbar(strings.ux.importDuplicate)
                    navController.navigate(Routes.recording(result.existingRecordingId))
                }
                is ImportResult.Failed -> snackbar.showSnackbar(strings.ux.genericError)
                is ImportResult.InTrash -> snackbar.showSnackbar(strings.added.importMatchInTrash)
                is ImportResult.Deleted -> Unit // watched-folder sync only
            }
        }
        // Each share is copied off the main thread and arrives here when ready; null means this platform has none.
        while (true) {
            // An import started before this collector (re)started, or finished while the app was stopped, is reported once.
            container.pendingShare?.let { pending ->
                val result = try {
                    pending.await()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    if (!pending.isCancelled) throw e // this collector was cancelled
                    null // the import itself was cancelled (a wipe); nothing to report
                }
                if (container.pendingShare === pending) {
                    container.pendingShare = null
                    val recovered = container.pendingShareRecovered
                    result?.let { report(it, recovered) }
                }
            }
            val shared = consentPath?.let { SharedFile.Ready(it, consentRecoveredAt) }
                ?: awaitIncomingSharePath() ?: break
            val path = when (shared) {
                is SharedFile.Ready -> shared.path
                SharedFile.CopyFailed -> {
                    snackbar.showSnackbar(strings.ux.importCopyFailed)
                    continue
                }
            }
            consentRecoveredAt = (shared as SharedFile.Ready).recoveredAtMs
            consentPath = path
            val gate = shareConsent
            val approved = gate.runIfApproved {
                container.pendingShareRecovered = shared.recoveredAtMs != null
                container.pendingShare = importPipeline.startSharedDetached(path, shared.recoveredAtMs)
            }
            if (!approved) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
                    dev.velum.app.platform.markShareHandled(path)
                    if (dev.velum.app.platform.isImportCacheCopy(path)) dev.velum.app.platform.deleteFileIfPresent(path)
                }
            }
            consentPath = null
            consentRecoveredAt = null
            shareConsent = dev.velum.app.platform.ExternalShareConsent()
        }
      }
    }

    LaunchedEffect(container) {
        container.autoFlowRunner.actions.collect { action ->
            when (action) {
                AutoFlowUiAction.ProcessingFailed -> snackbar.showSnackbar(strings.ux.genericError)
                is AutoFlowUiAction.ShareMarkdown -> container.noteToShare(action.recordingId)
                    ?.let { shareText(it.title, it.markdown) }
                is AutoFlowUiAction.InsertCalendarEvent -> container.noteToShare(action.recordingId)
                    ?.let { openCalendarInsert(it.title, it.summary) }
                is AutoFlowUiAction.ExportMarkdownToFolder -> {
                    when (val result = container.exportRecordingToFolder(action.recordingId)) {
                        null -> snackbar.showSnackbar(strings.ux.recordingMissing)
                        is ExportFolderResult.Ok ->
                            snackbar.showSnackbar(strings.ux.folderExportOkFormat.replace("%s", result.displayPath))
                        is ExportFolderResult.Failed -> snackbar.showSnackbar(strings.ux.genericError)
                    }
                }
                is AutoFlowUiAction.PostWebhook -> {
                    val msg = container.webhookDispatcher.dispatch(action.recordingId)
                    snackbar.showSnackbar(
                        if (msg != null && msg.startsWith("HTTP 2")) {
                            strings.ux.webhookPostedFormat.replace("%s", msg)
                        } else {
                            strings.ux.webhookFailedFormat.replace(
                                "%s",
                                msg?.let { dev.velum.app.ui.integrations.WebhookTestResult.failureText(it, strings) }
                                    ?: strings.ux.webhookNotConfigured,
                            )
                        },
                    )
                }
                is AutoFlowUiAction.BlockedOnPermission -> {
                    if (action.permission.contains("calendar", ignoreCase = true)) {
                        requestCalendar { }
                    } else {
                        snackbar.showSnackbar(action.message)
                    }
                }
            }
        }
    }

    val onShell = route == Routes.FILES ||
        route == Routes.EXPLORE ||
        route.isEmpty()
    val selectedTab = when {
        route == Routes.EXPLORE -> ShellTab.Explore
        else -> ShellTab.Files
    }

    // The window is edge-to-edge, so adjustResize no longer shrinks it: lift the whole shell above the keyboard so
    // focused fields stay visible (#305). imePadding consumes the keyboard inset, which the Scaffold and the
    // navigation-bar padding of the bottom bar (#299) exclude, so nothing is padded twice.
    Box(Modifier.fillMaxSize().imePadding()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (onShell && !showFirstRun) {
                    VelumBottomBar(
                        selected = selectedTab,
                        fabOpen = fabOpen,
                        onFiles = {
                            fabOpen = false
                            navController.navigate(Routes.FILES) {
                                popUpTo(Routes.FILES) { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                        onExplore = {
                            fabOpen = false
                            navController.navigate(Routes.EXPLORE) {
                                launchSingleTop = true
                            }
                        },
                        onFab = { fabOpen = !fabOpen },
                    )
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Routes.FILES,
                modifier = Modifier.padding(padding),
                enterTransition = { navEnter },
                exitTransition = { navExit },
                popEnterTransition = { navEnter },
                popExitTransition = { navExit },
            ) {
                    composable(Routes.FILES) {
                        LibraryAdaptiveScreen(
                            container = container,
                            selectedRecordingId = librarySelectedId,
                            onSelectRecording = {
                                librarySelectedId = it
                                librarySeekSeconds = 0
                            },
                            onOpenRecordingCompact = { id -> navController.navigate(Routes.recording(id)) },
                            onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                            onOpenDevice = { navController.navigate(Routes.DEVICE) },
                            onOpenAskCompact = { navController.navigate(Routes.ASK_ACROSS) },
                            railDestination = libraryRail,
                            onRailDestination = { libraryRail = it },
                            detailSeekSeconds = librarySeekSeconds,
                            onOpenCitation = { id, seek ->
                                librarySelectedId = id
                                librarySeekSeconds = seek
                                libraryRail = LibraryRailDestination.Library
                            },
                        )
                    }
                    composable(Routes.ASK_ACROSS) {
                        AskAcrossScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                            onOpenCitation = { id, seek ->
                                navController.navigate(Routes.recording(id, seek))
                            },
                        )
                    }
                    composable(Routes.EXPLORE) {
                        ExploreScreen(
                            container = container,
                            onTemplates = { navController.navigate(Routes.TEMPLATES) },
                            onAutoFlow = { navController.navigate(Routes.AUTOFLOW) },
                            onIntegrations = { navController.navigate(Routes.INTEGRATIONS) },
                            onApiSettings = { navController.navigate(Routes.API) },
                        )
                    }
                    composable(
                        Routes.RECORDING,
                        arguments = listOf(
                            navArgument("id") { type = NavType.StringType },
                            navArgument("seek") { type = NavType.LongType },
                        ),
                    ) { entry ->
                        RecordingDetailScreen(
                            container = container,
                            recordingId = entry.arg("id"),
                            onBack = { navController.popBackStack() },
                            onOpenModelManager = { navController.navigate(Routes.MODEL_MANAGER) },
                            initialSeekSeconds = entry.arguments?.read { getLongOrNull("seek") } ?: 0L,
                            onMovedToTrash = { trashedId ->
                                scope.launch {
                                    val result = snackbar.showSnackbar(
                                        message = strings.ux.movedToTrash,
                                        actionLabel = strings.ux.undo,
                                        duration = SnackbarDuration.Short,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        container.repository.restoreRecording(trashedId)
                                    }
                                }
                            },
                        )
                    }
                    composable(Routes.SETTINGS) {
                        SettingsHubScreen(
                            onBack = { navController.popBackStack() },
                            onDevices = { navController.navigate(Routes.DEVICES_SETTINGS) },
                            onRecording = { navController.navigate(Routes.RECORDING_SETTINGS) },
                            onAiProcessing = { navController.navigate(Routes.AI_PROCESSING) },
                            onModelManager = { navController.navigate(Routes.MODEL_MANAGER) },
                            onMemory = { navController.navigate(Routes.MEMORY) },
                            onPersonalization = { navController.navigate(Routes.PERSONALIZATION) },
                            onPreferences = { navController.navigate(Routes.PREFERENCES) },
                            onApi = { navController.navigate(Routes.API) },
                            onVoiceProfiles = { navController.navigate(Routes.VOICE_PROFILES) },
                            onTemplates = { navController.navigate(Routes.TEMPLATES) },
                            onAutoFlow = { navController.navigate(Routes.AUTOFLOW) },
                            onIntegrations = { navController.navigate(Routes.INTEGRATIONS) },
                            onWatchedFolders = { navController.navigate(Routes.IMPORT_AUDIO) },
                            onAppearance = { navController.navigate(Routes.APPEARANCE) },
                            onStoragePrivacy = { navController.navigate(Routes.STORAGE_PRIVACY) },
                            onAbout = { navController.navigate(Routes.ABOUT) },
                        )
                    }
                    composable(Routes.DEVICES_SETTINGS) {
                        DevicesSettingsScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                            onDevice = { navController.navigate(Routes.DEVICE) },
                            onImportKeys = { navController.navigate(Routes.IMPORT_KEYS) },
                        )
                    }
                    composable(Routes.PERSONALIZATION) {
                        PersonalizationScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                            onMemory = { navController.navigate(Routes.MEMORY) },
                        )
                    }
                    composable(Routes.PREFERENCES) {
                        PreferencesScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                            onAiProcessing = { navController.navigate(Routes.AI_PROCESSING) },
                            onCustomVocabulary = { navController.navigate(Routes.MEMORY) },
                        )
                    }
                    composable(Routes.API) {
                        ApiSettingsScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                            onAiProcessing = { navController.navigate(Routes.AI_PROCESSING) },
                            onModelManager = { navController.navigate(Routes.MODEL_MANAGER) },
                        )
                    }
                    composable(Routes.APPEARANCE) {
                        AppearanceScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(Routes.ABOUT) {
                        AboutScreen(onBack = { navController.popBackStack() })
                    }
                    composable(Routes.STORAGE_PRIVACY) {
                        StoragePrivacyScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(Routes.RECORDING_SETTINGS) {
                        RecordingSettingsScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(Routes.VOICE_PROFILES) {
                        VoiceProfilesScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(Routes.IMPORT_KEYS) {
                        ImportDeviceKeysScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(Routes.IMPORT_AUDIO) {
                        ImportHubScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                            onOpenRecording = { id -> navController.navigate(Routes.recording(id)) },
                            onPickWatchedFolder = pickWatchedFolder,
                            onSyncWatchedFolder = { syncWatchedFolders(importPipeline) },
                            watchedFoldersVersion = watchedFoldersVersion,
                        )
                    }
                    composable(Routes.AI_PROCESSING) {
                        AiProcessingScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                            onModelManager = { navController.navigate(Routes.MODEL_MANAGER) },
                        )
                    }
                    composable(Routes.MODEL_MANAGER) {
                        ModelManagerScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(Routes.MEMORY) {
                        MemoryScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(Routes.TEMPLATES) {
                        TemplatesScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                            onOpenTemplate = { id -> navController.navigate(Routes.template(id)) },
                        )
                    }
                    composable(
                        Routes.TEMPLATE_DETAIL,
                        arguments = listOf(navArgument("id") { type = NavType.StringType }),
                    ) { entry ->
                        TemplateDetailScreen(
                            container = container,
                            templateId = entry.arg("id"),
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(Routes.AUTOFLOW) {
                        AutoFlowListScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                            onEdit = { id -> navController.navigate(Routes.autoflowEdit(id)) },
                            onBrowseTemplates = {
                                navController.navigate(Routes.AUTOFLOW_TEMPLATES)
                            },
                        )
                    }
                    composable(Routes.AUTOFLOW_TEMPLATES) {
                        AutoFlowTemplateLibraryScreen(
                            onBack = { navController.popBackStack() },
                            onUseTemplate = { templateId ->
                                navController.navigate(Routes.autoflowFromTemplate(templateId))
                            },
                        )
                    }
                    composable(
                        Routes.AUTOFLOW_FROM_TEMPLATE,
                        arguments = listOf(navArgument("templateId") { type = NavType.StringType }),
                    ) { entry ->
                        AutoFlowEditScreen(
                            container = container,
                            flowId = "new",
                            seedTemplateId = entry.arg("templateId"),
                            onBack = { navController.popBackStack() },
                            onOpenIntegrations = {
                                navController.navigate(Routes.INTEGRATIONS)
                            },
                        )
                    }
                    composable(
                        Routes.AUTOFLOW_EDIT,
                        arguments = listOf(navArgument("id") { type = NavType.StringType }),
                    ) { entry ->
                        AutoFlowEditScreen(
                            container = container,
                            flowId = entry.arg("id"),
                            onBack = { navController.popBackStack() },
                            onOpenIntegrations = {
                                navController.navigate(Routes.INTEGRATIONS)
                            },
                        )
                    }
                    composable(Routes.INTEGRATIONS) {
                        IntegrationsScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(Routes.DEVICE) {
                        DeviceScreen(
                            ble = container.ble,
                            viewModel = container.deviceViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }

        if (onShell && !showFirstRun) {
            FabOverlay(
                visible = fabOpen,
                isCapturing = container.micCapture.isCapturing || activeMicId != null,
                onStartRecording = {
                    val owned = container.micRecordings.owner.value
                    val activeId = owned?.recordingId
                    val capturing = container.micCapture.isCapturing || activeId != null
                    if (capturing) {
                        fabOpen = false
                        scope.launch {
                            if (activeId != null) {
                                when (val stopped = finalizeMicRecording(container, activeId, owned.session.absolutePath, owned.session)) {
                                    is MicStopResult.Stopped -> Unit // Finalizer queued the verified audio.
                                    is MicStopResult.Failed -> snackbar.showSnackbar(stopped.message.ifBlank { strings.micStartFailed })
                                    else -> Unit
                                }
                                navController.navigate(Routes.recording(activeId))
                            } else {
                                snackbar.showSnackbar(strings.micStartFailed)
                            }
                        }
                    } else {
                        fun startPhoneRecording() {
                            scope.launch {
                                val draft = dev.velum.app.platform.startPhoneMicDraft(container)
                                val id = draft.recordingId
                                when (val started = draft.result) {
                                    is MicStartResult.Started -> {
                                        fabOpen = false
                                        navController.navigate(Routes.recording(id))
                                    }
                                    MicStartResult.PermissionRequired -> {
                                        snackbar.showSnackbar(strings.micPermissionRationale)
                                        fabOpen = false
                                    }
                                    MicStartResult.Unsupported -> {
                                        snackbar.showSnackbar(strings.micUnsupported)
                                        fabOpen = false
                                    }
                                    is MicStartResult.Failed -> {
                                        snackbar.showSnackbar(strings.micStartFailed)
                                        fabOpen = false
                                    }
                                }
                            }
                        }
                        requestMic { outcome ->
                            when (outcome) {
                                PermissionOutcome.Granted,
                                PermissionOutcome.NotRequired,
                                -> requestNotifications {
                                    // FGS still starts if notifications are denied; chrome may be limited.
                                    startPhoneRecording()
                                }
                                PermissionOutcome.Denied -> {
                                    scope.launch {
                                        snackbar.showSnackbar(strings.micPermissionRationale)
                                    }
                                }
                            }
                        }
                    }
                },
                onImportAudio = {
                    fabOpen = false
                    navController.navigate(Routes.IMPORT_AUDIO)
                },
                onDismiss = { fabOpen = false },
            )
        }

        if (showFirstRun) {
            // Same numbers the Download tap checks: models already Ready are not counted.
            val packIds = remember(strings.localeTag) { ModelCatalogUi.recommendedModelIds(strings.localeTag) }
            val modelStates by container.localModelManager.states.collectAsState()
            val recommendedTotal = remember(modelStates, packIds) { container.localModelManager.downloadBytesFor(packIds) }
            val recommendedNeeded = remember(modelStates, packIds) { container.localModelManager.bytesNeededFor(packIds) }
            FirstRunScreen(
                onPhoneMic = {
                    dismissFirstRun()
                    fabOpen = true
                },
                onImportRecorder = {
                    dismissFirstRun()
                    navController.navigate(Routes.IMPORT_AUDIO)
                },
                onConnectPlaud = {
                    dismissFirstRun()
                    navController.navigate(Routes.DEVICE)
                },
                onDownloadModels = {
                    dismissFirstRun()
                    navController.navigate(Routes.MODEL_MANAGER)
                },
                recommendedTotalBytes = recommendedTotal,
                recommendedNeededBytes = recommendedNeeded,
                notice = firstRunShort?.let { (needed, free) ->
                    ModelCatalogUi.fill(strings.firstRun.notEnoughSpaceFormat, ModelCatalogUi.formatBytes(needed), ModelCatalogUi.formatBytes(free))
                },
                onDownloadRecommended = {
                    if (firstRunDownloading) return@FirstRunScreen // a second tap must not start or navigate twice
                    firstRunDownloading = true
                    val ids = ModelCatalogUi.recommendedModelIds(
                        languageHint = strings.localeTag,
                    )
                    scope.launch(Dispatchers.Main.immediate) {
                        // Disk queries stay off the main thread; the writes after them are on Main (also under a test clock).
                        val (needed, free) = withContext(Dispatchers.Default) {
                            container.localModelManager.bytesNeededFor(ids) to container.localModelManager.freeSpaceBytes()
                        }
                        if (free != null && free < needed) {
                            firstRunShort = needed to free
                            firstRunDownloading = false
                            return@launch
                        }
                        // Skip or Use API during the disk check already left first run: do not start the pack then.
                        if (firstRunDismissed) return@launch
                        dismissFirstRun()
                        // The downloads outlive this screen; Model manager shows their progress and any failure.
                        // Navigating right away (not after they end) cannot pull the user back later.
                        container.localModelManager.startDownloads(ids)
                        navController.navigate(Routes.MODEL_MANAGER)
                    }
                },
                onUseApi = {
                    dismissFirstRun()
                    scope.launch {
                        container.settingsRepository.setTranscriptionMode(ComputeMode.Api)
                        // Diarization stays Local (no remote contract).
                        container.settingsRepository.setSummaryMode(ComputeMode.Api)
                        navController.navigate(Routes.API)
                    }
                },
                onSkip = {
                    dismissFirstRun()
                },
            )
        }
    }
}

private fun androidx.navigation.NavBackStackEntry.arg(key: String): String =
    arguments?.read { getStringOrNull(key) }.orEmpty()
