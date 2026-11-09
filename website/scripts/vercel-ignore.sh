#!/bin/sh
# Vercel: 0 cancels the build; 1 permits it. Unknown history cancels explicitly.
set -u

skip() {
    printf '%s\n' "Vercel build cancelled: $*"
    exit 0
}

[ "${VERCEL_GIT_COMMIT_REF:-}" = main ] || skip 'only main may deploy'
[ -z "${VERCEL_GIT_PULL_REQUEST_ID:-}" ] || skip 'pull requests may not deploy'

previous=${VERCEL_GIT_PREVIOUS_SHA:-}
current=${VERCEL_GIT_COMMIT_SHA:-}
for sha in "$previous" "$current"; do
    case "$sha" in
        ''|*[!0-9a-fA-F]*) skip 'missing or invalid deployment SHA; baseline needs operator review' ;;
    esac
    [ "${#sha}" -eq 40 ] || skip 'expected full deployment SHA; baseline needs operator review'
done

root=$(git rev-parse --show-toplevel 2>/dev/null) || skip 'Git checkout unavailable'
cd "$root" || skip 'repository root unavailable'
head=$(git rev-parse HEAD 2>/dev/null) || skip 'HEAD unavailable'
[ "$head" = "$current" ] || skip 'deployment SHA does not match checkout'
git cat-file -e "$previous^{commit}" 2>/dev/null || skip 'previous deployment missing locally; no fetch attempted'
git merge-base --is-ancestor "$previous" "$current" 2>/dev/null || skip 'baseline ancestry unproven; review shallow or rewritten history'

# Compare deployment trees, not HEAD^: merges, squash merges and skipped commits all count.
# No rename detection makes both old and new paths visible, including moves out of website.
git diff --quiet --no-ext-diff --no-textconv --no-renames "$previous" "$current" -- \
    website/ packages/ shared/ patches/ .yarn/ \
    package.json package-lock.json npm-shrinkwrap.json pnpm-lock.yaml yarn.lock bun.lock bun.lockb \
    pnpm-workspace.yaml .npmrc .yarnrc.yml .pnpmfile.cjs pnpmfile.cjs \
    .nvmrc .node-version .tool-versions .vercelignore vercel.json turbo.json nx.json \
    ':(top,glob)tsconfig*.json' ':(top,glob)vite.config.*'
result=$?
case "$result" in
    0) skip 'no site or shared dependency/configuration changes since previous deployment' ;;
    1) printf '%s\n' 'Vercel build permitted: main contains changed site inputs'; exit 1 ;;
    *) skip "Git diff failed with exit $result; operator review required" ;;
esac
