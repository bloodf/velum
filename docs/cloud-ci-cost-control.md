# Cloud CI and deployment cost control

Product development remains HOLD. This policy changes tooling only. It does not
authorize product work, release builds or deployment while HOLD remains active.

## GitHub Actions

`CI`, `Website` and `Release` require `workflow_dispatch`. Pushes, pull requests
and version tags do not run their development tests, builds, packaging or E2E.
Manual CI still runs JVM unit tests, template validation and Android lint.
Its `extended` input still opts into packaging, screenshots and emulator checks.
Manual runs have timeouts, read-only repository permissions and concurrency per ref.

`Security` retains the existing OSV dependency scan on pushes to main, with
manual dispatch available. Pull requests do not spend hosted minutes. Its scanner commands and exit
handling are unchanged. The existing vulnerability-found outcome is a warning,
not a blocking failure; scanner operational errors still fail. This change does
not strengthen or weaken that existing audit policy.

Run affected validation locally before review. Existing commands include:

```sh
./gradlew :core:validateAutoflowTemplates --stacktrace
./gradlew :plaud-protocol:jvmTest :ble:jvmTest :core:jvmTest :ai:jvmTest :recorder:jvmTest --stacktrace
./gradlew :ai:lintDebug :ble:lintDebug :composeApp:lintDebug --stacktrace
python3 website/scripts/test-vercel-ignore.py
```

Use the affected subset for a change; record the exact revision, command and
result. Website changes require local type checking and applicable website tests
using existing dependencies. Exact-head independent review, security requirements
and branch protections remain required. Local evidence does not create a hosted
status check. No placeholder success checks replace work that did not run.

If remote rules require `JVM unit tests + Android lint`,
`Website build + Playwright tests`, a Vercel deployment, or a workflow-qualified
OSV context, publication can leave those checks pending. The central owner must
reconcile the actual required contexts through a reviewed policy decision, or
obtain genuine validation at the required revision. Do not bypass protections or
claim a skipped check validates the code. Manual workflows must exist on the
default branch before GitHub accepts dispatch.

## Vercel website

The only local Vercel configuration is `website/vercel.json`. The candidate
project root is `website`, package `velum-web`, framework Vite, output `dist`.
No `.vercel/project.json` exists in this checkout. No team ID, project ID or live
root setting is proven by repository files. Additional linked cloud projects
cannot be ruled out from this local inventory.

`git.deploymentEnabled` disables every branch with `"**": false` and enables
only `main`. Vercel documents that a matching true rule wins. This control stops
feature/PR Git deployments before the ignored build step. `ignoreCommand` adds
`sh scripts/vercel-ignore.sh`, relative to the `website` project root.

The dependency-free shell gate uses Git only. Exit 0 cancels a build; exit 1
permits it. It requires main, no PR ID, full deployment SHAs, a checkout matching
the current SHA and a locally available ancestor baseline. It compares the last
successful deployment tree with the current tree, rather than `HEAD^`, so it
handles merge commits, squash merges and accumulated changes across skipped
commits. Disabling rename detection catches both paths of moves and deletions.

Inputs include all of `website`, root JavaScript manifests/locks/workspace and
runtime/package-manager configuration, root Vite/TypeScript/Vercel configuration,
and shared `packages`, `shared`, `patches` and `.yarn` trees. Current Vite config
and package scripts use website-local sources. Add newly introduced external
site inputs to the gate when their dependency is introduced.

Missing, invalid, shallow-unavailable or non-ancestor baselines cancel with a
reason. No network fetch or guessed `HEAD^` comparison occurs. This deliberately
prioritizes no unproven automatic build over freshness. First deployment and
rewritten history need operator review and a deliberate baseline/bootstrap
deployment after authorization. An unchanged or unknown tree is a cancelled
deployment, not successful validation. CLI deployments, deploy hooks and manual
redeployments are outside the Git branch control and require separate live audit.

## Central publication gates

Before any push, the central owner must contain live builds. Branches that still
carry old workflows or old Vercel configuration can still spend. This working
diff does not cancel queued runs or modify live settings.

For every cloud project linked to `bloodf/velum`, verify team/project identity,
root `website`, production branch `main`, configuration file selection and system
Git variables available to the ignored step. Verify the gate sees repository
history and shared files, and check for dashboard command overrides, alternate
environments, hooks, CLI deployments and other roots. The ignored build step can
still create a cancelled deployment record; it does not promise zero platform
overhead. Keep live containment until reviewed configuration is integrated and
historical branch exposure is handled. Required Vercel preview checks must be
resolved without fake success or admin bypass.

## Official references

Read during implementation; snapshots live in the central evidence directory.

- https://vercel.com/docs/project-configuration/git-configuration
- https://vercel.com/docs/project-configuration/vercel-json#ignorecommand
- https://vercel.com/docs/environment-variables/system-environment-variables#vercel_git_previous_sha
- https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow
- https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches
