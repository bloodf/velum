package dev.velum.app

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.i18n.PtBr
import dev.velum.app.navigation.Routes
import dev.velum.app.platform.AppPermission
import dev.velum.app.platform.LocalHaptics
import dev.velum.app.platform.MicStartResult
import dev.velum.app.platform.PermissionOutcome
import dev.velum.app.platform.openCalendarInsert
import dev.velum.app.platform.recordingsDirectoryPath
import dev.velum.app.platform.rememberAudioImporter
import dev.velum.app.platform.rememberHaptics
import dev.velum.app.platform.rememberPermissionRequester
import dev.velum.app.platform.shareText
import dev.velum.app.ui.motion.Motion
import dev.velum.app.ui.DeviceScreen
import dev.velum.app.ui.autoflow.AutoFlowEditScreen
import dev.velum.app.ui.autoflow.AutoFlowListScreen
import dev.velum.app.ui.explore.ExploreScreen
import dev.velum.app.ui.files.FilesScreen
import dev.velum.app.ui.integrations.IntegrationsScreen
import dev.velum.app.ui.onboarding.FirstRunScreen
import dev.velum.app.ui.recording.RecordingDetailScreen
import dev.velum.app.ui.settings.AccountScreen
import dev.velum.app.ui.settings.AiProcessingScreen
import dev.velum.app.ui.settings.ImportDeviceKeysScreen
import dev.velum.app.ui.settings.MemoryScreen
import dev.velum.app.ui.settings.ModelManagerScreen
import dev.velum.app.ui.settings.PersonalizationScreen
import dev.velum.app.ui.settings.PreferencesScreen
import dev.velum.app.ui.settings.SettingsHubScreen
import dev.velum.app.ui.shell.FabOverlay
import dev.velum.app.ui.shell.ShellTab
import dev.velum.app.ui.shell.VelumBottomBar
import dev.velum.app.ui.templates.TemplateDetailScreen
import dev.velum.app.ui.templates.TemplatesScreen
import dev.velum.app.ui.theme.VelumTheme
import dev.velum.core.model.ComputeMode
import kotlinx.coroutines.launch

@Composable
fun App(container: AppContainer) {
    VelumTheme {
        val haptics = rememberHaptics()
        CompositionLocalProvider(
            LocalStrings provides PtBr,
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

    val importAudio = rememberAudioImporter { path ->
        scope.launch {
            val id = container.createLocalRecording(
                source = "import",
                localPath = path,
                status = "queued",
            )
            fabOpen = false
            navController.navigate(Routes.recording(id))
            container.enqueueProcessing(
                recordingId = id,
                templateId = null,
                retranscribe = true,
            )
        }
    }

    LaunchedEffect(container) {
        container.autoFlowRunner.actions.collect { action ->
            when (action) {
                is AutoFlowUiAction.ShareMarkdown -> shareText(action.title, action.body)
                is AutoFlowUiAction.InsertCalendarEvent ->
                    openCalendarInsert(action.title, action.description)
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
                        FilesScreen(
                            container = container,
                            onOpenRecording = { id -> navController.navigate(Routes.recording(id)) },
                            onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                        )
                    }
                    composable(Routes.EXPLORE) {
                        ExploreScreen(
                            container = container,
                            onTemplates = { navController.navigate(Routes.TEMPLATES) },
                            onAutoFlow = { navController.navigate(Routes.AUTOFLOW) },
                            onIntegrations = { navController.navigate(Routes.INTEGRATIONS) },
                            onAccount = { navController.navigate(Routes.ACCOUNT) },
                        )
                    }
                    composable(
                        Routes.RECORDING,
                        arguments = listOf(navArgument("id") { type = NavType.StringType }),
                    ) { entry ->
                        RecordingDetailScreen(
                            container = container,
                            recordingId = entry.arg("id"),
                            onBack = { navController.popBackStack() },
                            onOpenModelManager = { navController.navigate(Routes.MODEL_MANAGER) },
                        )
                    }
                    composable(Routes.SETTINGS) {
                        SettingsHubScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                            onPersonalization = { navController.navigate(Routes.PERSONALIZATION) },
                            onPreferences = { navController.navigate(Routes.PREFERENCES) },
                            onAccount = { navController.navigate(Routes.ACCOUNT) },
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
                    composable(Routes.ACCOUNT) {
                        AccountScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                            onDevice = { navController.navigate(Routes.DEVICE) },
                            onAiProcessing = { navController.navigate(Routes.AI_PROCESSING) },
                            onModelManager = { navController.navigate(Routes.MODEL_MANAGER) },
                            onImportKeys = { navController.navigate(Routes.IMPORT_KEYS) },
                        )
                    }
                    composable(Routes.IMPORT_KEYS) {
                        ImportDeviceKeysScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
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
                        )
                    }
                    composable(Routes.INTEGRATIONS) {
                        IntegrationsScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(Routes.DEVICE) {
                        DeviceScreen(container.ble, container.deviceViewModel)
                    }
                }
            }

        if (onShell && !showFirstRun) {
            FabOverlay(
                visible = fabOpen,
                isCapturing = container.micCapture.isCapturing ||
                    container.activeMicRecordingId != null,
                onStartRecording = {
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
                                }
                                MicStartResult.PermissionRequired -> {
                                    snackbar.showSnackbar(strings.micPermissionRationale)
                                    container.activeMicRecordingId = id
                                }
                                MicStartResult.Unsupported -> {
                                    snackbar.showSnackbar(strings.micUnsupported)
                                    container.activeMicRecordingId = id
                                }
                                is MicStartResult.Failed -> {
                                    snackbar.showSnackbar(
                                        started.message.ifBlank { strings.micUnsupported },
                                    )
                                    container.activeMicRecordingId = id
                                }
                            }
                            fabOpen = false
                            navController.navigate(Routes.recording(id))
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
                },
                onImportAudio = { importAudio() },
                onDismiss = { fabOpen = false },
            )
        }

        if (showFirstRun) {
            FirstRunScreen(
                onDownload = {
                    scope.launch {
                        container.repository.markFirstRunDone()
                        showFirstRun = false
                        navController.navigate(Routes.MODEL_MANAGER)
                    }
                },
                onUseApi = {
                    scope.launch {
                        container.settingsRepository.setTranscriptionMode(ComputeMode.Api)
                        container.settingsRepository.setDiarizationMode(ComputeMode.Api)
                        container.settingsRepository.setSummaryMode(ComputeMode.Api)
                        container.repository.markFirstRunDone()
                        showFirstRun = false
                        navController.navigate(Routes.ACCOUNT)
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
