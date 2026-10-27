package dev.velum.app.importing

import dev.velum.app.platform.ImportAudioFormats
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportAudioFormatsTest {

    @Test
    fun acceptsRequiredExtensions() {
        listOf(
            "a.mp3", "b.m4a", "c.aac", "d.wav", "e.ogg", "f.opus",
            "g.flac", "h.amr", "i.3gp", "j.webm", "k.mp4",
        ).forEach { name ->
            assertTrue(ImportAudioFormats.isSupportedFileName(name), name)
        }
    }

    @Test
    fun rejectsNonAudio() {
        assertFalse(ImportAudioFormats.isSupportedFileName("notes.txt"))
        assertFalse(ImportAudioFormats.isSupportedFileName("photo.jpg"))
    }
}
