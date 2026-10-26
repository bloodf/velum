package dev.velum.ble.crypto

import dev.velum.protocol.FileDecryptor
import dev.velum.protocol.SessionCipher

/** Platform SessionCipher (JCA on JVM/Android). */
expect fun createSessionCipher(): SessionCipher

/** Platform FileDecryptor (JCA on JVM/Android). */
expect fun createFileDecryptor(): FileDecryptor
