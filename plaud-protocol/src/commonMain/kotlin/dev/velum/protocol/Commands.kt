package dev.velum.protocol

/**
 * Known Plaud BLE command opcodes (little-endian u16 after the 0x01 prefix).
 *
 * Many opcodes remain undocumented; only read-oriented commands used by the
 * community sync path are listed here.
 */
object Commands {
    const val HANDSHAKE: Int = 1
    const val GET_SSN: Int = 2
    const val BATTERY_LIKELY: Int = 9
    const val GET_SESSIONS: Int = 26
    const val SYNC_FILE_START: Int = 28
    const val SYNC_FILE_STOP: Int = 29
    const val DELETE_FILE: Int = 30
    const val DELETE_FILE_RSP: Int = 31
    const val FEATURE_REQUEST: Int = 138
    const val UNKNOWN_UNSOLICITED: Int = 141

    /** Sync-in-idle Wi-Fi config (APK): get/set SSID+password, list, test, result, delete. */
    const val WIFI_GET_SSID: Int = 120
    const val WIFI_SET_SSID: Int = 121
    const val WIFI_LIST: Int = 122
    const val WIFI_TEST: Int = 123
    const val WIFI_TEST_RESULT: Int = 124
    const val WIFI_DELETE: Int = 125
    const val WIFI_DISCONNECT: Int = 116
    const val WIFI_DISCONNECT_RSP: Int = 117

    // Protocol-20 unlock (sent without the usual 0x01 app→device prefix).
    const val UNLOCK_SN_SIGNATURE: Int = 0xFE10
    const val UNLOCK_NEED_PUBLIC_KEY: Int = 0xFE11
    const val UNLOCK_SESSION_SECRET: Int = 0xFE12
}

enum class HandshakeStatus(val code: Int) {
    Ok(0),
    TokenMismatch(1),
    RecordingInProgress(2),
    RejectedByUser(3),
    SsnCheckFailed(4),
    NotInPairingMode(255),
    ;

    companion object {
        fun fromCode(code: Int): HandshakeStatus? = entries.find { it.code == code }
    }
}
