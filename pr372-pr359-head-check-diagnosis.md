# PR 372 / 359 exact-head CI diagnosis

Scope: read-only diagnosis. No GitHub writes, reruns, branch edits, source edits, or heavy checks were performed.

## Local workflow evidence

- `.github/workflows/ci.yml:3-7` declares `push` only for `main` and `cursor/**`; PR CI is declared for `pull_request` targeting `main`.
- The PR trigger omits `types`, so GitHub's default `pull_request` activity types apply: `opened`, `synchronize`, and `reopened`. A new commit pushed to a PR head normally emits `synchronize`.
- `pull_request` workflows do not run while the PR has a merge conflict. GitHub documents this under the `pull_request` event.
- The release workflow only listens for `push` tags matching `v*`; it cannot supply PR-head checks.
- `git status --short --branch` was clean at inspection start. Local PR-specific refs point at the same head SHAs GitHub returned.

## GitHub read evidence (2026-11-06)

| PR | State | Head branch / SHA | Base SHA | Mergeable | PR workflow runs | Combined status API |
| --- | --- | --- | --- | --- | --- | --- |
| [#372](https://github.com/bloodf/velum/pull/372) | open | `codex/ptbr-runtime-strings-348` / `0f15c466a6a5af3e2c9126abd4d137555c0217e1` | `41a78f0741685eafb3fa19b8e78ad17480b82fe9` | false | none returned | no statuses returned |
| [#359](https://github.com/bloodf/velum/pull/359) | open | `codex/import-polish-266` / `645c32007a41cb8f00576103ac43834e543e266a` | `59391bf16cb328c8910c2401b23bf92e1526b505` | false | none returned | no statuses returned |

GitHub's PR discussion for #372 says it is superseded by [#402](https://github.com/bloodf/velum/pull/402), which re-cut the same change on current `main` with later review fixes. The #359 discussion says comparison found its changes already present on `main` and recommends closure, pending the owner's decision.

## Diagnosis and smallest safe next action

The repo workflow is configured to run on the normal `pull_request.synchronize` event; no workflow edit or empty commit is needed. The current heads are conflicting, and GitHub suppresses `pull_request` runs for conflicted PRs. Do not try to force a run for these stale PRs. For any still-needed work, use the active/re-cut PR head that is based on current `main`; once conflicts are resolved by the owner through an actual branch update, the resulting new head can receive the normal synchronize-triggered CI. If the branch update is made by a workflow using `GITHUB_TOKEN`, GitHub may place the resulting run in approval-required state; a GitHub App installation token or PAT avoids that approval prompt. The credential used for either PR's last update was not observable in this read-only evidence, so token suppression/approval is not asserted as the cause here.

PR-specific next actions:

- #372: direct remaining review/validation to #402's current exact head; do not make a new commit to #372 merely to start CI.
- #359: no remaining feature appears unique per its PR discussion. Leave closure to its owner; do not update the branch to provoke CI.

## Limits

No tests or checks were run locally. The Actions run lookup returned an empty result set for each SHA, and the combined-status lookup returned no status rows; this establishes only what those read APIs returned at inspection time.
