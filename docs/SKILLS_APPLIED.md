# Skills applied to Velum (Compose / KMP)

Sources cloned 2026-10-26:

- https://github.com/emilkowalski/skills → `/tmp/skills-repos/skills`
- https://github.com/expo/skills → `/tmp/skills-repos/expo-skills`
- https://github.com/Appllama/appllama-skills → `/tmp/skills-repos/appllama-skills`

Velum is **Kotlin Multiplatform + Compose Multiplatform**, not Expo/RN/web. Guidance was translated only where it maps cleanly to Compose (motion tokens, press feedback, haptics, reduce-motion, empty-state enter, nav fades). **Plaud layout/navigation structure is unchanged** (Files / Explore shell, FAB overlay, bottom bar geometry).

Commit that lands the craft pass: `df2c2b1`.

---

## Inventory (every `SKILL.md` read)

| # | Path | Skill name | Disposition |
|---|------|------------|-------------|
| 1 | `skills/skills/animate/SKILL.md` | animate | **Applied** (tokens, easing, durations, no scale(0)) |
| 2 | `skills/skills/animate-expo/SKILL.md` | animate-expo | **Applied** (mobile: press scale, springs/timing split, haptics, reduce motion) |
| 3 | `skills/skills/animation-performance/SKILL.md` | animation-performance | **Applied** (transform/opacity only; no layout anim on lists) |
| 4 | `skills/skills/animation-vocabulary/SKILL.md` | animation-vocabulary | Read — glossary only; no code |
| 5 | `skills/skills/apple-design/SKILL.md` | apple-design | **Applied** (interruptible settle, press-in feedback, spatial consistency) |
| 6 | `skills/skills/ask-sonner/SKILL.md` | ask-sonner | **Skipped** — React Sonner toast library |
| 7 | `skills/skills/break-ui/SKILL.md` | break-ui | **Skipped** this pass — adversarial data harness; existing worst-case strings already localized |
| 8 | `skills/skills/emil-design-eng/SKILL.md` | emil-design-eng | **Applied** (unseen details: press scale, haptic on commit, empty enter) |
| 9 | `skills/skills/find-animation-opportunities/SKILL.md` | find-animation-opportunities | **Applied** as filter — only high-frequency chrome + overlays; no list stagger |
| 10 | `skills/skills/improve-animations/SKILL.md` (+ `AUDIT.md`) | improve-animations | **Applied** (duration budgets, ease-out tables) |
| 11 | `skills/skills/mobile-native/SKILL.md` | mobile-native | **Skipped** — web/CSS viewport & tap-highlight fixes; Velum is native Compose |
| 12 | `skills/skills/pick-ui-library/SKILL.md` | pick-ui-library | **Skipped** — already on Compose Material3 |
| 13 | `skills/skills/prototype/SKILL.md` | prototype | **Skipped** — HTML prototype workflow |
| 14 | `skills/skills/review-animations/SKILL.md` | review-animations | Read — used as self-check bar for this pass |
| 15 | `skills/skills/write-swift/SKILL.md` | write-swift | **Skipped** — SwiftUI, not KMP |
| 16 | `expo-skills/plugins/expo/skills/expo-animation/SKILL.md` | expo-animation | **Applied** concepts (Reanimated → Compose `animate*` / `AnimatedVisibility`) |
| 17 | `expo-skills/plugins/expo/skills/expo-design-system/SKILL.md` | expo-design-system | **Applied** lightly — keep tokens centralized (`Motion`, existing `VelumColors`) |
| 18 | `expo-skills/plugins/expo/skills/expo-native-ui/SKILL.md` | expo-native-ui | **Skipped** — SwiftUI / Jetpack View wrappers for Expo |
| 19 | `expo-skills/plugins/expo/skills/eas-app-stores/SKILL.md` | eas-app-stores | Read — store readiness checklist noted; no Expo EAS. Velum already has a11y chrome / dark theme |
| 20 | `expo-skills/plugins/expo/skills/eas-hosting/SKILL.md` | eas-hosting | **Skipped** — Expo hosting |
| 21 | `expo-skills/plugins/expo/skills/eas-observe/SKILL.md` | eas-observe | **Skipped** — Expo observability |
| 22 | `expo-skills/plugins/expo/skills/eas-simulator/SKILL.md` | eas-simulator | **Skipped** — Expo simulator tooling |
| 23 | `expo-skills/plugins/expo/skills/eas-update/SKILL.md` | eas-update | **Skipped** — OTA updates (forbidden for Note Pro / Velum policy) |
| 24 | `expo-skills/plugins/expo/skills/eas-update-insights/SKILL.md` | eas-update-insights | **Skipped** — Expo updates |
| 25 | `expo-skills/plugins/expo/skills/eas-workflows/SKILL.md` | eas-workflows | **Skipped** — Expo CI product |
| 26 | `expo-skills/plugins/expo/skills/expo-app-clip/SKILL.md` | expo-app-clip | **Skipped** — iOS App Clip / Expo |
| 27 | `expo-skills/plugins/expo/skills/expo-brownfield/SKILL.md` | expo-brownfield | **Skipped** — embedding Expo in native hosts |
| 28 | `expo-skills/plugins/expo/skills/expo-data-fetching/SKILL.md` | expo-data-fetching | **Skipped** — Expo Router / React Query patterns |
| 29 | `expo-skills/plugins/expo/skills/expo-dev-client/SKILL.md` | expo-dev-client | **Skipped** — Expo dev client |
| 30 | `expo-skills/plugins/expo/skills/expo-dom/SKILL.md` | expo-dom | **Skipped** — Expo DOM components |
| 31 | `expo-skills/plugins/expo/skills/expo-examples/SKILL.md` | expo-examples | **Skipped** — Expo sample apps |
| 32 | `expo-skills/plugins/expo/skills/expo-module/SKILL.md` | expo-module | **Skipped** — Expo native modules |
| 33 | `expo-skills/plugins/expo/skills/expo-overview/SKILL.md` | expo-overview | **Skipped** — Expo product overview |
| 34 | `expo-skills/plugins/expo/skills/expo-project-structure/SKILL.md` | expo-project-structure | **Skipped** — Expo folder layout |
| 35 | `expo-skills/plugins/expo/skills/expo-router/SKILL.md` | expo-router | **Skipped** — Expo Router; Velum uses Navigation Compose (Plaud parity) |
| 36 | `expo-skills/plugins/expo/skills/expo-ui/SKILL.md` | expo-ui | **Skipped** — Expo UI kit |
| 37 | `expo-skills/plugins/expo/skills/expo-upgrade/SKILL.md` | expo-upgrade | **Skipped** — Expo upgrades |
| 38 | `expo-skills/plugins/expo/skills/expo-web-to-native/SKILL.md` | expo-web-to-native | **Skipped** — web→RN migration |
| 39 | `expo-skills/plugins/expo-experiments/skills/expo-migrate-module/SKILL.md` | expo-migrate-module | **Skipped** — Expo module migration |
| 40 | `expo-skills/.claude/skills/expo-skill-eval/SKILL.md` | expo-skill-eval | **Skipped** — meta skill-eval tooling |
| 41 | `appllama-skills/skills/appllama-app-design-skill/SKILL.md` | appllama-app-design-skill | **Applied** (anti-slop motion, empty composed states, reduce motion, press states) |
| 42 | `appllama-skills/skills/appllama-usage/SKILL.md` | appllama-usage | Read — MCP/study workflow; no Appllama MCP in this agent |

---

## What was applied (file → behavior)

| File | Change |
|------|--------|
| `composeApp/.../ui/motion/Motion.kt` | Ease-out / ease-in-out beziers, duration budgets, `pressableScale`, reduce-motion hooks |
| `composeApp/.../platform/Haptics.kt` (+ android/desktop/ios) | Light / medium / selection haptics; animator-scale / iOS reduce-motion detection |
| `composeApp/.../ui/components/Components.kt` | `BlackButton` / `OutlineButton` / `ChevronRow` press scale + light haptic |
| `composeApp/.../ui/components/Chrome.kt` | Empty-state fade+scale enter; chips / outline actions pressable |
| `composeApp/.../ui/shell/FabOverlay.kt` | Scrim + scale-from-0.96 `AnimatedVisibility` (anchor bottom FAB) |
| `composeApp/.../ui/shell/VelumBottomBar.kt` | Tab tint/scale settle; FAB press + open scale; selection haptics |
| `composeApp/.../App.kt` | `LocalHaptics` provider; NavHost fade enter/exit; FAB overlay always composed with `visible=` |
| `composeApp/.../ui/RecordingsScreen.kt` | Ambient pulse respects reduce-motion; ease-out |
| `composeApp/.../ui/MainScreenshotsTest.kt` | `FabOverlay(visible = true, …)` |

**Not changed (intentionally):** Files/Explore list layouts, recording detail structure, settings hierarchy, template screens — Plaud parity.

---

## Skipped (why), summary

- Entire Expo EAS / Router / module / hosting / OTA surface — wrong platform; OTA also forbidden for Note Pro.
- Web-only mobile-native CSS, Sonner, SwiftUI, HTML prototyping, UI-library picker.
- List stagger / shared-element hero transitions — high frequency paths; skills say restrain; would fight Plaud density.
- Full break-ui worst-case harness — deferred; not required for this motion pass.

---

## Artifacts

- Before screenshots: `/opt/cursor/artifacts/screenshots/before-skills/`
- After screenshots: `/opt/cursor/artifacts/screenshots/velum-*.png` (Roborazzi refresh)
- Debug APK parts: `/opt/cursor/artifacts/apk-parts/`
