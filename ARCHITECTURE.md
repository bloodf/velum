# Architecture

This is a map of the implementation, not a release certification. Android is the
current delivery target. Shared source sets and a desktop shell do not establish
feature parity, hardware compatibility, or supported iOS distribution. See
[hardware validation](docs/HARDWARE_VALIDATION.md) and [status](docs/STATUS.md).

## Modules

The authoritative module list is [settings.gradle.kts](settings.gradle.kts).
Dependencies below describe production source sets; tests may use extra modules.

| Module | Responsibility | Project dependencies |
|---|---|---|
| [plaud-protocol](plaud-protocol/) | Frames, commands, settings and downloaded-file wire formats | None |
| [core](core/) | SQLDelight repository, settings, secure-store contracts, identity import, templates and AutoFlow definitions | plaud-protocol, exported with `api` |
| [recorder](recorder/) | Recorder driver interfaces and Plaud handshake/session logic | core, plaud-protocol |
| [ble](ble/) | BLE transport, mobile Kable implementation and transfer adapters | core, plaud-protocol, recorder |
| [ble-testing](ble-testing/) | Test-only BLE and Wi-Fi fakes (`FakeBleTransport`, `FakePlaudPeripheral`, `FakeWifiSoftApJoiner`, `FakeWifiDeviceClient`); consumed only from test source sets | ble, core, plaud-protocol |
| [ai](ai/) | Audio decoding, model management, engine routing, processing jobs and AutoFlow execution | core |
| [composeApp](composeApp/) | Application composition, Compose screens, import/export, microphone capture and platform entry points | All five modules above |

The module boundaries are defined in their `build.gradle.kts` files. BLE currently
also contains fake peripheral code in production source sets; moving it to test
support and generalizing GATT remain tracked by #111 and #19. The desktop BLE
implementation is a stub. iOS source sets contain unsupported runtimes, including
a passthrough profile cipher; they must not be described as production encrypted
profile support.

## Inputs and persistence

Android starts in
[VelumApplication](composeApp/src/androidMain/kotlin/dev/velum/app/VelumApplication.kt),
which constructs [AppContainer](composeApp/src/commonMain/kotlin/dev/velum/app/di/AppContainer.kt)
and installs processing dependencies. [App.kt](composeApp/src/commonMain/kotlin/dev/velum/app/App.kt)
observes container/repository state and routes screens.

There are three input paths:

1. Recorder import starts in
   [DeviceViewModel](composeApp/src/commonMain/kotlin/dev/velum/app/ui/DeviceViewModel.kt).
   Identity is imported through
   [PlaudExportImporter](core/src/commonMain/kotlin/dev/velum/core/security/PlaudExportImporter.kt)
   and the `DeviceKeyImporter` contract in
   [SecureStore.kt](core/src/commonMain/kotlin/dev/velum/core/security/SecureStore.kt).
   [PlaudRecorderDriver](recorder/src/commonMain/kotlin/dev/velum/recorder/plaud/PlaudRecorderDriver.kt)
   handles recorder operations over the supplied transport. Downloaded bytes go to
   a temp file, then `PlaudFileCrypto.decryptStream` decrypts them in bounded
   pieces into `<id>.part`, which `AppContainer.persistDownloadedSession` renames
   to its final name before inserting a recording and queuing processing. JVM tests
   bound the pieces and heap; multi-hour hardware captures are still unvalidated
   (#86, #6). On iOS `createTempSessionFile()` returns null, so the encrypted
   session is still held whole in memory before chunked decrypt; iOS recorder
   import is not a supported target. Rows are unique on device session id plus
   device serial (no dedup without a real serial); a re-download atomically
   replaces the existing row's audio and keeps its title, transcript and summary (#113).
2. File import starts in
   [AudioImportPipeline](composeApp/src/commonMain/kotlin/dev/velum/app/importing/AudioImportPipeline.kt).
   It hashes the source, normalizes audio using the platform `AudioNormalizer`,
   checks normalized content hashes, creates a local recording, resolves AutoFlow
   parameters and enqueues the same processor. Before the container is published,
   a startup sweep quarantines unreferenced final audio files older than 24 hours in
   the recordings directory and purges quarantine after 7 days (#122).
3. Android phone capture uses
   [MicCapture](composeApp/src/androidMain/kotlin/dev/velum/app/platform/MicCapture.android.kt),
   [MicRecordingService](composeApp/src/androidMain/kotlin/dev/velum/app/platform/MicRecordingService.kt)
   and [WavWriter](composeApp/src/androidMain/kotlin/dev/velum/app/platform/WavWriter.android.kt).
   Capture ownership, completion and recovered-file publication are separate from
   AI processing. A local microphone test does not prove physical recorder import.

Structured state and migrations live in
[Velum.sq](core/src/commonMain/sqldelight/dev/velum/core/db/Velum.sq) and its
[migrations directory](core/src/commonMain/sqldelight/dev/velum/core/db/migrations/).
[VelumRepository](core/src/commonMain/kotlin/dev/velum/core/data/VelumRepository.kt)
is the application data boundary. Audio/model/profile files live outside the SQL
rows; deletion or recovery must account for both references and active sessions.

## Processing and UI

[RecordingJobScheduler](ai/src/commonMain/kotlin/dev/velum/ai/pipeline/RecordingJobScheduler.kt)
selects a platform scheduling implementation. Android uses
[RecordingProcessWorker](ai/src/androidMain/kotlin/dev/velum/ai/pipeline/RecordingProcessWorker.kt);
the shared fallback schedules coroutines in process, which is not durable work
after process termination.

[RecordingProcessor](ai/src/commonMain/kotlin/dev/velum/ai/pipeline/RecordingProcessor.kt)
coordinates audio input, transcription, diarization, transcript/summary persistence
and processing state. [AiTaskRouter](ai/src/commonMain/kotlin/dev/velum/ai/engine/AiTaskRouter.kt)
chooses local or user-configured API engines per task. Model catalog presence does
not mean that weights are installed or inference has been verified. See
[AI architecture](docs/ARCHITECTURE_AI.md) for engine-specific contracts and limits.

[AutoFlowRunner](ai/src/commonMain/kotlin/dev/velum/ai/pipeline/AutoFlowRunner.kt)
observes recording/rule changes. A failure in one recording must be reported
without ending collection for subsequent recordings; cancellation retains its
normal coroutine semantics. See [AutoFlow templates](docs/autoflow-templates.md).

[VoiceProfileStore](ai/src/commonMain/kotlin/dev/velum/ai/local/VoiceProfileStore.kt)
uses an awaited readiness barrier and serialized profile access. Android/JVM use
AES-GCM. Existing key and ciphertext formats remain readable. Profile inventory
distinguishes per-file damage from unavailable key/provider state; explicit removal
is tied to the inspected files and must not become automatic recovery by deletion.
Android vault failures are retryable and preserve other entries. Profile metadata is stored as JSON, and
`cacheClusterEmbedding` also writes a plaintext `.f32` compatibility cache; these
file formats are visible in the same store implementation.

Critical Android journeys are exercised by `DeviceWalkUiTest` with synthetic audio
and processing engines. Those tests establish navigation/persistence behavior in
the tested variant; they do not measure real ASR quality, recorder interoperability,
physical Keystore behavior, or genuine process death. See
[engineering standards](docs/ENGINEERING_STANDARDS.md) for commands and evidence rules.
