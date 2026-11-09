# Issue #85 source coverage inventory

- Issue: https://github.com/bloodf/velum/issues/85
- Lease: f55f27d3-1121-4651-b7a6-a516cec234ad (caller releases)
- Exact audited HEAD: `963663004e44f7a9b3816fc4c247a8053a2f6578` (`t3/audit-download-service-coverage`, `origin/main`)
- Scope: read-only source inventory; no tests/builds/device runs performed.
- Issue retrieval: `web.open` failed with Cache miss; `gh issue view 85 --repo bloodf/velum --json title,body,state,url` failed to connect to api.github.com. Accordingly, the issue's exact acceptance wording is unavailable here; rows below map source-observable behaviors tied to #85 and do not claim exact issue wording.
- Tooling: read `AGENTS.md` and `docs/ai-tooling.md`. CodeGraph 1.6.2 status reported `initialized:false`; did not run `init`/`sync` because those create/modify index files and the task forbids file changes beyond this requested evidence artifact. Used bounded `rg` and numbered ranges instead. Initial working tree was clean.

## Requirement / behavior to regression-test map

| Source behavior associated with #85 | Implementation evidence | Existing regression coverage | Assessment |
|---|---|---|---|
| Session download enters foreground and presents session progress/cancel action. | `SessionDownloadService.kt:32-40,55-69`; `DownloadProgressNotifier.android.kt:46-60,89-100,128-155`; manifest `AndroidManifest.xml:70-77`. | `SessionDownloadServiceTest.kt:56-64` checks selected connected-device type and notification title/body/progress/action. `DeviceDownloadForegroundTest.kt:83-113` observes the real VM path starts the service and shows session text. | Source-covered by unit/Robolectric tests; not proof of OS/device behavior. |
| Permission-dependent type selection and fallback from connectedDevice to dataSync. | `SessionDownloadService.kt:55-74`; manifest `AndroidManifest.xml:70-73`. | `SessionDownloadServiceTest.kt:67-82,120-130` checks no-Bluetooth type, refused-type fallback, merged service declaration and permissions. Instrumented `SessionDownloadServiceDeviceTest.kt:50-59` specifies both type paths on Android. | Robolectric source coverage exists; instrumented device tests not run. |
| Promotion refusal leaves service stopped without crashing; stop during pending start still promotes then stops. | `SessionDownloadService.kt:32-40,94-108`. | `SessionDownloadServiceTest.kt:84-99`. | Covered for synchronous command ordering in Robolectric. |
| Service releases after a download completes, gets cancelled, or fails terminally. | Notifier show/dismiss lifecycle `DownloadProgressNotifier.android.kt:46-79`; VM registration, terminal `finally`, cancellation `cancelDownload`, and coroutine handler `DeviceViewModel.kt:229-234,274-303,335-394`. | `DeviceDownloadForegroundTest.kt:120-147` covers completion/cancel/failure through a VM using synthetic BLE/persistence. | Covered for VM/service wanted flag and posted notification cleanup under Robolectric. |
| Android 15 dataSync timeout invokes cancellation and stops service. | `SessionDownloadService.kt:43-48`; notifier callback dispatch `DownloadProgressNotifier.android.kt:81-87`. | `SessionDownloadServiceTest.kt:110-118` directly invokes `onTimeout` and asserts callback count and stopped status. | Callback contract covered in isolation; real OS timeout delivery and resulting actual VM/BLE cancellation are not established by that test. |
| Download remains active in background/screen-off while service is foreground. | Service coverage intent `SessionDownloadService.kt:16-22`; full scenario `DownloadBackgroundDeviceTest.kt:45-57,110-177`. | Instrumented scenario uses fake in-process peripheral, checks progress while activity stopped/screen off, saved bytes and CRC. | Not run. Test comments explicitly exclude process survival attribution, Doze, CPU suspend, real BLE/OEM background behavior. Real Note Pro background/Doze/process-death remains unverified per `docs/STATUS.md:72`. |

## Concrete uncovered behavior

The source has race-aware `latestStartId` handling (`SessionDownloadService.kt:28,32-39,76-84`): stop calls `stopSelfResult(latestStartId)`, and a newer pending start is intended to keep service alive for its command/promotion. Existing Robolectric coverage tests a stop before the first delivered command (`SessionDownloadServiceTest.kt:91-99`) and a stop after one promotion (`:101-108`), but does not exercise two queued/delivered start commands where an older stop races a newer start ID. A narrow regression check could deliver start ID N, queue a second start N+1, issue stop against the active service before delivery of N+1, then deliver N+1 and assert it is promoted while wanted remains true; separately assert a later stop terminates it. This is a source-level coverage gap, not evidence of a production defect.

The notification action path builds an immutable broadcast `PendingIntent` and dispatches by kind (`DownloadProgressNotifier.android.kt:137-144,164-168`). Search found no matching Android unit test exercising `onCancelBroadcast`/`DownloadCancelReceiver`; the VM test calls `vm.cancelDownload()` directly. Notification-action-to-VM cancellation therefore is not covered end-to-end by the located regression tests. This gap is separate from the service timeout callback coverage.

## Device boundary / conclusion

Source coverage exists for the service lifecycle and core terminal paths; no source defect is established by this read-only pass. Device proof is pending for real OS foreground promotion and permission/type behavior, OS timeout delivery, and background/Doze/process-death behavior on physical hardware. The existing instrumented test source is not a substitute for a completed device run, and no device success or performance claim is made.

## Proposed narrow check command (not run)

```sh
./gradlew :composeApp:testDebugUnitTest --tests 'dev.velum.app.platform.SessionDownloadServiceTest' --tests 'dev.velum.app.di.DeviceDownloadForegroundTest'
```

This runs the focused service-lifecycle and VM foreground regression classes. It does not prove real-device behavior; instrumented tests would require a separate authorized device run.
