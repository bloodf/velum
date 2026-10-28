package dev.velum.ble.client

actual fun createSessionPartialFileFactory(directoryPath: String): SessionPartialFileFactory =
    object : SessionPartialFileFactory {
        override fun allocatePath(sessionId: Long): String =
            error("Session partial files are not supported on this target")

        override fun open(path: String): SessionPartialFile =
            error("Session partial files are not supported on this target")
    }
