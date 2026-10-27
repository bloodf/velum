package dev.velum.core.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class AppearancePreferencesTest {
    @Test
    fun themeAndLanguageRoundTrip() = runTest {
        val repo = SettingsRepository(dev.velum.core.security.InMemorySecureStore())
        repo.load()
        assertEquals("system", repo.preferences.value.themeMode)
        assertEquals("system", repo.preferences.value.appLanguage)

        repo.setThemeMode("dark")
        repo.setAppLanguage("en")
        repo.setDefaultMicInput("Camcorder")
        assertEquals("dark", repo.preferences.value.themeMode)
        assertEquals("en", repo.preferences.value.appLanguage)
        assertEquals("Camcorder", repo.preferences.value.defaultMicInput)

        repo.setThemeMode("nope")
        assertEquals("system", repo.preferences.value.themeMode)
        repo.setAppLanguage("fr")
        assertEquals("system", repo.preferences.value.appLanguage)
    }

    @Test
    fun deviceAndStoragePrefsRoundTrip() = runTest {
        val repo = SettingsRepository(dev.velum.core.security.InMemorySecureStore())
        repo.load()
        assertEquals(true, repo.preferences.value.preferWifiFastPath)
        assertEquals(true, repo.preferences.value.autoSyncWatchedFolders)
        assertEquals(false, repo.preferences.value.deleteAudioAfterProcessing)

        repo.setPreferWifiFastPath(false)
        repo.setAutoSyncWatchedFolders(false)
        repo.setDeleteAudioAfterProcessing(true)
        assertEquals(false, repo.preferences.value.preferWifiFastPath)
        assertEquals(false, repo.preferences.value.autoSyncWatchedFolders)
        assertEquals(true, repo.preferences.value.deleteAudioAfterProcessing)
    }
}
