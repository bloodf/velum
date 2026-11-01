# Issue coverage snapshot — 2026-11-01

Remote main: `1f30a0f18fb959a6020606f50e82d5ac0bb2601d`. 101 open, 16 closed, 117 total issues; PRs excluded. Both open pages were read. Closed-page size is below100.

This is complete by issue ID. Many rows have not had a full source audit. Read the linked issue before implementation. See [machine-readable inventory](issue-coverage-2026-11-01.json) for acceptance excerpts, evidence, gaps and references.

| Issue | State | Assessment | PRs |
|---|---|---|---|
| [#3 Extract Note Pro identity (snSignature, bind token, keys) via the USB route ](https://github.com/bloodf/velum/issues/3) | open | hardware_or_owner_evidence_needed | — |
| [#4 Import Note Pro identity into the app and verify Keystore storage ](https://github.com/bloodf/velum/issues/4) | open | hardware_or_owner_evidence_needed | — |
| [#5 Validate BLE unlock, advertising and battery on a real Note Pro ](https://github.com/bloodf/velum/issues/5) | open | hardware_or_owner_evidence_needed | — |
| [#6 Validate multi-hour download with resume and CRC on a real Note Pro ](https://github.com/bloodf/velum/issues/6) | open | hardware_or_owner_evidence_needed | — |
| [#7 Confirm Ogg Opus rate/channels and PLAUD.AI decrypt against a real capture ](https://github.com/bloodf/velum/issues/7) | open | hardware_or_owner_evidence_needed | — |
| [#8 Validate Wi-Fi SoftAP fast transfer (:8081) on a real Note Pro ](https://github.com/bloodf/velum/issues/8) | open | hardware_or_owner_evidence_needed | — |
| [#9 Check whether FE10 alone works with E2EE off (no FE11) ](https://github.com/bloodf/velum/issues/9) | open | hardware_or_owner_evidence_needed | — |
| [#10 Remove the 200 MiB decoded-audio hard fail for very long recordings ](https://github.com/bloodf/velum/issues/10) | closed | closed_before_task_not_reaudited | — |
| [#11 Share a test APK with the owner after hardware validation passes ](https://github.com/bloodf/velum/issues/11) | open | external_or_owner_requirements_to_resolve | — |
| [#12 Measure on-device performance and thermals of Parakeet, Whisper and Gemma ](https://github.com/bloodf/velum/issues/12) | open | hardware_or_owner_evidence_needed | — |
| [#13 Verify Whistle end-to-end on an arm64 emulator with low-RAM limits ](https://github.com/bloodf/velum/issues/13) | open | audit_pending | — |
| [#14 Validate diarization and voice-profile recognition on real meetings ](https://github.com/bloodf/velum/issues/14) | open | hardware_or_owner_evidence_needed | — |
| [#15 Omi / Friend driver from the open-source Omi project ](https://github.com/bloodf/velum/issues/15) | open | audit_pending | — |
| [#16 DIY ESP32 / XIAO devices running Friend firmware (from open-source firmware) ](https://github.com/bloodf/velum/issues/16) | open | audit_pending | — |
| [#17 Pocket / OpenPocket driver from open-source sources ](https://github.com/bloodf/velum/issues/17) | open | audit_pending | — |
| [#18 Limitless Pendant driver from open-source / community protocol work ](https://github.com/bloodf/velum/issues/18) | open | audit_pending | — |
| [#19 Generic multi-GATT BLE transport for recorder drivers ](https://github.com/bloodf/velum/issues/19) | open | current_main_transport_API_gap_confirmed | — |
| [#20 Feasibility research: Bee, Soundcore Work, iFlytek and other closed note-takers ](https://github.com/bloodf/velum/issues/20) | open | audit_pending | — |
| [#21 Verify the curated Plaud template catalog shipped (100 per category) ](https://github.com/bloodf/velum/issues/21) | closed | closed_before_task_not_reaudited | — |
| [#22 Hide badge/author on built-in templates; credit only PR-contributed templates ](https://github.com/bloodf/velum/issues/22) | closed | closed_before_task_not_reaudited | — |
| [#23 Polish site screenshots to marketing quality ](https://github.com/bloodf/velum/issues/23) | open | audit_pending | — |
| [#24 Build and deploy the marketing website ](https://github.com/bloodf/velum/issues/24) | open | audit_pending | — |
| [#25 Upload the GitHub social preview image ](https://github.com/bloodf/velum/issues/25) | open | audit_pending | — |
| [#26 Release signing and Google Play listing via fastlane ](https://github.com/bloodf/velum/issues/26) | open | external_or_owner_requirements_to_resolve | — |
| [#27 Publish on F-Droid ](https://github.com/bloodf/velum/issues/27) | open | external_or_owner_requirements_to_resolve | — |
| [#28 Close remaining review debt: SoftAP fixed AEAD nonce, dependency updates, OSV CI ](https://github.com/bloodf/velum/issues/28) | open | audit_pending | — |
| [#29 Desktop D0: owner sign-off that desktop starts after the Android gate ](https://github.com/bloodf/velum/issues/29) | open | external_or_owner_requirements_to_resolve | — |
| [#30 Desktop D1: mic + system-audio capture on macOS and Windows ](https://github.com/bloodf/velum/issues/30) | open | audit_pending | — |
| [#31 Desktop D2: local STT on desktop (chunked, then near-live) ](https://github.com/bloodf/velum/issues/31) | open | audit_pending | — |
| [#32 Desktop D3: meeting detection and calendar auto-start ](https://github.com/bloodf/velum/issues/32) | open | audit_pending | — |
| [#33 Desktop D4: UX parity with the phone app + packaging ](https://github.com/bloodf/velum/issues/33) | open | audit_pending | — |
| [#34 Desktop D5: user-owned sync and MCP/CLI access to notes ](https://github.com/bloodf/velum/issues/34) | open | audit_pending | — |
| [#35 iOS: Xcode project, CI, Kable BLE and Keychain SecureStore ](https://github.com/bloodf/velum/issues/35) | open | audit_pending | — |
| [#36 iOS: recording, import and local AI (ASR, diarization, LLM) ](https://github.com/bloodf/velum/issues/36) | open | audit_pending | — |
| [#37 iOS: TestFlight and App Store release ](https://github.com/bloodf/velum/issues/37) | open | external_or_owner_requirements_to_resolve | — |
| [#38 [Epic] Device identity & Note Pro ](https://github.com/bloodf/velum/issues/38) | open | audit_pending | — |
| [#39 [Epic] Android 1.0 hardware validation ](https://github.com/bloodf/velum/issues/39) | open | hardware_or_owner_evidence_needed | — |
| [#40 [Epic] Local AI quality & performance ](https://github.com/bloodf/velum/issues/40) | open | audit_pending | — |
| [#41 [Epic] Templates ](https://github.com/bloodf/velum/issues/41) | open | audit_pending | — |
| [#42 [Epic] Release & distribution ](https://github.com/bloodf/velum/issues/42) | open | audit_pending | — |
| [#43 [Epic] Website & marketing ](https://github.com/bloodf/velum/issues/43) | open | audit_pending | — |
| [#44 [Epic] Other note-taker devices ](https://github.com/bloodf/velum/issues/44) | open | audit_pending | — |
| [#45 [Epic] Desktop app (macOS & Windows) ](https://github.com/bloodf/velum/issues/45) | open | audit_pending | — |
| [#46 [Epic] iOS app ](https://github.com/bloodf/velum/issues/46) | open | audit_pending | — |
| [#47 [Roadmap] Velum roadmap ](https://github.com/bloodf/velum/issues/47) | open | audit_pending | — |
| [#48 Integrations framework: on-device OAuth, Keystore token store, registry, Settings > Integrations ](https://github.com/bloodf/velum/issues/48) | closed | closed_before_task_not_reaudited | — |
| [#49 Google Cloud OAuth client, consent screen and scope verification ](https://github.com/bloodf/velum/issues/49) | open | external_or_owner_requirements_to_resolve | — |
| [#50 Azure (Entra) app registration for Microsoft Graph ](https://github.com/bloodf/velum/issues/50) | open | external_or_owner_requirements_to_resolve | — |
| [#51 Google Calendar API: attendees and meeting-link enrichment ](https://github.com/bloodf/velum/issues/51) | open | audit_pending | — |
| [#52 Google Docs export of AI note ](https://github.com/bloodf/velum/issues/52) | open | audit_pending | — |
| [#53 Google Drive export: audio, transcript and note Markdown ](https://github.com/bloodf/velum/issues/53) | open | audit_pending | — |
| [#54 Gmail: send note or summary via draft ](https://github.com/bloodf/velum/issues/54) | open | audit_pending | — |
| [#55 Microsoft Outlook calendar via Graph: enrichment and create event ](https://github.com/bloodf/velum/issues/55) | open | audit_pending | — |
| [#56 OneDrive export ](https://github.com/bloodf/velum/issues/56) | open | audit_pending | — |
| [#57 OneNote export ](https://github.com/bloodf/velum/issues/57) | open | audit_pending | — |
| [#58 Microsoft Teams: post summary to a chat or channel ](https://github.com/bloodf/velum/issues/58) | open | audit_pending | — |
| [#59 Notion export: page in a chosen database ](https://github.com/bloodf/velum/issues/59) | open | audit_pending | — |
| [#60 Obsidian / local folder export via Storage Access Framework ](https://github.com/bloodf/velum/issues/60) | closed | closed_before_task_not_reaudited | — |
| [#61 Slack: post summary to a channel ](https://github.com/bloodf/velum/issues/61) | open | audit_pending | — |
| [#62 Generic webhook (Zapier / n8n / Make) with JSON schema and HMAC signing ](https://github.com/bloodf/velum/issues/62) | closed | closed_before_task_not_reaudited | — |
| [#63 AutoFlow 'action after processing' for each integration ](https://github.com/bloodf/velum/issues/63) | closed | closed_before_task_not_reaudited | — |
| [#64 Epic: Third-party integrations (Google Workspace, Microsoft 365, others) ](https://github.com/bloodf/velum/issues/64) | open | audit_pending | — |
| [#65 Device pairing via QR code (desktop shows, mobile scans) ](https://github.com/bloodf/velum/issues/65) | open | audit_pending | — |
| [#66 LAN discovery and direct transport ](https://github.com/bloodf/velum/issues/66) | open | audit_pending | — |
| [#67 WebRTC data channel transport across networks ](https://github.com/bloodf/velum/issues/67) | open | audit_pending | — |
| [#68 End-to-end encryption and authentication with paired keys ](https://github.com/bloodf/velum/issues/68) | open | audit_pending | — |
| [#69 Sync data model and conflict-free merge ](https://github.com/bloodf/velum/issues/69) | open | audit_pending | — |
| [#70 Background and opportunistic sync (Android WorkManager, desktop daemon) ](https://github.com/bloodf/velum/issues/70) | open | audit_pending | — |
| [#71 Offline fallbacks: Bluetooth transfer and encrypted bundle export/import ](https://github.com/bloodf/velum/issues/71) | open | audit_pending | — |
| [#72 Sync status UI ](https://github.com/bloodf/velum/issues/72) | open | audit_pending | — |
| [#73 Epic: Serverless P2P sync between mobile and desktop ](https://github.com/bloodf/velum/issues/73) | open | audit_pending | — |
| [#74 Cloud product definition: plans, hours quota, pricing, free-forever scope ](https://github.com/bloodf/velum/issues/74) | open | external_or_owner_requirements_to_resolve | — |
| [#75 Build flavors: oss vs store with build-time config injection ](https://github.com/bloodf/velum/issues/75) | open | audit_pending | — |
| [#76 Client cloud module boundary and documented contract ](https://github.com/bloodf/velum/issues/76) | open | audit_pending | — |
| [#77 Private server repository bootstrap (pointer issue) ](https://github.com/bloodf/velum/issues/77) | open | external_or_owner_requirements_to_resolve | — |
| [#78 Accounts for store builds only (Sign in with Google / Apple) ](https://github.com/bloodf/velum/issues/78) | open | audit_pending | — |
| [#79 Subscriptions and billing: Play Billing and StoreKit 2 ](https://github.com/bloodf/velum/issues/79) | open | external_or_owner_requirements_to_resolve | — |
| [#80 Hosted transcription and summaries with metered hours ](https://github.com/bloodf/velum/issues/80) | open | audit_pending | — |
| [#81 Encrypted cloud storage/backup and cloud sync relay ](https://github.com/bloodf/velum/issues/81) | open | audit_pending | — |
| [#82 Managed integrations via server broker ](https://github.com/bloodf/velum/issues/82) | open | audit_pending | — |
| [#83 Privacy policy, terms, data deletion, LGPD/GDPR ](https://github.com/bloodf/velum/issues/83) | open | audit_pending | — |
| [#84 Epic: Velum Cloud (optional paid subscription, post-1.0) ](https://github.com/bloodf/velum/issues/84) | open | audit_pending | — |
| [#85 BLE/Wi-Fi session download foreground service for background survival ](https://github.com/bloodf/velum/issues/85) | open | audit_pending | — |
| [#86 Stream Opus/WAV decode to disk to avoid OOM on multi-hour recordings ](https://github.com/bloodf/velum/issues/86) | open | current_batch_review_pending | — |
| [#87 Localize Voice Profiles screen (EN + pt-BR, no engine jargon) ](https://github.com/bloodf/velum/issues/87) | open | current_batch_review_pending | — |
| [#89 Software: Note Pro identity extraction file format + parser (fixtures) ](https://github.com/bloodf/velum/issues/89) | closed | closed_completed_in_this_task | #128 |
| [#90 Release build must not fall back to the committed debug keystore ](https://github.com/bloodf/velum/issues/90) | closed | closed_completed_in_this_task | #123 |
| [#91 Reduce release APK size (~200 MB) ](https://github.com/bloodf/velum/issues/91) | open | audit_pending | — |
| [#92 Validate identity import and Android Keystore storage with synthetic fixtures ](https://github.com/bloodf/velum/issues/92) | open | open_partial_persistence_proven_atomicity_draft | #129, #131 |
| [#93 Validate BLE unlock, advertising parsing, and battery updates with simulated peripheral ](https://github.com/bloodf/velum/issues/93) | open | audit_pending | — |
| [#94 Validate multi-hour BLE download, resume, and CRC under simulated faults ](https://github.com/bloodf/velum/issues/94) | open | audit_pending | — |
| [#95 Validate Ogg Opus rate/channels detection and PLAUD.AI decrypt with synthetic fixtures ](https://github.com/bloodf/velum/issues/95) | open | audit_pending | — |
| [#96 Validate Android SoftAP :8081 fast transfer against a mock server ](https://github.com/bloodf/velum/issues/96) | open | audit_pending | — |
| [#97 Exercise FE10-only sessions with E2EE off against a simulated peripheral ](https://github.com/bloodf/velum/issues/97) | open | audit_pending | — |
| [#98 Add reproducible Android benchmark harness for Parakeet, Whisper and Gemma ](https://github.com/bloodf/velum/issues/98) | open | audit_pending | — |
| [#99 Templates: deduplicate exact bodies with deterministic survivors and CI coverage ](https://github.com/bloodf/velum/issues/99) | open | source_gap_confirmed | — |
| [#100 Templates: verify full All catalog import, paging and FTS search on Android ](https://github.com/bloodf/velum/issues/100) | open | audit_pending | — |
| [#101 Templates: enforce 100 distinct Featured bodies per category and official coverage ](https://github.com/bloodf/velum/issues/101) | open | audit_pending | — |
| [#102 Templates: add composable category, tag and language filters in Discover ](https://github.com/bloodf/velum/issues/102) | open | audit_pending | — |
| [#103 Templates: change recording template and regenerate summary with version preservation ](https://github.com/bloodf/velum/issues/103) | open | audit_pending | — |
| [#104 Delete dead PlaudTemplateCatalogData.kt ](https://github.com/bloodf/velum/issues/104) | open | source_gap_confirmed_next_batches | — |
| [#105 docs: add ARCHITECTURE.md and docs/ENGINEERING_STANDARDS.md ](https://github.com/bloodf/velum/issues/105) | closed | closed_completed_in_this_task | #127 |
| [#106 Isolate R8 device tests in minifiedTest and restore shipping obfuscation ](https://github.com/bloodf/velum/issues/106) | open | open_partial_toolchain_draft_failed_validation | #123, #130 |
| [#107 Track independent review rounds until Android 1.0 passes a clean ship gate ](https://github.com/bloodf/velum/issues/107) | open | pending_independent_review_after_fixes | — |
| [#108 Flaky test: AutoFlowRunnerTest.incompleteDuration_thenMatch_runsOnceAndPostsOnReady times out intermittently ](https://github.com/bloodf/velum/issues/108) | closed | closed_completed_in_this_task | #124 |
| [#109 AutoFlowRunner: one failing recording silently stops AutoFlow ](https://github.com/bloodf/velum/issues/109) | closed | closed_completed_in_this_task | #124 |
| [#110 VoiceProfileStore key priming races with first access (MainScreenshotsTest flake, possible cold-start crash) ](https://github.com/bloodf/velum/issues/110) | closed | closed_completed_in_this_task | #125 |
| [#111 Move FakeBleTransport and FakePlaudPeripheral out of production source sets into a test-only module ](https://github.com/bloodf/velum/issues/111) | open | source_gap_confirmed_next_batches | — |
| [#112 core: commonTest resources missing from Android unit-test classpath (PlaudExportImporterTest fails in :core:testDebugUnitTest) ](https://github.com/bloodf/velum/issues/112) | closed | closed_completed_in_this_task | #123 |
| [#113 Re-downloading a device session creates a duplicate recording instead of reusing the existing one ](https://github.com/bloodf/velum/issues/113) | open | current_main_dedup_gap_confirmed | — |
| [#114 test issue please ignore ](https://github.com/bloodf/velum/issues/114) | closed | closed_before_task_not_reaudited | — |
| [#115 Device walk: soft misses must fail the walk; assert required coverage (#106 follow-up) ](https://github.com/bloodf/velum/issues/115) | open | current_batch_review_pending | — |
| [#116 Device walk: hard Files return still misses Settings gear after nested screens ](https://github.com/bloodf/velum/issues/116) | closed | closed_before_task_not_reaudited | — |
| [#117 minifiedTest R8 keep-whack: InfiniteAnimationPolicy$DefaultImpls still missing ](https://github.com/bloodf/velum/issues/117) | open | current_batch_review_pending | — |
| [#118 ScreenTopBar still inside verticalScroll on most settings/import/ask screens ](https://github.com/bloodf/velum/issues/118) | open | source_gap_confirmed_next_batches | — |
| [#119 Test VelumApplication wiring of cleanupStaleDownloadsThenPublish (#86 follow-up) ](https://github.com/bloodf/velum/issues/119) | open | source_gap_confirmed_next_batches | — |
| [#122 Startup sweep: delete orphaned recording audio files left by #113 dedup ](https://github.com/bloodf/velum/issues/122) | open | current_batch_review_pending | — |
