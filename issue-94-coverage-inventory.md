# Issue #94 source coverage inventory

## Scope and evidence boundary

- Repository: `bloodf/velum`, issue URL: https://github.com/bloodf/velum/issues/94
- Lease: `c194ce69-d52f-468a-9131-11e66afc63f3`
- Audited ref: `963663004e44f7a9b3816fc4c247a8053a2f6578` (branch `t3/2728b55f`)
- Working tree was clean at audit start. No source, tests, PRs, remote branches, or GitHub issues were changed.
- `docs/ai-tooling.md` was read. CodeGraph 1.6.2 was initialized/synced for this worktree; it indexed 930 files / 19,997 nodes / 45,328 edges. Languages include Kotlin, C/C++, Swift, TypeScript and others. It is a navigation aid, not runtime proof.
- Issue body could not be retrieved: `gh issue view 94 --repo bloodf/velum ...` failed with `error connecting to api.github.com`; web open returned cache miss. Therefore issue-specific requirement wording cannot be quoted or mapped. Inventory below is bounded to the requested BLE resume, CRC, and simulated-fault topics visible in current source. Reconcile against issue text when available.
- No builds or tests were run. Evidence establishes source/test presence only; it does not establish device behavior or performance.

## Requirement-to-test map (scope inferred from the requested audit topics)

| Topic | Current source and tests | Coverage visible in source |
|---|---|---|
| Resume after partial transfer | `ble/src/commonMain/kotlin/dev/velum/ble/client/ResumableSessionDownload.kt:17-24,91-98,131-139`; `ble/src/jvmTest/kotlin/dev/velum/ble/ResumableSessionDownloadTest.kt:108-137` | Disconnects at several offsets resume at last received byte; garbled packet, gap, lost notification, and overlap cases retry and assert byte-exact output. One lost chunk after resume retries same offset. |
| Device refusal / unusable resume | Same production file `:153-160,183-190`; test `ResumableSessionDownloadTest.kt:258-307,375-435` | Refusal, unaligned restart, relative offsets, bad saved bytes, silent and malformed resume replies lead to restart from zero / exact reconstruction. |
| CRC validation / mismatch policy | Production `ResumableSessionDownload.kt:26-36,131-147,201-212`; tests `ResumableSessionDownloadTest.kt:95-106,139-179,621-...` | Enforced CRC on large synthetic transfer; corrupt byte triggers fresh-from-zero retry; ReportOnly returns mismatch and retains received data; CRC absent remains NotProvided. Test later in file caps repeated mismatches. |
| Fault fixture semantics | `ble-testing/src/commonMain/kotlin/dev/velum/ble/fake/TransferFaults.kt:3-54`; `FakePlaudPeripheral.kt:222-259,276-325` | Fixture supports one-shot disconnect, corruption, garble/auth failure, dropped gap, duplicate/replay, lost notification, overlap, stray session frames, endless repeat, offset-ignore/relative offset, refusal/silence/malformed reply, persistent gaps and CRC absent/wrong. |
| Simulated peripheral scenario coverage | `ResumableSessionDownloadTest.kt:120-160,248-435`; `SimulatedPeripheralScenariosTest.kt:338-363` | Explicit assertions exercise selected fault modes. Additional fixture modes exist, but fixture capability alone is not test coverage. |

## Concrete remaining source-coverage gaps

These are unverified behaviors inferred by comparing fixture knobs to downloader-level tests; not claims of implementation defects:

- `TransferFaults(duplicateAt=...)`, `replayAt=...`, `strayEndAt=...`, `repeatBurstAt=...`, and `endlessRepeatAt=...` are exercised at the lower-level client by `ble/src/jvmTest/kotlin/dev/velum/ble/TransferIntegrityTest.kt:105,151,177-206`. This inventory found no downloader-level assertion that these particular input faults recover to a byte-exact resumed download. `disconnectEveryPassAt` is exercised at downloader level in `ResumableSessionDownloadTest.kt:354-416`.
- `StopCrc.Wrong` is covered at downloader level by `ResumableSessionDownloadTest.kt:621-...`; it is not a gap. The suite tests the distinct wrong-stop-CRC path in addition to `corruptAt`.
- Android/iOS hardware BLE resume and CRC behavior is not proven by JVM simulator tests. The test class itself states at lines 37-39: synthetic keys/data, no hardware, and a real Note Pro’s resume semantics and CRC algorithm remain unverified.
- Issue acceptance criteria remain unavailable from GitHub, so this list is scoped to the requested audit topics and is not a full issue closure assessment.

## Result

Source coverage is substantial for synthetic resume, CRC policy, and several injected faults. Status: **source coverage inventoried; device proof pending**. No device success or performance conclusion is made.

## Proposed narrow check (not run)

`./gradlew :ble:jvmTest --tests 'dev.velum.ble.ResumableSessionDownloadTest'`

This targets the focused JVM resume/CRC simulator suite. It does not prove physical BLE device compatibility.
