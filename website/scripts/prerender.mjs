// Writes prerendered HTML for every route into dist/ (after `vite build` and the SSR build of src/entry-server.tsx).
// `/` becomes dist/index.html and `/docs/<slug>` dist/docs/<slug>.html, which Vercel serves at /docs/<slug>
// (`cleanUrls` in vercel.json) before its SPA rewrite; `vite preview` resolves the .html extension the same way.
// Run by `pnpm build`.
import { mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const site = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const dist = resolve(site, 'dist')
const ssrDir = resolve(site, 'dist-ssr')
const { render, routes } = await import(pathToFileURL(resolve(ssrDir, 'entry-server.js')).href)

const template = readFileSync(resolve(dist, 'index.html'), 'utf8')
const marker = '<div id="root"></div>'
if (!template.includes(marker)) throw new Error(`dist/index.html has no ${marker}`)

for (const route of routes) {
  const html = render(route)
  if (!html || html.length < 200) throw new Error(`prerender of ${route} produced no markup`)
  const page = template.replace(marker, `<div id="root">${html}</div>`)
  const file = route === '/' ? resolve(dist, 'index.html') : resolve(dist, `.${route}.html`)
  mkdirSync(dirname(file), { recursive: true })
  writeFileSync(file, page)
  console.log(`prerendered ${route} (${Math.round(html.length / 1024)} kB)`)
}
rmSync(ssrDir, { recursive: true, force: true })
