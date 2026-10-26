package dev.velum.ble.crypto

import dev.velum.protocol.FileDecryptor
import dev.velum.protocol.SessionCipher

actual fun createSessionCipher(): SessionCipher =
    error("SessionCipher on iOS lands with CryptoKit in M3")

actual fun createFileDecryptor(): FileDecryptor =
    error("FileDecryptor on iOS lands with CryptoKit in M3")
