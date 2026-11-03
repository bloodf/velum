package dev.velum.ai.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** #330: only a rerun after a stopped unpromoted run fails; other retries never do. */
class UnpromotedRunGuardTest {
    private class Flag { var on = false }

    private fun run(f: Flag, promoted: Boolean, apiOnly: Boolean = false) = runBlocking {
        failUnpromotedRerun(promoted, apiOnly, wasUnpromoted = { f.on }, setFlag = { f.on = it })
    }

    @Test fun a_first_refused_start_runs_and_records_itself() {
        val f = Flag()
        assertFalse(run(f, promoted = false))
        assertTrue(f.on)
    }

    @Test fun a_rerun_after_a_stopped_unpromoted_run_fails_once_and_clears() {
        val f = Flag().apply { on = true } // the earlier unpromoted run was stopped: it never cleared the flag
        assertTrue(run(f, promoted = false))
        assertFalse(f.on)
    }

    @Test fun a_thermal_retry_then_a_refused_start_is_not_failed() {
        val f = Flag()
        assertFalse(run(f, promoted = true))   // first run, promoted, ends in a thermal retry (flag cleared)
        assertFalse(run(f, promoted = false))  // the retry is refused: a fresh unpromoted run, not a loop
        assertTrue(f.on)
    }

    @Test fun a_short_unpromoted_run_that_ends_on_its_own_is_not_a_loop() {
        val f = Flag()
        assertFalse(run(f, promoted = false))
        f.on = false // the worker clears the flag when the job ends by itself
        assertFalse(run(f, promoted = false))
    }

    @Test fun a_promoted_rerun_clears_the_flag_and_runs() {
        val f = Flag().apply { on = true }
        assertFalse(run(f, promoted = true))
        assertFalse(f.on)
    }

    @Test fun api_only_jobs_are_exempt() {
        val f = Flag().apply { on = true }
        assertFalse(run(f, promoted = false, apiOnly = true))
        assertEquals(true, f.on)
    }
}
