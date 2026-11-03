package dev.velum.core.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.velum.core.db.Recording
import dev.velum.core.db.VelumDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/** #208: a failed index write must not drop the device-session row (its audio is already on disk). */
class DeviceSessionIndexFailureTest {
    @Test
    fun indexFailure_keepsTheRow_andFlagsTheIndexStale() = runBlocking {
        val real = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VelumDatabase.Schema.create(real)
        var failIndex = false
        val driver = object : SqlDriver by real {
            override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
                if (failIndex && sql.contains("INSERT INTO recording_fts")) throw IllegalStateException("index write failed")
                return real.execute(identifier, sql, parameters, binders)
            }
        }
        try {
            val repo = VelumRepository(VelumDatabase(driver), driver)
            assertNull(repo.getMeta(VelumRepository.META_FTS_DIRTY))
            failIndex = true
            repo.insertDeviceSessionRecording(
                Recording(
                    id = "rec-dev-1", session_id = 7, folder_id = null, title = "Synthetic", source = "note_pro",
                    device_serial = "SN1", started_at = 1, duration_seconds = 0, size_bytes = 1, local_path = "/x",
                    transcript = null, summary_markdown = null, status = "queued", trashed = 0, created_at = 1,
                    processing_warning = null,
                ),
            )
            assertNotNull(repo.getRecording("rec-dev-1"))
            assertEquals("1", repo.getMeta(VelumRepository.META_FTS_DIRTY))
        } finally {
            real.close()
        }
    }
}
