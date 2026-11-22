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

## Social preview image

`public/brand/social-preview.png` (the `og:image`) and `docs/brand/sized/github-social-preview-1280x640.png` (upload it
in GitHub → Settings → General → Social preview) are rendered from the app screenshots:

```sh
node scripts/render-social-preview.mjs
```

`og:image` is a relative path until the production domain is fixed; most link-preview crawlers need an absolute URL,
so set it to `https://<domain>/brand/social-preview.png` when the domain is known.
