package dev.velum.app.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.velum.ai.audio.createOpusDecoder
import dev.velum.ai.local.ModelStoreDir
import dev.velum.app.platform.AudioNormalizer
import dev.velum.ble.crypto.JcaSessionCipher
import dev.velum.ble.crypto.createFileDecryptor
import dev.velum.ble.crypto.rsaKeyPairToPem
import dev.velum.protocol.Bytes
import dev.velum.protocol.ChaCha20
import dev.velum.protocol.PlaudFile
import dev.velum.protocol.PlaudFileCrypto
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #95 on a device: synthetic PLAUD.AI file -> decrypt -> the real Android Ogg Opus decoder (libopus through
 * kopus, native) -> 16 kHz mono PCM, and the platform MediaCodec normalizer on the decrypted Ogg. Fixture and
 * keys are synthetic. Says nothing about a real Note Pro (#7).
 *
 * Run (no device-walk mode, no UI):
 *   adb shell am instrument -w -e class dev.velum.app.audio.OggOpusDecodeDeviceTest -e velumDeviceWalk false \
 *     dev.velum.app.test/dev.velum.app.devicewalk.DeviceWalkTestRunner
 */
@RunWith(AndroidJUnit4::class)
class OggOpusDecodeDeviceTest {
    private val key = ByteArray(32) { (it + 7).toByte() }
    private val nonce = ByteArray(12) { (it + 1).toByte() }
    private val pems = rsaKeyPairToPem()
    private lateinit var dir: File

    @Before fun setUp() {
        dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "ogg-device-test").apply {
            deleteRecursively(); mkdirs()
        }
    }

    @After fun tearDown() { dir.deleteRecursively() }

    /** A PLAUD.AI version 1 file built with [ChaCha20] directly, not the code under test. */
    private fun plaudFile(plain: ByteArray): ByteArray {
        val file = ByteArray(PlaudFile.HEADER_SIZE + plain.size)
        PlaudFile.MAGIC.copyInto(file, 0)
        Bytes.u16Le(1).copyInto(file, PlaudFile.OFF_VERSION)
        Bytes.u16Le(PlaudFile.HEADER_SIZE).copyInto(file, PlaudFile.OFF_HEADER_SIZE)
        Bytes.u16Le(1).copyInto(file, PlaudFile.OFF_FILE_TYPE)
        Bytes.u16Le(1).copyInto(file, PlaudFile.OFF_CHANNEL)
        Bytes.u16Le(1).copyInto(file, PlaudFile.OFF_ENCRYPT_TYPE)
        Bytes.u32Le(1).copyInto(file, PlaudFile.OFF_DURATION)
        Bytes.u32Le(1).copyInto(file, PlaudFile.OFF_CHACHA_COUNTER)
        nonce.copyInto(file, PlaudFile.OFF_CHACHA_NONCE)
        Bytes.u32Le(0).copyInto(file, PlaudFile.OFF_SEGMENT_SIZE)
        JcaSessionCipher().rsaEncryptPkcs1(pems.first, key).copyInto(file, PlaudFile.OFF_KEY_CIPHER)
        ChaCha20.xor(key, nonce, 1L, plain).copyInto(file, PlaudFile.HEADER_SIZE)
        return file
    }

    private fun decrypt(file: ByteArray, privatePem: String): ByteArray {
        val out = ByteArrayOutputStream(file.size)
        PlaudFileCrypto.decryptStream(
            file.size.toLong(),
            { position, length -> file.copyOfRange(position.toInt(), position.toInt() + length) },
            privatePem,
            createFileDecryptor(),
        ) { out.write(it) }
        return out.toByteArray()
    }

    private fun samples(pcm: File): ShortArray {
        val bytes = pcm.readBytes()
        return ShortArray(bytes.size / 2).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
    }

    @Test fun decrypted_plaud_file_decodes_to_16k_mono_with_duration_and_tone() {
        val ogg = decrypt(plaudFile(SyntheticOggFixture.bytes), pems.second)
        assertTrue("decrypt gives back the exact Ogg", ogg.contentEquals(SyntheticOggFixture.bytes))
        val src = File(dir, "in.ogg").apply { writeBytes(ogg) }
        val pcmFile = File(dir, "out.pcm")

        val meta = createOpusDecoder().decodePathToPcmFile(src.path, ModelStoreDir(pcmFile.path))

        assertEquals(16_000, meta.sampleRateHz)
        assertEquals(1, meta.channels)
        assertFalse(meta.truncated)
        val expectedSamples = SyntheticOggFixture.PACKETS * 320 - SyntheticOggFixture.PRE_SKIP_48K / 3
        assertEquals("pre-skip dropped, 16 kHz mono PCM16", expectedSamples * 2L, meta.sizeBytes)
        assertEquals(meta.sizeBytes, pcmFile.length())
        val pcm = samples(pcmFile)
        val durationMs = pcm.size * 1000L / 16_000
        assertTrue("duration $durationMs ms", abs(durationMs - 993) <= 20)
        // Skip codec settling, then count rising zero crossings: wrong rate/channels would move the pitch.
        val steady = pcm.copyOfRange(1_600, pcm.size)
        assertTrue("tone survives the downmix", steady.maxOf { abs(it.toInt()) } > 1_000)
        var crossings = 0
        for (i in 1 until steady.size) if (steady[i - 1] < 0 && steady[i] >= 0) crossings++
        val hz = crossings * 16_000.0 / steady.size
        assertTrue("pitch $hz Hz", abs(hz - SyntheticOggFixture.TONE_HZ) < 10.0)
    }

    @Test fun wrong_key_never_reaches_successful_decode_and_leaks_no_key() {
        val file = plaudFile(SyntheticOggFixture.bytes)
        val other = rsaKeyPairToPem()
        val outcome = runCatching {
            val garbage = decrypt(file, other.second)
            val src = File(dir, "bad.ogg").apply { writeBytes(garbage) }
            createOpusDecoder().decodePathToPcmFile(src.path, ModelStoreDir(File(dir, "bad.pcm").path))
        }
        assertTrue("wrong key must not decode", outcome.isFailure)
        val text = outcome.exceptionOrNull().toString()
        assertFalse("no key material in the error", text.contains("PRIVATE KEY") || text.contains(other.second.lines()[1]))
    }

    @Test fun platform_normalizer_turns_the_decrypted_ogg_into_16k_mono_wav() {
        val ogg = File(dir, "norm.ogg").apply { writeBytes(decrypt(plaudFile(SyntheticOggFixture.bytes), pems.second)) }
        val result = runBlocking { AudioNormalizer().normalizeToWav(ogg.path, 16_000) }
        assertEquals(16_000, result.sampleRateHz)
        assertTrue("duration ${result.durationMs} ms", abs(result.durationMs - 1_000) <= 60)
        val wav = File(result.wavPath).readBytes()
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("channels", 1, header.getShort(22).toInt())
        assertEquals("sample rate", 16_000, header.getInt(24))
    }
}
