package dev.velum.ai.local

import kotlin.io.encoding.Base64
import kotlin.test.*
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

class IosZipArchiveTest {
    private fun root() = ModelStoreDir(NSTemporaryDirectory() + NSUUID().UUIDString)

    @Test fun stored_deflated_and_empty_archives_extract() {
        for (fixture in listOf("stored", "deflated", "empty")) {
            val root = root()
            try {
                val archive = root.resolve("model.ZIP")
                archive.writeBytes(Base64.decode(fixtures.getValue(fixture)))
                val destination = root.resolve("out")
                assertTrue(ModelArchiveExtractor.isArchive(archive.name))
                ModelArchiveExtractor.extract(archive, destination)
                assertTrue(destination.isDirectory())
                when (fixture) {
                    "stored" -> {
                        assertContentEquals("weights".encodeToByteArray(), destination.resolve("nested/model.bin").readBytes())
                        assertContentEquals("tokens".encodeToByteArray(), destination.resolve("tokens.txt").readBytes())
                    }
                    "deflated" -> assertContentEquals("weights".repeat(1000).encodeToByteArray(), destination.resolve("nested/model.bin").readBytes())
                    "empty" -> assertTrue(destination.listChildren().isEmpty())
                }
                assertEquals(setOf("model.ZIP", "out"), root.listChildren().map { it.name }.toSet())
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun unsafe_or_corrupt_archives_leave_no_extracted_files() {
        for ((name, encoded) in fixtures.filterKeys { it !in setOf("stored", "deflated", "empty") }) {
            val root = root()
            try {
                val bytes = Base64.decode(encoded)
                val archive = root.resolve("model.zip")
                archive.writeBytes(bytes)
                val destination = root.resolve("out")
                val failure = assertFailsWith<IllegalArgumentException>(name) { ModelArchiveExtractor.extract(archive, destination) }
                if (name.startsWith("fifo")) {
                    assertEquals("ZIP special file metadata is forbidden", failure.message, name)
                }
                assertFalse(destination.exists(), name)
                assertEquals(listOf("model.zip"), root.listChildren().map { it.name }, name)
                assertContentEquals(bytes, archive.readBytes(), name)
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun existing_destination_is_preserved() {
        val root = root()
        try {
            val archive = root.resolve("model.zip")
            archive.writeBytes(Base64.decode(fixtures.getValue("stored")))
            val destination = root.resolve("out")
            destination.resolve("keep").writeBytes("original".encodeToByteArray())
            assertFailsWith<IllegalArgumentException> { ModelArchiveExtractor.extract(archive, destination) }
            assertContentEquals("original".encodeToByteArray(), destination.resolve("keep").readBytes())
        } finally { root.deleteRecursively() }
    }

    // Python stdlib zipfile fixtures. Malformed names and CRC payloads are byte-patched after writing.
    private val fixtures = mapOf(
        "emptyFile" to "",
        "stored" to "UEsDBBQAAAAAAAAAIQAAAAAAAAAAAAAAAAAHAAAAbmVzdGVkL1BLAwQUAAAAAAAAACEAWHPSGgcAAAAHAAAAEAAAAG5lc3RlZC9tb2RlbC5iaW53ZWlnaHRzUEsDBBQAAAAAAAAAIQCOEVqqBgAAAAYAAAAKAAAAdG9rZW5zLnR4dHRva2Vuc1BLAQIUAxQAAAAAAAAAIQAAAAAAAAAAAAAAAAAHAAAAAAAAAAAAAADtQQAAAABuZXN0ZWQvUEsBAhQDFAAAAAAAAAAhAFhz0hoHAAAABwAAABAAAAAAAAAAAAAAAKSBJQAAAG5lc3RlZC9tb2RlbC5iaW5QSwECFAMUAAAAAAAAACEAjhFaqgYAAAAGAAAACgAAAAAAAAAAAAAApIFaAAAAdG9rZW5zLnR4dFBLBQYAAAAAAwADAKsAAACIAAAAAAA=",
        "deflated" to "UEsDBBQAAAAIAAAAIQBVfcyEJAAAAFgbAAAQAAAAbmVzdGVkL21vZGVsLmJpbu3FMQ0AMAgAMLUEdo8E+yjAQft04mX1H0mSJEmSJEmSJEl3C1BLAQIUAxQAAAAIAAAAIQBVfcyEJAAAAFgbAAAQAAAAAAAAAAAAAACkgQAAAABuZXN0ZWQvbW9kZWwuYmluUEsFBgAAAAABAAEAPgAAAFIAAAAAAA==",
        "empty" to "UEsFBgAAAAAAAAAAAAAAAAAAAAAAAA==",
        "traversal" to "UEsDBBQAAAAAAAAAIQD7OSuCAwAAAAMAAAAJAAAALi4vZXNjYXBlYmFkUEsBAhQDFAAAAAAAAAAhAPs5K4IDAAAAAwAAAAkAAAAAAAAAAAAAAKSBAAAAAC4uL2VzY2FwZVBLBQYAAAAAAQABADcAAAAqAAAAAAA=",
        "absolute" to "UEsDBBQAAAAAAAAAIQD7OSuCAwAAAAMAAAAHAAAAL2VzY2FwZWJhZFBLAQIUAxQAAAAAAAAAIQD7OSuCAwAAAAMAAAAHAAAAAAAAAAAAAACkgQAAAAAvZXNjYXBlUEsFBgAAAAABAAEANQAAACgAAAAAAA==",
        "backslash" to "UEsDBBQAAAAAAAAAIQD7OSuCAwAAAAMAAAANAAAAbmVzdGVkXGVzY2FwZWJhZFBLAQIUAxQAAAAAAAAAIQD7OSuCAwAAAAMAAAANAAAAAAAAAAAAAACkgQAAAABuZXN0ZWRcZXNjYXBlUEsFBgAAAAABAAEAOwAAAC4AAAAAAA==",
        "drive" to "UEsDBBQAAAAAAAAAIQD7OSuCAwAAAAMAAAAJAAAAQzovZXNjYXBlYmFkUEsBAhQDFAAAAAAAAAAhAPs5K4IDAAAAAwAAAAkAAAAAAAAAAAAAAKSBAAAAAEM6L2VzY2FwZVBLBQYAAAAAAQABADcAAAAqAAAAAAA=",
        "nul" to "UEsDBBQAAAAAAAAAIQD7OSuCAwAAAAMAAAAIAAAAYmFkAG5hbWViYWRQSwECFAMUAAAAAAAAACEA+zkrggMAAAADAAAACAAAAAAAAAAAAAAApIEAAAAAYmFkAG5hbWVQSwUGAAAAAAEAAQA2AAAAKQAAAAAA",
        "duplicate" to "UEsDBBQAAAAAAAAAIQBX7nGSBQAAAAUAAAAJAAAAbW9kZWwuYmluZmlyc3RQSwMEFAAAAAAAAAAhAGkRH7YGAAAABgAAAAkAAABtb2RlbC5iaW5zZWNvbmRQSwECFAMUAAAAAAAAACEAV+5xkgUAAAAFAAAACQAAAAAAAAAAAAAApIEAAAAAbW9kZWwuYmluUEsBAhQDFAAAAAAAAAAhAGkRH7YGAAAABgAAAAkAAAAAAAAAAAAAAKSBLAAAAG1vZGVsLmJpblBLBQYAAAAAAgACAG4AAABZAAAAAAA=",
        "caseCollision" to "UEsDBBQAAAAAAAAAIQBX7nGSBQAAAAUAAAAJAAAAbW9kZWwuYmluZmlyc3RQSwMEFAAAAAAAAAAhAGkRH7YGAAAABgAAAAkAAABNT0RFTC5iaW5zZWNvbmRQSwECFAMUAAAAAAAAACEAV+5xkgUAAAAFAAAACQAAAAAAAAAAAAAApIEAAAAAbW9kZWwuYmluUEsBAhQDFAAAAAAAAAAhAGkRH7YGAAAABgAAAAkAAAAAAAAAAAAAAKSBLAAAAE1PREVMLmJpblBLBQYAAAAAAgACAG4AAABZAAAAAAA=",
        "aliasCollision" to "UEsDBBQAAAAAAAAAIQBX7nGSBQAAAAUAAAAJAAAAbW9kZWwuYmluZmlyc3RQSwMEFAAAAAAAAAAhAGkRH7YGAAAABgAAAAsAAAAuL21vZGVsLmJpbnNlY29uZFBLAQIUAxQAAAAAAAAAIQBX7nGSBQAAAAUAAAAJAAAAAAAAAAAAAACkgQAAAABtb2RlbC5iaW5QSwECFAMUAAAAAAAAACEAaREftgYAAAAGAAAACwAAAAAAAAAAAAAApIEsAAAALi9tb2RlbC5iaW5QSwUGAAAAAAIAAgBwAAAAWwAAAAAA",
        "fileParentCollision" to "UEsDBBQAAAAAAAAAIQAQNp+MBAAAAAQAAAAGAAAAbmVzdGVkZmlsZVBLAwQUAAAAAAAAACEA+zkrggMAAAADAAAAEAAAAG5lc3RlZC9tb2RlbC5iaW5iYWRQSwECFAMUAAAAAAAAACEAEDafjAQAAAAEAAAABgAAAAAAAAAAAAAApIEAAAAAbmVzdGVkUEsBAhQDFAAAAAAAAAAhAPs5K4IDAAAAAwAAABAAAAAAAAAAAAAAAKSBKAAAAG5lc3RlZC9tb2RlbC5iaW5QSwUGAAAAAAIAAgByAAAAWQAAAAAA",
        "directoryCollision" to "UEsDBBQAAAAAAAAAIQBX7nGSBQAAAAUAAAAQAAAAbmVzdGVkL21vZGVsLmJpbmZpcnN0UEsDBBQAAAAAAAAAIQD7OSuCAwAAAAMAAAAGAAAAbmVzdGVkYmFkUEsBAhQDFAAAAAAAAAAhAFfucZIFAAAABQAAABAAAAAAAAAAAAAAAKSBAAAAAG5lc3RlZC9tb2RlbC5iaW5QSwECFAMUAAAAAAAAACEA+zkrggMAAAADAAAABgAAAAAAAAAAAAAApIEzAAAAbmVzdGVkUEsFBgAAAAACAAIAcgAAAFoAAAAAAA==",
        "symlink" to "UEsDBBQAAAAAAAAAIQCjZRntCQAAAAkAAAAEAAAAbGluay4uL2VzY2FwZVBLAQIUAxQAAAAAAAAAIQCjZRntCQAAAAkAAAAEAAAAAAAAAAAAAAD/oQAAAABsaW5rUEsFBgAAAAABAAEAMgAAACsAAAAAAA==",
        "fifoDirectory" to "UEsDBBQAAAAAAAAAIQAAAAAAAAAAAAAAAAAFAAAAcGlwZS9QSwECFAMUAAAAAAAAACEAAAAAAAAAAAAAAAAABQAAAAAAAAAAAAAApBEAAAAAcGlwZS9QSwUGAAAAAAEAAQAzAAAAIwAAAAAA",
        "fifoExtra" to "UEsDBBQAAAAAAAAAIQAAAAAAAAAAAAAAAAAEAAsAcGlwZXhsBwAFFAMAAKQRUEsBAhQDFAAAAAAAAAAhAAAAAAAAAAAAAAAAAAQACwAAAAAAAAAAAKSBAAAAAHBpcGV4bAcABRQDAACkEVBLBQYAAAAAAQABAD0AAAAtAAAAAAA=",
        "fifo" to "UEsDBBQAAAAAAAAAIQAAAAAAAAAAAAAAAAAEAAAAcGlwZVBLAQIUAxQAAAAAAAAAIQAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAACkEQAAAABwaXBlUEsFBgAAAAABAAEAMgAAACIAAAAAAA==",
        "directoryData" to "UEsDBBQAAAAAAAAAIQAVaixCBwAAAAcAAAAEAAAAYmFkL3BheWxvYWRQSwECFAMUAAAAAAAAACEAFWosQgcAAAAHAAAABAAAAAAAAAAAAAAA7UEAAAAAYmFkL1BLBQYAAAAAAQABADIAAAApAAAAAAA=",
        "crc" to "UEsDBBQAAAAAAAAAIQAAAAAAAAAAAAAAAAAHAAAAbmVzdGVkL1BLAwQUAAAAAAAAACEAWHPSGgcAAAAHAAAAEAAAAG5lc3RlZC9tb2RlbC5iaW5XRUlHSFRTUEsDBBQAAAAAAAAAIQCOEVqqBgAAAAYAAAAKAAAAdG9rZW5zLnR4dHRva2Vuc1BLAQIUAxQAAAAAAAAAIQAAAAAAAAAAAAAAAAAHAAAAAAAAAAAAAADtQQAAAABuZXN0ZWQvUEsBAhQDFAAAAAAAAAAhAFhz0hoHAAAABwAAABAAAAAAAAAAAAAAAKSBJQAAAG5lc3RlZC9tb2RlbC5iaW5QSwECFAMUAAAAAAAAACEAjhFaqgYAAAAGAAAACgAAAAAAAAAAAAAApIFaAAAAdG9rZW5zLnR4dFBLBQYAAAAAAwADAKsAAACIAAAAAAA=",
        "truncated" to "UEsDBBQAAAAIAAAAIQBVfcyEJAAAAFgbAAAQAAAAbmVzdGVkL21vZGVsLmJpbu3FMQ0AMAgAMLUEdo8E+yjAQft04mX1H0mSJEmSJEmSJEl3C1BLAQIUAxQAAAAIAAAAIQBVfcyEJAAAAFgbAAAQAAAAAAAAAAAAAACkgQAAAABuZXN0ZWQvbW9kZWwuYmluUEsFBgAAAAABAAEA",
        "unicodeTraversal" to "UEsDBBQAAAAAAAAAIQD7OSuCAwAAAAMAAAAEABIAc2FmZXVwDgABjyikHy4uL2VzY2FwZWJhZFBLAQIUAxQAAAAAAAAAIQD7OSuCAwAAAAMAAAAEABIAAAAAAAAAAACkgQAAAABzYWZldXAOAAGPKKQfLi4vZXNjYXBlUEsFBgAAAAABAAEARAAAADcAAAAAAA==",
    )
}
