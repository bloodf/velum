package dev.velum.core.data

import app.cash.sqldelight.db.SqlDriver
import dev.velum.core.db.VelumDatabase

expect class DriverFactory {
    fun createDriver(): SqlDriver
}

fun createDatabase(driverFactory: DriverFactory): VelumDatabase =
    VelumDatabase(driverFactory.createDriver())
