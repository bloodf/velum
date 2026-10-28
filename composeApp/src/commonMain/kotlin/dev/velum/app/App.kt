package dev.velum.app

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import dev.velum.app.platform.consumeIncomingSharePath
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
import dev.velum.app.platform.writeMarkdownToExportFolder
import dev.velum.core.integrations.MarkdownNoteExport
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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
    var showFirstRun by remember { mutableStateOf(false) }
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

    LaunchedEffect(container) {
        showFirstRun = !container.repository.isFirstRunDone()
    }

    val importPipeline = remember(container) { AudioImportPipeline(container) }
    LaunchedEffect(container, importPipeline) {
        runCatching { container.settingsRepository.load() }
        if (container.settingsRepository.preferences.value.autoSyncWatchedFolders) {
            runCatching { syncWatchedFolders(importPipeline) }
        }
    }
    val importAudio = rememberAudioImporter { path ->
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
            }
        }
    }
    val pickWatchedFolder = rememberImportFolderPicker { _ ->
        scope.launch {
            snackbar.showSnackbar(strings.ux.folderSavedResync)
        }
    }

    LaunchedEffect(container) {
        consumeIncomingSharePath()?.let { path ->
            when (val result = importPipeline.importFile(path, source = "share")) {
                is ImportResult.Created -> navController.navigate(Routes.recording(result.recordingId))
                is ImportResult.Duplicate -> {
                    snackbar.showSnackbar(strings.ux.importDuplicate)
                    navController.navigate(Routes.recording(result.existingRecordingId))
                }
                is ImportResult.Failed -> snackbar.showSnackbar(strings.ux.genericError)
            }
        }
    }

    LaunchedEffect(container) {
        container.autoFlowRunner.actions.collect { action ->
            when (action) {
                is AutoFlowUiAction.ShareMarkdown -> shareText(action.title, action.body)
                is AutoFlowUiAction.InsertCalendarEvent ->
                    openCalendarInsert(action.title, action.description)
                is AutoFlowUiAction.ExportMarkdownToFolder -> {
                    val rec = container.repository.getRecording(action.recordingId)
                    if (rec == null) {
                        snackbar.showSnackbar(strings.ux.recordingMissing)
                    } else {
                        val speakers = container.repository.getSpeakers(action.recordingId)
                            .mapNotNull { it.display_name ?: it.label }
                        val note = MarkdownNoteExport.Note(
                            title = rec.title,
                            startedAtEpochSeconds = rec.started_at,
                            durationSeconds = rec.duration_seconds,
                            source = rec.source,
                            speakers = speakers,
                            tags = listOf("velum"),
                            summaryMarkdown = rec.summary_markdown,
                            transcriptMarkdown = rec.transcript,
                        )
                        val md = MarkdownNoteExport.render(note)
                        val name = MarkdownNoteExport.filename(rec.title, rec.started_at)
                        when (val result = writeMarkdownToExportFolder(name, md)) {
                            is ExportFolderResult.Ok ->
                                snackbar.showSnackbar(
                                    strings.ux.folderExportOkFormat.replace("%s", result.displayPath),
                                )
                            is ExportFolderResult.Failed ->
                                snackbar.showSnackbar(strings.ux.genericError)
                        }
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
                                msg ?: strings.ux.webhookNotConfigured,
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

    Box(Modifier.fillMaxSize()) {
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
                isCapturing = container.micCapture.isCapturing ||
                    container.activeMicRecordingId != null,
                onStartRecording = {
                    val activeId = container.activeMicRecordingId
                    val capturing = container.micCapture.isCapturing || activeId != null
                    if (capturing) {
                        fabOpen = false
                        scope.launch {
                            if (activeId != null) {
                                finalizeMicRecording(container, activeId)
                                container.repository.updateStatus(activeId, "queued")
                                container.enqueueProcessing(
                                    recordingId = activeId,
                                    templateId = "velum.meeting",
                                    retranscribe = true,
                                )
                                navController.navigate(Routes.recording(activeId))
                            } else {
                                container.micCapture.stop()
                            }
                        }
                    } else {
                        fun startPhoneRecording() {
                            scope.launch {
                                val id = container.createLocalRecording(
                                    source = "phone",
                                    status = "recording",
                                )
                                val wavPath = "${recordingsDirectoryPath()}/$id.wav"
                                when (val started = container.micCapture.start(wavPath)) {
                                    MicStartResult.Started -> {
                                        container.activeMicRecordingId = id
                                        container.repository.updateLocalPath(
                                            id = id,
                                            localPath = wavPath,
                                            durationSeconds = 0,
                                            sizeBytes = 0,
                                        )
                                        fabOpen = false
                                        navController.navigate(Routes.recording(id))
                                    }
                                    MicStartResult.PermissionRequired -> {
                                        snackbar.showSnackbar(strings.micPermissionRationale)
                                        // Do not keep a stuck "recording" row without capture.
                                        container.repository.trashRecording(id)
                                        fabOpen = false
                                    }
                                    MicStartResult.Unsupported -> {
                                        snackbar.showSnackbar(strings.micUnsupported)
                                        container.repository.trashRecording(id)
                                        fabOpen = false
                                    }
                                    is MicStartResult.Failed -> {
                                        snackbar.showSnackbar(
                                            started.message.ifBlank { strings.micUnsupported },
                                        )
                                        container.repository.trashRecording(id)
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
            FirstRunScreen(
                onPhoneMic = {
                    scope.launch {
                        container.repository.markFirstRunDone()
                        showFirstRun = false
                        fabOpen = true
                    }
                },
                onImportRecorder = {
                    scope.launch {
                        container.repository.markFirstRunDone()
                        showFirstRun = false
                        navController.navigate(Routes.IMPORT_AUDIO)
                    }
                },
                onConnectPlaud = {
                    scope.launch {
                        container.repository.markFirstRunDone()
                        showFirstRun = false
                        navController.navigate(Routes.DEVICE)
                    }
                },
                onDownloadModels = {
                    scope.launch {
                        container.repository.markFirstRunDone()
                        showFirstRun = false
                        navController.navigate(Routes.MODEL_MANAGER)
                    }
                },
                onDownloadRecommended = {
                    scope.launch {
                        val ids = ModelCatalogUi.recommendedModelIds(
                            languageHint = strings.localeTag,
                        )
                        ids.forEach { id ->
                            runCatching {
                                container.localModelManager.download(id).collect()
                            }
                        }
                        container.repository.markFirstRunDone()
                        showFirstRun = false
                        navController.navigate(Routes.MODEL_MANAGER)
                    }
                },
                onUseApi = {
                    scope.launch {
                        container.settingsRepository.setTranscriptionMode(ComputeMode.Api)
                        // Diarization stays Local (no remote contract).
                        container.settingsRepository.setSummaryMode(ComputeMode.Api)
                        container.repository.markFirstRunDone()
                        showFirstRun = false
                        navController.navigate(Routes.API)
                    }
                },
                onSkip = {
                    scope.launch {
                        container.repository.markFirstRunDone()
                        showFirstRun = false
                    }
                },
            )
        }
    }
}

private fun androidx.navigation.NavBackStackEntry.arg(key: String): String =
    arguments?.read { getStringOrNull(key) }.orEmpty()
