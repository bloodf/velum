# Issue #285 source diagnosis

- Repository: `bloodf/velum`
- Bound source revision: `963663004e44f7a9b3816fc4c247a8053a2f6578`
- Local `main` and `origin/main` resolve to the same revision.
- Inspection is read-only with respect to product source. No build, test, emulator, or process inspection was run.
- Issue body fetched from GitHub on 2026-11-06. It reports historical 10-second wait timeouts and a category/search assertion that relied on `Thread.sleep(1500)` and could pass when the category was already cleared.

## Finding

The reported test weaknesses are already covered on current `main`; no remaining source defect is confirmed from this bounded inspection.

- `composeApp/src/androidUnitTest/kotlin/dev/velum/app/ui/templates/DiscoverCategorySearchUiTest.kt:125-128` counts facets starts and finishes and waits for a reload that began after the recorded baseline, with no request in flight. The category scenario captures the baseline before search at lines 170-177.
- The same test asserts `active:category` before and after the reload, then advances the Compose test clock over six frames while checking the selection remains active (`:171-179`, helper `:130-139`). This rules out the prior false pass where a cleared category made the no-hit state trivially true.
- Row absence is checked by stable lazy-grid key, not by text not being composed (`:58-75`, used at `:168-169`, `:181-182`). It first waits for the intended filtered rows (`:163-168`). Clearing the category then proves the searched row returns while the search remains (`:183-186`).
- No `Thread.sleep(1500)` remains in this category/search test. The reload is observed through the `DiscoverQueries` facets seam (`:109-128`).
- `DiscoverFiltersUiTest.failed_facets_load_keeps_selections` waits for the query log to record the `facets(search='zq')` failure and for all logged queries to leave flight before checking retained category/tag selections (`:253-267`).
- `QueryLog.waitWhileBusy` now allows up to 180 seconds total, fails on 10 seconds of observed query quiet, idles Robolectric's main looper during each poll, and reports whether the timeout was a stall or cap (`QueryLog.kt:68-105`). The old fixed 10-second wall-clock timeout described by issue #285 is gone.
- Source history on current `main` contains issue-specific commits `f7580445c` (wait behavior, query delivery and sharper tests) and `2b5414433` (timing margins and helper guard), plus category/filter synchronization commits `7778372fb` and `18e1babac`. These changes are present in the bound revision.

## Scheduler assumptions and test quality

`QueryLog.logged` records query start/end and hands both successful and failed continuations back to `Dispatchers.Main` (`QueryLog.kt:34-53`). With Robolectric's paused main looper, the test wait explicitly idles that looper while polling (`:83-91`). Facets counters are atomic because they are updated around the async seam (`DiscoverCategorySearchUiTest.kt:109-128`). UI state is then observed through Compose semantics and the test clock, not a wall-clock sleep.

`DiscoverFiltersUiTest` still advances the test clock for a bounded 1,000 ms after a confirmed facets failure (`:261-263`); the user behavior assertion is based on active selection and retained facet options (`:263-267`). This is deterministic test-clock progression, not scheduler waiting. No defect is established by this source inspection.

## Smallest next task and gate

No code change is indicated from the current source. If issue #285 is reopened on fresh CI evidence, rerun only the two affected Android unit test classes and compare any failure to the query log / timeout reason before changing source:

```sh
./gradlew :composeApp:testDebugUnitTest \
  --tests 'dev.velum.app.ui.templates.DiscoverFiltersUiTest' \
  --tests 'dev.velum.app.ui.templates.DiscoverCategorySearchUiTest'
```

Tests were not run as requested. User instruction says issue #323 was already covered; this diagnosis proposes no stale overlapping work.
