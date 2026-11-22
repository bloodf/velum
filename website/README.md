# Website development

Use Node 24.22.2 and pnpm 12.18.0. The repository root contains `.nvmrc` and
`.node-version`; `package.json` pins the package manager and Node engine range.

From `website/`:

```sh
pnpm install --frozen-lockfile
pnpm dev
```

`pnpm build` type-checks, builds the client bundle, builds `src/entry-server.tsx` for the server and prerenders
every route (`scripts/prerender.mjs`): `/` into `dist/index.html` and each `/docs/<slug>` into
`dist/docs/<slug>.html` (served at `/docs/<slug>` thanks to `cleanUrls` in `vercel.json`). The client hydrates that
HTML after the first paint. Fonts are self-hosted (`@fontsource`), screenshots are served as WebP
(`scripts/site-screenshot-variants.py` at the repository root), and Vercel Web Analytics is only bundled when the
build runs on Vercel (`VERCEL` set).

Set `VITE_SITE_URL=https://<domain>` (or rely on Vercel's `VERCEL_PROJECT_PRODUCTION_URL`) to get an absolute
`og:image`, `og:url`, a canonical link, `sitemap.xml` and a `Sitemap:` line in `robots.txt`; without it they are left
out rather than guessed.

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

The build makes `og:image` absolute when it knows the production origin (see above).

## Lighthouse

Measured on the production build (brotli-compressed static server) with Lighthouse 12, 2026-11-22, in a sandbox
without a GPU: desktop performance 98-100; mobile performance 73-86 (FCP 1.4-1.7 s, TBT 300-700 ms, CLS 0);
accessibility, best practices and SEO 100 on both. Re-check on the deployed site with PageSpeed Insights: the local
runs are noisy and headless Chrome here renders WebGL in software.
