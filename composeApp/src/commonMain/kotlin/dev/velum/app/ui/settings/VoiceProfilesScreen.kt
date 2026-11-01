package dev.velum.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.velum.ai.local.ProfileCipherUnavailableException
import dev.velum.ai.local.UnreadableVoiceProfile
import dev.velum.ai.local.UnreadableVoiceProfileException
import dev.velum.ai.local.VoiceProfileKeyUnavailableException
import dev.velum.ai.local.VoiceProfileReadProblem
import dev.velum.core.security.SecureStoreUnavailableException
import dev.velum.ai.local.VoiceProfile
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.i18n.Strings
import dev.velum.app.ui.components.BlackButton
import dev.velum.app.ui.components.OutlineButton
import dev.velum.app.ui.components.ScreenTopBar
import dev.velum.app.ui.theme.VelumBg
import dev.velum.app.ui.theme.VelumInk
import dev.velum.app.ui.theme.VelumInkSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * On-device voice profiles: list / rename / edit meta / delete / export / import / wipe.
 * Embeddings stay encrypted; never leave the device unless the user exports.
 */
@Composable
fun VoiceProfilesScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var profiles by remember(container) { mutableStateOf(emptyList<VoiceProfile>()) }
    var loaded by remember(container) { mutableStateOf(false) }
    var unreadable by remember(container) { mutableStateOf(emptyList<UnreadableVoiceProfile>()) }
    var pendingRemoval by remember(container) { mutableStateOf<UnreadableVoiceProfile?>(null) }
    var selected by remember { mutableStateOf<VoiceProfile?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var exportText by remember { mutableStateOf<String?>(null) }
    var importDraft by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showWipeConfirm by remember { mutableStateOf(false) }

    suspend fun refresh() {
        try {
            val inventory = container.voiceProfileStore.withReady { inspectProfiles() }
            profiles = inventory.profiles
            unreadable = inventory.unreadable
            loaded = true
            selected = selected?.id?.let { id -> profiles.firstOrNull { it.id == id } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // A failed global refresh must not leave stale per-file diagnoses/removal actions.
            loaded = false
            profiles = emptyList()
            unreadable = emptyList()
            selected = null
            pendingRemoval = null
            throw failure
        }
    }

    fun showFailure(failure: Exception) {
        val global = failure is VoiceProfileKeyUnavailableException ||
            failure is ProfileCipherUnavailableException || failure is SecureStoreUnavailableException
        if (global) {
            loaded = false
            profiles = emptyList()
            unreadable = emptyList()
            selected = null
            pendingRemoval = null
        }
        status = when (failure) {
            is SecureStoreUnavailableException -> strings.ux.secureStorageUnavailable
            is VoiceProfileKeyUnavailableException, is ProfileCipherUnavailableException -> strings.ux.voiceProfilesKeyUnavailable
            is UnreadableVoiceProfileException -> strings.ux.voiceProfilesChangeBlocked
            else -> strings.ux.genericError
        }
    }

    fun launchAction(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                showFailure(failure)
            }
        }
    }

    LaunchedEffect(container) {
        try {
            refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            showFailure(failure)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(VelumBg)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenTopBar(strings.voiceProfiles, onBack = onBack)
        Column(Modifier.padding(20.dp)) {
            Text(
                strings.ux.voiceProfilesHint,
                color = VelumInkSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            Spacer(Modifier.height(16.dp))
            if (!loaded && status == null) {
                CircularProgressIndicator()
            } else if (!loaded) {
                Text(status.orEmpty(), color = VelumInkSecondary)
                Spacer(Modifier.height(8.dp))
                OutlineButton(strings.ux.retryProcessing, { launchAction { container.voiceProfileStore.reloadKey(); refresh(); status = null } })
            } else if (profiles.isEmpty() && unreadable.isEmpty()) {
                Text(strings.ux.voiceProfilesEmpty, color = VelumInkSecondary)
            } else {
                profiles.forEach { p ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { selected = p }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(p.displayName, fontWeight = FontWeight.SemiBold, color = VelumInk)
                        Text(
                            buildString {
                                append(
                                    strings.ux.voiceProfilesSamplesFormat.replace(
                                        "%d",
                                        p.sampleCount.toString(),
                                    ),
                                )
                                if (p.role.isNotBlank()) append(" · ${p.role}")
                                if (p.company.isNotBlank()) append(" · ${p.company}")
                                if (p.email.isNotBlank()) append(" · ${p.email}")
                            },
                            color = VelumInkSecondary,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
            if (loaded && unreadable.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(strings.ux.voiceProfilesUnreadableTitle, fontWeight = FontWeight.SemiBold, color = VelumInk)
                unreadable.forEach { item ->
                    Spacer(Modifier.height(12.dp))
                    Text(item.displayName, fontWeight = FontWeight.SemiBold, color = VelumInk)
                    Text(
                        when (item.problem) {
                            VoiceProfileReadProblem.MissingMetadata -> strings.ux.voiceProfilesMissingMetadata
                            VoiceProfileReadProblem.MissingEmbedding -> strings.ux.voiceProfilesMissingEmbedding
                            VoiceProfileReadProblem.InvalidMetadata -> strings.ux.voiceProfilesInvalidMetadata
                            VoiceProfileReadProblem.InvalidEmbedding -> strings.ux.voiceProfilesInvalidEmbedding
                            VoiceProfileReadProblem.CannotDecrypt -> strings.ux.voiceProfilesCannotDecrypt
                            VoiceProfileReadProblem.FileUnavailable -> strings.ux.voiceProfilesFileUnavailable
                        },
                        color = VelumInkSecondary,
                        fontSize = 13.sp,
                    )
                    if (item.canRemove) {
                        OutlineButton(strings.ux.voiceProfilesRemoveFiles, { pendingRemoval = item })
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlineButton(strings.ux.retryProcessing, { launchAction { container.voiceProfileStore.reloadKey(); refresh(); status = null } })
            }
            Spacer(Modifier.height(16.dp))
            selected?.let { p ->
                ProfileEditor(
                    profile = p,
                    onSave = { name, role, company, email, notes ->
                        launchAction {
                            container.voiceProfileStore.withReady {
                                rename(p.id, name)
                                updateMeta(
                                    p.id,
                                    role = role,
                                    company = company,
                                    email = email,
                                    notes = notes,
                                )
                            }
                            refresh()
                            status = strings.ux.voiceProfilesSaved
                        }
                    },
                    onDelete = { showDeleteConfirm = true },
                )
                Spacer(Modifier.height(12.dp))
            }
            OutlineButton(
                strings.ux.voiceProfilesExport,
                {
                    launchAction {
                        exportText = container.voiceProfileStore.withReady { exportEncrypted() }
                        status = strings.ux.voiceProfilesExportReady
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = loaded && unreadable.isEmpty(),
            )
            if (loaded && unreadable.isNotEmpty()) {
                Text(strings.ux.voiceProfilesExportBlocked, color = VelumInkSecondary, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            exportText?.let {
                Text(it.take(400) + if (it.length > 400) "…" else "", color = VelumInkSecondary, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
            }
            Text(strings.ux.voiceProfilesImportTitle, fontWeight = FontWeight.SemiBold, color = VelumInk)
            Spacer(Modifier.height(6.dp))
            BasicTextField(
                value = importDraft,
                onValueChange = { importDraft = it },
                textStyle = TextStyle(color = VelumInk, fontSize = 13.sp),
                modifier = Modifier
                    .semantics { contentDescription = strings.ux.voiceProfilesImportTitle }
                    .fillMaxWidth()
                    .height(100.dp)
                    .background(VelumBg)
                    .padding(8.dp),
            )
            Spacer(Modifier.height(8.dp))
            OutlineButton(
                strings.ux.voiceProfilesImport,
                {
                    val draft = importDraft
                    launchAction {
                        val n = container.voiceProfileStore.withReady { importEncrypted(draft) }
                        refresh()
                        status = strings.ux.voiceProfilesImportedFormat.replace("%d", n.toString())
                        importDraft = ""
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = loaded,
            )
            Spacer(Modifier.height(8.dp))
            OutlineButton(
                strings.ux.voiceProfilesWipe,
                { showWipeConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = loaded,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                strings.ux.voiceProfilesThreshold,
                fontWeight = FontWeight.SemiBold,
                color = VelumInk,
            )
            Row(Modifier.fillMaxWidth()) {
                thresholdOptions(strings).forEach { (t, label) ->
                    OutlineButton(
                        label,
                        {
                            container.settingsViewModel.runStorageAction {
                                container.settingsRepository.setSpeakerMatchThreshold(t)
                                status = strings.ux.voiceProfilesThresholdSet
                            }
                        },
                        modifier = Modifier.weight(1f).padding(horizontal = 2.dp),
                    )
                }
            }
            status?.takeIf { loaded }?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = VelumInkSecondary, fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                strings.ux.voiceProfilesEnrollHint,
                color = VelumInkSecondary,
                fontSize = 12.sp,
            )
        }
    }

    pendingRemoval?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(strings.ux.confirmDeleteTitle) },
            text = { Text(strings.ux.voiceProfilesRemoveFilesConfirm.replace("%s", item.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemoval = null
                    launchAction {
                        val removed = container.voiceProfileStore.removeUnreadableProfile(item)
                        refresh()
                        status = if (removed) strings.ux.voiceProfilesDeleted else strings.ux.voiceProfilesFilesChanged
                    }
                }) { Text(strings.delete) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) { Text(strings.cancel) }
            },
        )
    }

    if (showDeleteConfirm) {
        val p = selected
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(strings.ux.confirmDeleteTitle) },
            text = { Text(strings.ux.confirmDeleteProfileBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        if (p != null) {
                            launchAction {
                                container.voiceProfileStore.withReady { delete(p.id) }
                                selected = null
                                refresh()
                                status = strings.ux.voiceProfilesDeleted
                            }
                        }
                    },
                ) { Text(strings.delete) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(strings.cancel) }
            },
        )
    }
    if (showWipeConfirm) {
        AlertDialog(
            onDismissRequest = { showWipeConfirm = false },
            title = { Text(strings.ux.confirmDeleteTitle) },
            text = { Text(strings.ux.confirmWipeProfilesBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showWipeConfirm = false
                        launchAction {
                            container.voiceProfileStore.withReady { wipeAll() }
                            selected = null
                            refresh()
                            status = strings.ux.voiceProfilesWiped
                        }
                    },
                ) { Text(strings.confirm) }
            },
            dismissButton = {
                TextButton(onClick = { showWipeConfirm = false }) { Text(strings.cancel) }
            },
        )
    }
}

internal fun thresholdOptions(strings: Strings): List<Pair<Float, String>> = listOf(
    0.45f to strings.ux.voiceProfilesSensitivityFlexible,
    0.55f to strings.ux.voiceProfilesSensitivityBalanced,
    0.65f to strings.ux.voiceProfilesSensitivityStrict,
    0.75f to strings.ux.voiceProfilesSensitivityVeryStrict,
)

@Composable
private fun ProfileEditor(
    profile: VoiceProfile,
    onSave: (name: String, role: String, company: String, email: String, notes: String) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(profile.id) { mutableStateOf(profile.displayName) }
    var role by remember(profile.id) { mutableStateOf(profile.role) }
    var company by remember(profile.id) { mutableStateOf(profile.company) }
    var email by remember(profile.id) { mutableStateOf(profile.email) }
    var notes by remember(profile.id) { mutableStateOf(profile.notes) }
    Column {
        val strings = LocalStrings.current
        Field(strings.name, name) { name = it }
        Field(strings.ux.role, role) { role = it }
        Field(strings.ux.company, company) { company = it }
        Field(strings.ux.email, email) { email = it }
        Field(strings.notes, notes) { notes = it }
        Spacer(Modifier.height(8.dp))
        BlackButton(strings.save, { onSave(name, role, company, email, notes) }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlineButton(strings.ux.deleteProfile, onDelete, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    Text(label, color = VelumInkSecondary, fontSize = 12.sp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        textStyle = TextStyle(color = VelumInk, fontSize = 15.sp),
        modifier = Modifier
            .semantics { contentDescription = label }
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    )
}
