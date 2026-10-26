package dev.velum.core.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.velum.core.db.VelumDatabase

actual class DriverFactory(private val path: String = JdbcSqliteDriver.IN_MEMORY) {
    actual fun createDriver(): SqlDriver {
        val driver = JdbcSqliteDriver(path)
        VelumDatabase.Schema.create(driver)
        return driver
    }
}
