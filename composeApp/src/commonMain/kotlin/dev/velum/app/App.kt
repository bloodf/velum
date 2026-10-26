package dev.velum.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.savedstate.read
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.i18n.PtBr
import dev.velum.app.navigation.Routes
import dev.velum.app.ui.DeviceScreen
import dev.velum.app.ui.autoflow.AutoFlowEditScreen
import dev.velum.app.ui.autoflow.AutoFlowListScreen
import dev.velum.app.ui.explore.ExploreScreen
import dev.velum.app.ui.files.FilesScreen
import dev.velum.app.ui.integrations.IntegrationsScreen
import dev.velum.app.ui.recording.RecordingDetailScreen
import dev.velum.app.ui.settings.AccountScreen
import dev.velum.app.ui.settings.MemoryScreen
import dev.velum.app.ui.settings.PersonalizationScreen
import dev.velum.app.ui.settings.PreferencesScreen
import dev.velum.app.ui.settings.SettingsHubScreen
import dev.velum.app.ui.shell.FabOverlay
import dev.velum.app.ui.shell.ShellTab
import dev.velum.app.ui.shell.VelumBottomBar
import dev.velum.app.ui.templates.TemplateDetailScreen
import dev.velum.app.ui.templates.TemplatesScreen
import dev.velum.app.ui.theme.VelumTheme

@Composable
fun App(container: AppContainer) {
    VelumTheme {
        CompositionLocalProvider(LocalStrings provides PtBr) {
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

    val onShell = route == Routes.FILES ||
        route == Routes.EXPLORE ||
        route.isEmpty() // initial composition before NavHost settles
    val selectedTab = when {
        route == Routes.EXPLORE -> ShellTab.Explore
        else -> ShellTab.Files
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                if (onShell) {
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
                        onBack = { navController.popBackStack() },
                        onMemory = { navController.navigate(Routes.MEMORY) },
                    )
                }
                composable(Routes.PREFERENCES) {
                    PreferencesScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.ACCOUNT) {
                    AccountScreen(
                        container = container,
                        onBack = { navController.popBackStack() },
                        onDevice = { navController.navigate(Routes.DEVICE) },
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
                    IntegrationsScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.DEVICE) {
                    DeviceScreen(container.ble, container.deviceViewModel)
                }
            }
        }

        if (fabOpen && onShell) {
            FabOverlay(
                onStartRecording = { fabOpen = false },
                onImportAudio = { fabOpen = false },
                onDismiss = { fabOpen = false },
            )
        }
    }
}

private fun androidx.navigation.NavBackStackEntry.arg(key: String): String =
    arguments?.read { getStringOrNull(key) }.orEmpty()

