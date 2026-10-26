package dev.velum.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.velum.app.di.AppContainer
import dev.velum.app.ui.DeviceScreen
import dev.velum.app.ui.RecordingsScreen
import dev.velum.app.ui.SettingsScreen
import dev.velum.app.ui.theme.VelumTheme

private enum class Dest(val route: String, val label: String) {
    Device("device", "Device"),
    Recordings("recordings", "Recordings"),
    Settings("settings", "Settings"),
}

@Composable
fun App(container: AppContainer) {
    VelumTheme {
        val navController = rememberNavController()
        val backStack by navController.currentBackStackEntryAsState()
        val current = backStack?.destination?.route

        Scaffold(
            bottomBar = {
                NavigationBar {
                    Dest.entries.forEach { dest ->
                        NavigationBarItem(
                            selected = current == dest.route,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = when (dest) {
                                        Dest.Device -> Icons.Outlined.Sensors
                                        Dest.Recordings -> Icons.AutoMirrored.Outlined.List
                                        Dest.Settings -> Icons.Outlined.Settings
                                    },
                                    contentDescription = dest.label,
                                )
                            },
                            label = { Text(dest.label) },
                        )
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Dest.Device.route,
                modifier = Modifier.padding(padding),
            ) {
                composable(Dest.Device.route) {
                    DeviceScreen(container.ble, container.deviceViewModel)
                }
                composable(Dest.Recordings.route) {
                    RecordingsScreen()
                }
                composable(Dest.Settings.route) {
                    SettingsScreen(container.settingsViewModel)
                }
            }
        }
    }
}
