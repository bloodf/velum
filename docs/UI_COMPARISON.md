# Plaud vs Velum UI comparison (2026-10-26)

Reference: owner `plaud-ui-screenshots.zip` (33 PNGs, **not committed**).
Velum: Roborazzi `w360dp-h800dp-xxhdpi` phone captures under CI artifact `velum-ui-screenshots`.

KVM + AVD `velum_api34` is available in the agent VM. Official Plaud APK was **not** installed here (no login/account per owner rules; APKMirror fetch deferred to avoid third-party APK trust issues in CI). Comparison uses the owner reference zip + Velum Roborazzi renders.

## Side-by-side diffs

| Screen | Match | Diff / fix |
|---|---|---|
| Files list | Strong | Velum uses **Arquivos** (pt-BR); Plaud build showed truncated `Todos os fi…` (EU PT). Bottom selected tab now **filled** folder icon (was outline). Velum top-left uses brand mark + sync; Plaud shows device glyph — intentional (no Plaud branding). Profile is silhouette vs photo. |
| FAB overlay | Strong | Black FAB + sparkle; Start recording / Import rows present. |
| Recording Fontes | Strong | Fontes/Notas tabs, player chrome, Retranscrever CTA. Transcript now Markdown (`**Speaker:**`). |
| Recording Notas | Strong | Markdown preview (headings, lists, checkboxes). Ask AI / Generate CTAs. |
| Name speakers | Good | Sheet layout matches; Velum binds real DB speakers + voice profiles (not hardcoded demos only). |
| Generate sheet | Good | Auto/Custom + template/language/model rows; Apply runs `RecordingProcessor`. |
| Templates | Good | Discover grid from seeded DB; search works. |
| Explore | Good | Black header + list. Cloud sync / invite rows are informational; OAuth integrations report blocked-on-credentials. |
| AutoFlow | Good | List + edit persist; runner executes on matching `note_pro` imports. |
| Integrations | Good | Google Calendar shows **blocked** until owner registers OAuth client id (no fake Connected). |
| Settings hub | Good | Stats + Personalização / Preferências / Conta. No owner photo (privacy). |
| AI processing | Strong | Local/API per task; language pickers persist. |
| Model manager | Strong | Real download / BlockedOnUrl / delete; no fake Ready. |

## Remaining intentional differences

- No Plaud logos, wordmarks, or proprietary templates.
- Demo seed content (2 recordings) vs owner’s single-device library.
- Heatmap / avatar photo omitted or simplified for privacy.
- Mind map CTA is present but generation is not a separate renderer yet (Ask AI / Generate cover summary path).

## Confirm only on owner Note Pro

- BLE unlock with real `sn-sign` keys + battery/list/download against firmware 1.5/1.7.
- Ogg Opus sample-rate / PLAUD.AI decrypt of a real capture.
- Thermal / long-file WorkManager behavior on a physical phone.
