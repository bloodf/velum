package dev.velum.app.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * FirstRun gate is an in-memory [kotlinx.coroutines.flow.StateFlow] on
 * [AppContainer]: dismiss must hide immediately (before DB persist), and a
 * fresh install must show (#106).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FirstRunPendingTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("velum.db")
    }

    @Test
    fun freshInstall_showsFirstRun() {
        val container = AppContainer(
            secureStore = InMemorySecureStore(),
            driverFactory = DriverFactory(context),
        )
        assertTrue(
            container.firstRunPending.value,
            "Fresh install must seed firstRunPending=true for first paint",
        )
    }

    @Test
    fun dismiss_hidesImmediately_evenBeforePersist_andSurvivesRecreateSeed() {
        val container = AppContainer(
            secureStore = InMemorySecureStore(),
            driverFactory = DriverFactory(context),
        )
        assertTrue(container.firstRunPending.value)

        container.markFirstRunDone()

        // Synchronous in-memory clear — must not wait on appScope persist.
        assertFalse(
            container.firstRunPending.value,
            "markFirstRunDone must clear firstRunPending before DB write lands",
        )
        // Activity recreate re-collects from the same process-scoped flow;
        // seed must stay false even if repository.markFirstRunDone is still queued.
        val recreateSeed = container.firstRunPending.value
        assertFalse(
            recreateSeed,
            "Recreate must keep FirstRun hidden from in-memory flow before persist",
        )
    }
}
