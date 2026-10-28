# Velum Android 1.0 UX review

**Date:** 2026-10-28  
**Reviewer:** Fresh product designer + senior Android engineer pass  
**Baseline audit:** `docs/audit/android-1.0-audit.md` (bugs already fixed — not re-audited here)  
**Head at start:** `ddaaacd`  
**Method:** Code walk of every Compose screen + Roborazzi CI screenshots (`velum-ui-screenshots` from run `40628351920`) + emulator capture in this environment. Before shots under `docs/audit/ux/before-*.png` are Velum UI only (no Plaud branding).

Status legend: `Open` · `Fixed` · `Deferred (owner/hardware)` · `Won't do (policy/scope)`

---

## Product rules (enforced)

- No UI without a fully working function; no placeholders / coming soon / disabled stubs.
- No accounts, login, profile, or telemetry UI/code.
- All user-facing text EN + pt-BR, plain language.
- Never send BLE opcodes 104 / 5 / 0xFE20 / 128 / 143; never write settings 4 / 26.
- Out of scope: OAuth, P2P sync, desktop polish, iOS, other BLE brands as product, website, Velum Cloud.

---

## Priority summary

| Priority | Theme | Count (planned) |
|----------|--------|----------------:|
| P0 | Playback dead, trash without confirm/undo, AutoFlow create broken, FAB Stop starts another take | 4 |
| P1 | Export formats, rename, sort, multi-select, copy transcript, seek-by-tap, speed, skip silence, confirm destructive actions | 10 |
| P2 | Progress notifications (model + BLE %), Device i18n, empty/loading polish, pull-to-refresh, search highlight, predictive back | 8 |
| P3 | Haptics consistency, font-scale/a11y, foldable spacing, Ask empty-corpus, AutoFlow delete/edit completeness | 6 |

---

## Gaps by screen

### Onboarding / first run — `FirstRunScreen`

| ID | Gap | Pri | Planned fix | Final |
|----|-----|-----|-------------|-------|
| FR1 | Download-recommended has no in-screen progress / cancel; errors silent | P2 | Navigate to Model Manager first; show download notification | **Fixed** |
| FR2 | Skip is one-tap with no “you can change this in Settings” toast | P3 | Snackbar after skip | **Fixed** |
| FR3 | No font-scale / TalkBack pass on hero CTAs | P3 | contentDescription + min 48dp | **Fixed** |

### Library — `FilesScreen` / `LibraryAdaptiveScreen`

| ID | Gap | Pri | Planned fix | Final |
|----|-----|-----|-------------|-------|
| LIB1 | No loading skeleton (list starts empty) | P2 | `libraryLoading` until first emission | **Fixed** |
| LIB2 | Trash empty state same as All | P2 | Distinct trash / filter empty copy | **Fixed** |
| LIB3 | Filter & Sort does not sort (`sortByCreated` unused) | P1 | Newest / oldest / title sort | **Fixed** |
| LIB4 | No rename | P1 | Long-press / menu → rename dialog (`updateRecordingTitle`) | **Fixed** |
| LIB5 | No multi-select (trash / share / restore) | P1 | Selection mode + bulk actions | **Fixed** |
| LIB6 | Trash from list missing; detail trash has no confirm / undo | P0 | Confirm dialog + undo snackbar; list swipe/menu trash | **Fixed** |
| LIB7 | No permanent delete / empty trash UI | P1 | Empty trash + delete forever with confirm | **Fixed** |
| LIB8 | No pull-to-refresh | P2 | Refresh recordings + watched-folder sync if configured | **Fixed** |
| LIB9 | Search has no hit highlighting | P2 | Highlight query in title row | **Fixed** |
| LIB10 | Long titles / pt-BR overflow | P2 | `maxLines` + ellipsis; row min height 48dp | **Fixed** |
| LIB11 | No list-level share | P1 | Share selected as MD/TXT | **Fixed** |

### Recording detail — `RecordingDetailScreen`

| ID | Gap | Pri | Planned fix | Final |
|----|-----|-----|-------------|-------|
| REC1 | Player transport is stub (`playbackUnavailable` even with file) | P0 | Real `AudioPlayer` (MediaPlayer) play/pause/seek ±15 | **Fixed** |
| REC2 | Playback speed hardcoded `"1x"` | P1 | Cycle 0.75 / 1 / 1.25 / 1.5 / 2 | **Fixed** |
| REC3 | No skip silence | P1 | WAV energy scan + skip during play | **Fixed** |
| REC4 | No seek-by-tapping transcript timestamps | P1 | Clickable `**Speaker** · m:ss` lines | **Fixed** |
| REC5 | Share is MD-only; no copy transcript | P1 | Export sheet TXT/MD/SRT (+ simple DOCX); clipboard copy | **Fixed** |
| REC6 | No rename title | P1 | Editable title / dialog | **Fixed** |
| REC7 | Trash: no confirm, no undo | P0 | Dialog + snackbar restore | **Fixed** |
| REC8 | Failed status has no dedicated error UI | P2 | Failed banner + Retry | **Fixed** |
| REC9 | Progress for ASR is indeterminate only | P2 | Stage label from worker when available | **Fixed** |
| REC10 | Hardcoded EN speaker-profile note | P1 | Localize | **Fixed** |
| REC11 | Ask/speed/"1x" a11y incomplete | P2 | Labels + TalkBack | **Fixed** |

### Live mic — `LiveTranscriptPane` + FAB

| ID | Gap | Pri | Planned fix | Final |
|----|-----|-----|-------------|-------|
| MIC1 | FAB shows Stop but always starts another recording | P0 | Stop path when `isCapturing` | **Fixed** |
| MIC2 | Stop has no confirm | P3 | Optional confirm for >30s | **Fixed** |
| MIC3 | Waveform decorative, unlabeled | P3 | contentDescription | **Fixed** |

### Import — `ImportHubScreen`

| ID | Gap | Pri | Planned fix | Final |
|----|-----|-----|-------------|-------|
| IMP1 | Progress is text-only | P2 | Linear progress while importing | **Fixed** |
| IMP2 | Clear watched folder no confirm | P2 | Confirm dialog | **Fixed** |

### Device — `DeviceScreen` / `DeviceViewModel`

| ID | Gap | Pri | Planned fix | Final |
|----|-----|-----|-------------|-------|
| DEV1 | Download/unlock status strings English-only | P1 | `DeviceUiCopy` / Strings | **Fixed** |
| DEV2 | No byte/% progress for BLE download | P1 | Progress in UI (+ optional notification) | **Fixed** |
| DEV3 | No cancel download | P2 | Cancel job | **Fixed** |
| DEV4 | Connected + 0 sessions empty weak | P2 | Dedicated empty copy | **Fixed** |
| DEV5 | BLE FGS notification | P2 | Progress notification while downloading (in-process; full FGS still #85) | **Fixed** |

### AI provider / Model Manager / API

| ID | Gap | Pri | Planned fix | Final |
|----|-----|-----|-------------|-------|
| AI1 | Model download: no system notification / cancel | P1 | Download notification + cancel | **Fixed** |
| AI2 | Delete model no confirm | P1 | Confirm dialog | **Fixed** |
| AI3 | Raw EN engine errors | P2 | Map to generic / status | **Fixed** |
| AI4 | Clear API key no confirm | P2 | Confirm | **Fixed** |

### Voice profiles

| ID | Gap | Pri | Planned fix | Final |
|----|-----|-----|-------------|-------|
| VP1 | Delete / wipe no confirm | P0 | AlertDialog | **Fixed** |
| VP2 | Export not shareable | P2 | shareText export blob | **Fixed** |

### AutoFlow

| ID | Gap | Pri | Planned fix | Final |
|----|-----|-----|-------------|-------|
| AF1 | Create `"new"` Save is no-op (`persist` returns early) | P0 | `upsertAutoflow` for new ids | **Fixed** |
| AF2 | No delete flow | P1 | Delete with confirm | **Fixed** |
| AF3 | Duration / language / notify not editable | P1 | Editable When sheet | **Fixed** |
| AF4 | Empty list has no empty state | P2 | EmptyState + CTA | **Fixed** |
| AF5 | Add button < 48dp | P2 | Min touch target | **Fixed** |

### Integrations

| ID | Gap | Pri | Planned fix | Final |
|----|-----|-----|-------------|-------|
| INT1 | Clear folder/webhook no confirm | P2 | Confirm | **Fixed** |
| INT2 | No test-webhook ping | P2 | POST test payload | **Fixed** |
| INT3 | Status reuses oauth* string names | P3 | Rename keys only if safe | Deferred |

### Ask across

| ID | Gap | Pri | Planned fix | Final |
|----|-----|-----|-------------|-------|
| ASK1 | No spinner / cancel while generating | P2 | Progress + cancel job | **Fixed** |
| ASK2 | No empty-library messaging | P2 | Distinct empty | **Fixed** |

### Settings hub + children

| ID | Gap | Pri | Planned fix | Final |
|----|-----|-----|-------------|-------|
| SET1 | Memory tap-delete no confirm | P1 | Confirm | **Fixed** |
| SET2 | Clear device keys no confirm | P1 | Confirm | **Fixed** |
| SET3 | About version hardcoded | P3 | Keep until ship bump (audit L3) | Deferred |
| SET4 | Predictive back not enabled | P2 | `enableOnBackInvokedCallback` | **Fixed** |
| SET5 | Haptics missing on many rows | P3 | Wire LocalHaptics | Deferred |
| SET6 | Process death loses form drafts | P2 | `rememberSaveable` on key forms | Deferred |

### Cross-cutting

| ID | Gap | Pri | Planned fix | Final |
|----|-----|-----|-------------|-------|
| X1 | Edge-to-edge: Activity enabled; some screens ignore insets | P2 | WindowInsets padding on top bars | **Fixed** |
| X2 | Foldable / tablet: library adaptive OK; detail dense | P3 | Wider padding when expanded | Deferred |
| X3 | Font scale 200% truncation | P2 | SoftWrap / scroll on critical labels | Deferred |
| X4 | No PDF export | P2 | Android `PdfDocument` share when reasonable | **Fixed** |
| X5 | DOCX export | P2 | Minimal OOXML zip if feasible | **Fixed** |

---

## Complex on-device features to add (Plaud-replacement 1.0)

All must work fully offline / on-device (BYO API allowed where already present):

1. **Real audio playback** with speed + skip silence + transcript seek  
2. **Multi-format export** (TXT, MD, SRT; DOCX/PDF if shippable)  
3. **Library multi-select** + rename + sort + trash undo  
4. **Model download progress notification**  
5. **BLE download progress** with cancelable job + localized status  
6. **AutoFlow create/delete** fully working  

Intentionally not adding: cloud sync UI, OAuth, accounts, other-device brands as first-class product, desktop/iOS 1.0 polish.

---

## Templates & AI provider (owner follow-ups)

| Item | Status | Notes |
|------|--------|-------|
| Full hygiene before curation | Done | `tools/templates/` + report `docs/audit/templates-hygiene.md` |
| Featured ≥100/cat + Full cleaned (~16k) | Done | Featured seeded; Full gzip lazy-imported on Discover → All |
| Tags + FTS + category/tag/lang filters | Done | 53-slug vocabulary; chips on library + Generate picker |
| Change template + summary versions | Done | Notes: Trocar modelo / Versões; schema v4 `summary_version` |
| Per-task API model + multi-profile | Done | `ApiModelResolver`: override > task default > fallback; Keystore per profile |

Catalog: Featured **1500** (100×15), Full **16153** exact-deduped. Assets ≈ Featured JSONL 4.2 MB + Full `.gz` 7.6 MB.

---

## Implementation log

| Commit | Items | Notes |
|--------|-------|-------|
| *(pending)* | Review doc | This file |
| branch `cursor/templates-hygiene-full-2011` | Hygiene, Featured/All, tags, summary versions, API model profiles | See section above |

---

## After screenshots

Store under `docs/audit/ux/` as `after-<screen>-<theme>-<locale>.png` (Velum UI only). **Populated:** 13 after-captures committed alongside this review.

---

## Final status

- Gaps found: see tables above (P0–P3).  
- Fixed: all P0/P1 and nearly all P2 items in the screen tables (`Fixed` column).  
- Deferred / light polish: INT3, SET3, SET5, SET6, X2, X3.  
- Intentionally not done: SoftAP E2E (#8), BLE connectedDevice FGS timing (#85), multi-hour OOM HW (#86), OAuth, Velum Cloud.  
- After screenshots: `docs/audit/ux/after-*.png` (13 captures).  
- Green CI (pre-packaging-fix): run `40651939251` on `be5c0be`. Packaging fix for template JSONL in APK follows on main.  
- Owner hardware checklist: below.

### Owner check on Samsung Galaxy S26 Ultra + Plaud Note Pro

1. Playback of phone mic + imported + Note Pro session audio; speed + skip silence  
2. Transcript tap-to-seek; export TXT/MD/SRT/DOCX/PDF share sheet  
3. Trash → undo; empty trash; multi-select + rename + sort  
4. Model download notification survives leaving Model Manager; cancel works  
5. BLE session download progress % then appears in library; cancel works  
6. AutoFlow: browse 107 templates → pre-fill editor → create/edit/delete; optional API model override  
7. Templates: Discover Featured (≥100/cat) + All (full cleaned set); tag/lang/category filters; Generate picker same  
8. Notes: Change template → regenerate; Versions switch/restore/delete (Local + API)  
9. AI provider: multiple endpoint profiles; per-task model mapping; Generate advanced override  
10. Light/dark + EN/pt-BR + font scale ~200% on library + detail  
11. First-run → each CTA path  
12. Confirm prior audit hardware items still pass  
