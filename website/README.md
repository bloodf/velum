# Website development

Use Node 24.22.2 and pnpm 12.18.0. The repository root contains `.nvmrc` and
`.node-version`; `package.json` pins the package manager and Node engine range.

From `website/`:

```sh
pnpm install --frozen-lockfile
pnpm dev
```

Build and run the existing Playwright tests:

```sh
pnpm build
pnpm exec playwright install chromium
pnpm test
```

On Linux, Playwright may also need its system dependencies:
`pnpm exec playwright install --with-deps chromium`.

Commit dependency changes with `pnpm-lock.yaml`.
The workspace policy permits the required esbuild install script; other dependency
build scripts remain subject to pnpm approval.
