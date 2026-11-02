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
    data class EnsureResult(
        val available: Boolean,
        /** True when template_fts was dropped/recreated (caller must rebuild rows). */
        val templateSchemaRecreated: Boolean,
        /** Exception class when FTS is unavailable (no message: it may echo SQL); null otherwise. */
        val failure: String? = null,
    )

    fun ensureSchema(driver: SqlDriver): EnsureResult {
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
            val needsTagsUpgrade = templateFtsNeedsTagsColumn(driver)
            if (needsTagsUpgrade) {
                // Keep DROP+CREATE atomic so a kill mid-upgrade cannot leave FTS missing.
                driver.execute(identifier = null, sql = "BEGIN IMMEDIATE", parameters = 0)
                try {
                    driver.execute(
                        identifier = null,
                        sql = "DROP TABLE IF EXISTS template_fts",
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
                              tags,
                              tokenize = 'porter unicode61'
                            )
                        """.trimIndent(),
                        parameters = 0,
                    )
                    driver.execute(identifier = null, sql = "COMMIT", parameters = 0)
                } catch (t: Throwable) {
                    runCatching {
                        driver.execute(identifier = null, sql = "ROLLBACK", parameters = 0)
                    }
                    throw t
                }
            } else {
                driver.execute(
                    identifier = null,
                    sql = """
                        CREATE VIRTUAL TABLE IF NOT EXISTS template_fts USING fts5(
                          template_id UNINDEXED,
                          name,
                          description,
                          category,
                          system_prompt,
                          tags,
                          tokenize = 'porter unicode61'
                        )
                    """.trimIndent(),
                    parameters = 0,
                )
            }
            // CREATE ... IF NOT EXISTS succeeds without loading the module when the tables already exist
            // (e.g. a DB written by the bundled SQLite, opened by framework SQLite). Touch both tables so a
            // missing module is reported here instead of throwing on first use in VelumRepository.<init>.
            probe(driver, "recording_fts")
            probe(driver, "template_fts")
            EnsureResult(available = true, templateSchemaRecreated = needsTagsUpgrade)
        } catch (e: Exception) {
            // Robolectric / framework SQLite without FTS5: "no such module: fts5"
            EnsureResult(available = false, templateSchemaRecreated = false, failure = e::class.simpleName ?: "Exception")
        }
    }

    private fun probe(driver: SqlDriver, table: String) {
        driver.executeQuery(
            identifier = null,
            sql = "SELECT 1 FROM $table LIMIT 0",
            mapper = { QueryResult.Value(Unit) },
            parameters = 0,
        )
    }

    /**
     * True when template_fts is missing or was created before the tags column existed.
     * Fresh installs (no table) return true so the caller rebuilds after seed.
     */
    private fun templateFtsNeedsTagsColumn(driver: SqlDriver): Boolean {
        val sql = driver.executeQuery(
            identifier = null,
            sql = "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'template_fts'",
            mapper = { cursor ->
                val value = if (cursor.next().value) cursor.getString(0) else null
                QueryResult.Value(value)
            },
            parameters = 0,
        ).value
        if (sql.isNullOrBlank()) return true
        // FTS5 CREATE sql embeds column names; absence of tags means pre-tags schema.
        return !sql.contains("tags", ignoreCase = true)
    }

    data class Emptiness(
        val recordingsEmpty: Boolean,
        val templatesEmpty: Boolean,
    )

    fun emptiness(driver: SqlDriver): Emptiness {
        fun count(table: String): Long =
            driver.executeQuery(
                identifier = null,
                sql = "SELECT COUNT(*) FROM $table",
                mapper = { cursor ->
                    cursor.next()
                    QueryResult.Value(cursor.getLong(0) ?: 0L)
                },
                parameters = 0,
            ).value
        return try {
            Emptiness(
                recordingsEmpty = count("recording_fts") == 0L,
                templatesEmpty = count("template_fts") == 0L,
            )
        } catch (_: Exception) {
            Emptiness(recordingsEmpty = true, templatesEmpty = true)
        }
    }

    /**
     * True when at least one FTS table has zero rows.
     * Prefer [emptiness] + selective rebuild — an empty library alone must not
     * force rewriting every template on cold start.
     */
    fun isEmpty(driver: SqlDriver): Boolean {
        val e = emptiness(driver)
        return e.recordingsEmpty || e.templatesEmpty
    }

    /** Remove every FTS row (call before a full rebuild to drop orphans). */
    fun clearAll(driver: SqlDriver) {
        clearRecordings(driver)
        clearTemplates(driver)
    }

    fun clearRecordings(driver: SqlDriver) {
        driver.execute(identifier = null, sql = "DELETE FROM recording_fts", parameters = 0)
    }

    fun clearTemplates(driver: SqlDriver) {
        driver.execute(identifier = null, sql = "DELETE FROM template_fts", parameters = 0)
    }

    fun upsertRecording(
        driver: SqlDriver,
        id: String,
        title: String,
        transcript: String?,
        summaryMarkdown: String?,
    ) {
        inTransaction(driver) {
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
        tags: String = "",
        /**
         * False only when the table was just cleared (bulk rebuild). `template_id` is UNINDEXED, so each
         * DELETE scans the whole table: per-row deletes make a 16k-row rebuild quadratic (#100).
         */
        replaceExisting: Boolean = true,
    ) {
        inTransaction(driver) {
            if (replaceExisting) {
                driver.execute(
                    identifier = null,
                    sql = "DELETE FROM template_fts WHERE template_id = ?",
                    parameters = 1,
                ) { bindString(0, id) }
            }
            driver.execute(
                identifier = null,
                sql = "INSERT INTO template_fts(template_id, name, description, category, system_prompt, tags) VALUES (?, ?, ?, ?, ?, ?)",
                parameters = 6,
            ) {
                bindString(0, id)
                bindString(1, name)
                bindString(2, description)
                bindString(3, category)
                bindString(4, systemPrompt)
                bindString(5, tags)
            }
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

    /**
     * Wrap delete+insert so a crash mid-upsert cannot leave a missing FTS row.
     * Skips BEGIN/COMMIT when already inside a SQLDelight Transacter transaction.
     */
    private inline fun inTransaction(driver: SqlDriver, block: () -> Unit) {
        if (driver.currentTransaction() != null) {
            block()
            return
        }
        driver.execute(identifier = null, sql = "BEGIN", parameters = 0)
        try {
            block()
            driver.execute(identifier = null, sql = "COMMIT", parameters = 0)
        } catch (t: Throwable) {
            runCatching {
                driver.execute(identifier = null, sql = "ROLLBACK", parameters = 0)
            }
            throw t
        }
    }
}
