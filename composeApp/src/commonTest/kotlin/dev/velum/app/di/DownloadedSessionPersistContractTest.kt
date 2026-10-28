package dev.velum.app.di

import dev.velum.ai.audio.DownloadedSessionAudioPersist
import dev.velum.ai.audio.PcmWav
import dev.velum.app.ui.DownloadedSessionAudio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract test for the AppContainer.persistDownloadedSession prepare step: every
 * DownloadedSessionAudio body shape must materialize as ogg|wav (never bin) using the same
 * helper AppContainer calls.
 */
class DownloadedSessionPersistContractTest {
    @Test
    fun appContainerPrepare_matchesHelperForAllSessionBodies() {
        val sessions = listOf(
            session(byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte())),
            session(PcmWav.encode(ByteArray(8), sampleRateHz = 16_000)),
            session(ByteArray(100) { it.toByte() }), // historic `.bin` raw PCM
        )
        for (session in sessions) {
            val prepared = DownloadedSessionAudioPersist.prepare(
                decryptedBytes = session.decryptedBytes,
                sampleRateHz = session.sampleRateHz,
                channels = session.channels,
            )
            assertTrue(prepared.extension == "ogg" || prepared.extension == "wav")
            assertEquals(
                if (PcmWav.looksLikeOgg(session.decryptedBytes)) "ogg" else "wav",
                prepared.extension,
            )
        }
    }

    private fun session(bytes: ByteArray) = DownloadedSessionAudio(
        sessionId = 1L,
        deviceSerial = "test",
        startedAtEpochSeconds = 1_700_000_000L,
        sizeBytes = bytes.size.toLong(),
        decryptedBytes = bytes,
        sampleRateHz = DownloadedSessionAudioPersist.DEFAULT_SAMPLE_RATE_HZ,
        channels = DownloadedSessionAudioPersist.DEFAULT_CHANNELS,
    )
}
