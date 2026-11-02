package dev.velum.core.data

import android.content.Context
import android.os.Build
import androidx.sqlite.db.SupportSQLiteOpenHelper
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.velum.core.db.VelumDatabase
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory

actual class DriverFactory(
    private val context: Context,
    private val name: String = "velum.db",
) {
    actual fun createDriver(): SqlDriver {
        val bundled = BundledSqlite.factoryOrNull()
        return if (bundled != null) {
            AndroidSqliteDriver(VelumDatabase.Schema, context.applicationContext, name, factory = bundled)
        } else {
            AndroidSqliteDriver(VelumDatabase.Schema, context.applicationContext, name)
        }
    }
}

/**
 * Android's framework SQLite is built without FTS5, so search would silently fall back to a full
 * list scan. Use the bundled SQLite (with FTS5) when its native library loads; otherwise (and under
 * Robolectric, which cannot load it) keep the framework helper. Same on-disk format either way.
 */
internal object BundledSqlite {
    private const val LIBRARY = "sqlite3x"

    fun factoryOrNull(
        isRobolectric: Boolean = Build.FINGERPRINT == "robolectric",
        load: () -> Unit = { System.loadLibrary(LIBRARY) },
    ): SupportSQLiteOpenHelper.Factory? {
        if (isRobolectric) return null
        return try {
            load()
            RequerySQLiteOpenHelperFactory()
        } catch (_: UnsatisfiedLinkError) {
            null
        }
    }
}
