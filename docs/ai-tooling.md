# Agent tooling

Product development remains HOLD until the user explicitly resumes it. Tooling completion, passing CI and background events do not authorize product work or product merges. Keep exact-head independent review, applicable validation and required remote protections.

## CodeGraph 1.6.2

Use the standalone CLI installed by the workstation coordinator, outside application dependencies. `.codegraph-version` pins 1.6.2; `sh scripts/codegraph.sh` refuses missing or mismatched versions and disables telemetry. Do not upgrade or change global harness settings during repository work.

From this worktree:

```sh
sh scripts/codegraph.sh init --yes
sh scripts/codegraph.sh sync
sh scripts/codegraph.sh status --json
sh scripts/codegraph.sh query 'MicRecordingController' --limit 5 --json
sh scripts/codegraph.sh explore --max-files 3 'MicRecordingController'
```

Query narrowly before broad source reads. Confirm relevant edges against source; a graph is not runtime proof. Use bounded `rg` for exact text, unsupported languages or missing symbols. Inspect actual language coverage: this Kotlin Multiplatform repository also contains TypeScript, Swift and native code, and an index must not imply full Kotlin coverage.

Each worktree owns its index. Never copy databases across worktrees or commit databases, logs, sockets or daemon state. Only `.codegraph/.gitignore` is tracked. CLI usage requires explicit `sync` after checkout or edits. Re-run `index` after changing local include/exclude/extension rules. Version 1.6.2 has no `--strict` flag. MCP watches changes only while active; keep the default single `codegraph_explore` tool where centrally configured. Otherwise use the same CLI manually, including Grok and MiniMax.

## RTK 1.0.1, Caveman and Ponytail

Honor `~/.codex/RTK.md`: where hooks already condense output, run commands normally. Use `rtk bypass <cmd>` only for unusable output, not merely shortened output. Where no hook exists, use supported explicit RTK commands. Never compress the same boundary twice.

Use installed Caveman instructions for concise, readable reports without losing negations, numbers, evidence or blockers. Use installed Ponytail instructions to reuse existing helpers and standard libraries and make the smallest sufficient change. If skills are unavailable in a harness, follow these principles manually; do not claim native skill or hook support. Do not duplicate complete skills here or weaken validation to save tokens.

## TokenEconomizer and model routing

TokenEconomizer global installation and harness settings belong to the central coordinator. Where supported, it provides repository learning, tool pruning and loop guidance; it is not a generic output compressor. Unsupported harnesses use manual guidance, not invented integration. Keep required review, orchestration and device tools available; learned advice cannot override user policy. No local token or cost savings have been measured.

Antigravity uses GOOGLE MODELS ONLY. Argon stays parked while quota-exhausted. Reserve capacity with the central coordinator before launching: at most one worker per account across projects during rollout. Round robin only among eligible accounts; unknown quota stays unknown. Park exhausted or unavailable routes without blind retries or budget resets.

MiniMax M3.1 Flash requires the exact runtime-validated target `m:minimax:MiniMax-M3.1-Flash:v:thinking`. The empty variant advertised by T3 fails `Invalid model reasoning` because the runtime requires `FORCED_ON`; do not use or claim a nonthinking route. The earlier stale Preview default was a separate model/route failure, not quota evidence. Unknown quota stays unknown. Keep mechanical tasks bounded and retain risk-appropriate models and independent review gates. Children receive explicit ownership and no subdelegation unless authorized.

Before any tooling worker launch, reserve with `t3-model-route reserve --project velum --purpose tooling --task '<bounded task>'`. Launch only the returned exact target and options, record task/run and release only that completed lease with evidence. If no route is eligible, retain HOLD. The dispatcher coordinates cooperating orchestrators; it does not intercept manual T3/UI launches. Global authority and limits are in `/Users/heitor/.config/ai-tooling/COORDINATION.md`. Routine coordinators use Codex `gpt-6.1-sol`, `reasoningEffort=high`, `serviceTier=default`; do not restart running turns to change models.

## Delivery and propagation

Keep tooling out of application dependencies. Validate the real index, a bounded query/explore, a source-checked cross-file edge, version rejection and tracked-artifact exclusion. Preserve independent exact-head review and mandatory repository checks. Only the dedicated reviewed tooling PR may merge under the rollout grant.

After merge, propagate only tooling payloads and additive guide pointers to every local/origin branch, including historical branches without PRs. Back up original refs and dirty states outside the repository; append commits without force pushes or product-history merges. Preserve pre-existing instruction and ignore files. Keep receipts with old/new heads and ancestry checks; explicit blockers remain open. Global configuration is never part of these commits.
