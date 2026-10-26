package dev.velum.core.search

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * SQLite FTS5 index managed outside SQLDelight .sq files.
 * SQLDelight 2.0's default dialect stack-overflows on VIRTUAL TABLE / MATCH,
 * so DDL + search run through [SqlDriver] directly. Real FTS5 on Android/JVM.
 *
 * Returns false when the host SQLite has no `fts5` module (e.g. Robolectric) —
 * callers must fall back to [ListSearch].
 */
object FtsIndex {
    fun ensureSchema(driver: SqlDriver): Boolean {
        return try {
            driver.execute(
                identifier = null,
                sql = """
                    CREATE VIRTUAL TABLE IF NOT EXISTS recording_fts USING fts5(
                      recording_id UNINDEXED,
                      title,
                      transcript,
                      summary_markdown,
                      tokenize = 'porter unicode61'
                    )
                """.trimIndent(),
                parameters = 0,
            )
            driver.execute(
                identifier = null,
                sql = """
                    CREATE VIRTUAL TABLE IF NOT EXISTS template_fts USING fts5(
                      template_id UNINDEXED,
                      name,
                      description,
                      category,
                      system_prompt,
                      tokenize = 'porter unicode61'
                    )
                """.trimIndent(),
                parameters = 0,
            )
            true
        } catch (_: Exception) {
            // Robolectric / stripped SQLite: "no such module: fts5"
            false
        }
    }

    fun upsertRecording(
        driver: SqlDriver,
        id: String,
        title: String,
        transcript: String?,
        summaryMarkdown: String?,
    ) {
        driver.execute(
            identifier = null,
            sql = "DELETE FROM recording_fts WHERE recording_id = ?",
            parameters = 1,
        ) { bindString(0, id) }
        driver.execute(
            identifier = null,
            sql = "INSERT INTO recording_fts(recording_id, title, transcript, summary_markdown) VALUES (?, ?, ?, ?)",
            parameters = 4,
        ) {
            bindString(0, id)
            bindString(1, title)
            bindString(2, transcript.orEmpty())
            bindString(3, summaryMarkdown.orEmpty())
        }
    }

    fun deleteRecording(driver: SqlDriver, id: String) {
        driver.execute(
            identifier = null,
            sql = "DELETE FROM recording_fts WHERE recording_id = ?",
            parameters = 1,
        ) { bindString(0, id) }
    }

    fun upsertTemplate(
        driver: SqlDriver,
        id: String,
        name: String,
        description: String,
        category: String,
        systemPrompt: String,
    ) {
        driver.execute(
            identifier = null,
            sql = "DELETE FROM template_fts WHERE template_id = ?",
            parameters = 1,
        ) { bindString(0, id) }
        driver.execute(
            identifier = null,
            sql = "INSERT INTO template_fts(template_id, name, description, category, system_prompt) VALUES (?, ?, ?, ?, ?)",
            parameters = 5,
        ) {
            bindString(0, id)
            bindString(1, name)
            bindString(2, description)
            bindString(3, category)
            bindString(4, systemPrompt)
        }
    }

    fun deleteTemplate(driver: SqlDriver, id: String) {
        driver.execute(
            identifier = null,
            sql = "DELETE FROM template_fts WHERE template_id = ?",
            parameters = 1,
        ) { bindString(0, id) }
    }

    fun searchRecordingIds(driver: SqlDriver, matchQuery: String): List<String> =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT recording_id FROM recording_fts WHERE recording_fts MATCH ? ORDER BY rank",
            mapper = { cursor ->
                val ids = mutableListOf<String>()
                while (cursor.next().value) {
                    cursor.getString(0)?.let { ids += it }
                }
                QueryResult.Value(ids)
            },
            parameters = 1,
        ) {
            bindString(0, matchQuery)
        }.value

    fun searchTemplateIds(driver: SqlDriver, matchQuery: String): List<String> =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT template_id FROM template_fts WHERE template_fts MATCH ? ORDER BY rank",
            mapper = { cursor ->
                val ids = mutableListOf<String>()
                while (cursor.next().value) {
                    cursor.getString(0)?.let { ids += it }
                }
                QueryResult.Value(ids)
            },
            parameters = 1,
        ) {
            bindString(0, matchQuery)
        }.value
}
