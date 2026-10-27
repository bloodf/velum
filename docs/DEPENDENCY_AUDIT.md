# Dependency audit

Lightweight CVE posture for Velum. No OWASP Dependency-Check Gradle plugin in-repo (review S16). Prefer Renovate (or Dependabot) + occasional OSV queries over a heavy plugin until CI budget allows.

## Spot-check

| Date (UTC) | Method | Stack checked | Result |
|------------|--------|---------------|--------|
| 2026-10-26 | Manual [OSV](https://osv.dev) query on pinned versions | Ktor 3.2.3, SQLDelight 2.0.2, WorkManager 2.10, commons-compress 1.28, kable 0.37.1, markdown-renderer 0.38.1 | No high/critical hits reported for those pins |

Re-run after bumping any of the above, or monthly if Renovate is not yet enabled.

## Recommendations

1. Enable Renovate (or GitHub Dependabot) for Gradle version catalogs (onboarding PR #2).
2. ~~Optionally add an OSV-Scanner CI job~~ — landed as `osv-scan` in `.github/workflows/ci.yml` (`fail-on-vuln: false` until Renovate is on).
3. Skip in-repo OWASP `dependencyCheck` unless the team wants HTML reports in CI artifacts.

## Gradle note

`settings.gradle.kts` / version catalog remain the single pin source. Do not add OWASP plugin wiring here unless product asks for it.
