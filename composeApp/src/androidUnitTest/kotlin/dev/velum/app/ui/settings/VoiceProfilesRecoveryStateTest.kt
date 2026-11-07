package dev.velum.app.ui.settings

import dev.velum.ai.local.ProfileCipherUnavailableException
import dev.velum.ai.local.UnreadableVoiceProfileException
import dev.velum.ai.local.VoiceProfileKeyUnavailableException
import dev.velum.core.security.SecureStoreUnavailableException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import java.security.ProviderException

@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34])
class VoiceProfilesRecoveryStateTest {
    @Test fun only_a_lost_key_offers_the_wipe() {
        assertTrue(isKeyLostFailure(VoiceProfileKeyUnavailableException()))
        assertFalse(isKeyLostFailure(ProfileCipherUnavailableException(ProviderException("synthetic"))))
        assertFalse(isKeyLostFailure(SecureStoreUnavailableException(RuntimeException("synthetic"))))
        assertFalse(isKeyLostFailure(UnreadableVoiceProfileException()))
        assertFalse(isKeyLostFailure(IllegalStateException("synthetic")))
    }

    @Test fun the_wipe_dialog_needs_data_to_wipe_or_a_lost_key() {
        assertTrue(wipeDialogVisible(requested = true, loaded = true, keyLost = false))
        assertTrue(wipeDialogVisible(requested = true, loaded = false, keyLost = true))
        // A retry that failed differently, or a screen still loading, must not leave a confirm that wipes.
        assertFalse(wipeDialogVisible(requested = true, loaded = false, keyLost = false))
        assertFalse(wipeDialogVisible(requested = false, loaded = true, keyLost = true))
        assertEquals(false, wipeDialogVisible(false, false, false))
    }
}
