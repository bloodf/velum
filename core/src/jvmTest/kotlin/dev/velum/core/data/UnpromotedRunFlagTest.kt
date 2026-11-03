package dev.velum.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class UnpromotedRunFlagTest {
    @Test fun flag_round_trips_per_recording_and_is_cleared_by_wipe() = runBlocking {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        assertFalse(repo.isUnpromotedRun("a"))
        repo.setUnpromotedRun("a", true)
        assertTrue(repo.isUnpromotedRun("a"))
        assertFalse(repo.isUnpromotedRun("b"))
        repo.setUnpromotedRun("a", false)
        assertFalse(repo.isUnpromotedRun("a"))
        repo.setUnpromotedRun("b", true)
        repo.wipeAllUserData()
        assertEquals(false, repo.isUnpromotedRun("b"))
    }
}
