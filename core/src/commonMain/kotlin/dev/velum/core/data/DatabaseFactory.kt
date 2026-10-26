package dev.velum.core.data

import app.cash.sqldelight.db.SqlDriver
import dev.velum.core.db.VelumDatabase

expect class DriverFactory {
    fun createDriver(): SqlDriver
}

data class DatabaseHandle(
    val database: VelumDatabase,
    val driver: SqlDriver,
)

fun createDatabase(driverFactory: DriverFactory): DatabaseHandle {
    val driver = driverFactory.createDriver()
    return DatabaseHandle(VelumDatabase(driver), driver)
}
