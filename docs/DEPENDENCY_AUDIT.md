# Dependency audit

Lightweight CVE posture for Velum. No OWASP Dependency-Check Gradle plugin in-repo (review S16). Prefer Renovate (or Dependabot) + occasional OSV queries over a heavy plugin until CI budget allows.

## Strategy (issue #28)

1. **Renovate** — onboarding PR #2 remains the owner merge/close decision; once merged, Renovate owns routine catalog bumps.
2. **Version catalog** — `gradle/libs.versions.toml` is the single pin source for intentional bumps (e.g. SoftAP `java-websocket` 1.6.0).
3. **OSV** — spot-check after bumps; CI `osv-scan` job already exists on `main`. Further OSV CI hardening (fail-on-vuln, workflow edits) is **out of scope for #28 SoftAP** and left for a later change.
4. Skip in-repo OWASP `dependencyCheck` unless the team wants HTML reports in CI artifacts.

## Spot-check

| Date (UTC) | Method | Stack checked | Result |
|------------|--------|---------------|--------|
| 2026-10-26 | Manual [OSV](https://osv.dev) query on pinned versions | Ktor 3.2.3, SQLDelight 2.0.2, WorkManager 2.10, commons-compress 1.28, kable 0.37.1, markdown-renderer 0.38.1 | No high/critical hits reported for those pins |
| 2026-10-28 | Manual OSV query (issue #28) | Ktor 3.2.3, WorkManager 2.10.0, commons-compress 1.28.0, Java-WebSocket 1.5.7 | No known vulns reported for those pins |

Re-run after bumping any of the above, or monthly if Renovate is not yet enabled.

## Gradle note

`settings.gradle.kts` / version catalog remain the single pin source. Do not add OWASP plugin wiring here unless product asks for it.
