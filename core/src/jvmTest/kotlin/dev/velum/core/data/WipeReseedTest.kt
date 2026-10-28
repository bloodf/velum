package dev.velum.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class WipeReseedTest {
    @Test
    fun wipe_reseeds_default_autoflow_and_builtins() = runTest {
        val handle = createDatabase(DriverFactory())
        val repo = VelumRepository(handle)
        val before = handle.database.velumQueries.selectAutoflows().executeAsList()
        assertTrue(before.any { it.id == "flow-default" }, "seed should insert flow-default")

        repo.wipeAllUserData()

        val after = handle.database.velumQueries.selectAutoflows().executeAsList()
        assertTrue(
            after.any { it.id == "flow-default" },
            "wipe must reseed default AutoFlow; got ids=${after.map { it.id }}",
        )
        val builtins = handle.database.velumQueries.selectTemplates().executeAsList()
            .filter { it.builtin != 0L }
        assertTrue(builtins.isNotEmpty(), "wipe must keep/reseed builtin templates")
    }
}
