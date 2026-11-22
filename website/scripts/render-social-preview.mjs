// Renders the 1280x640 GitHub / Open Graph social preview (#25) from the brand assets and real app screenshots.
//   node scripts/render-social-preview.mjs            (from website/, after pnpm install)
// Writes public/brand/social-preview.png (used by index.html) and ../docs/brand/sized/github-social-preview-1280x640.png
// (the file the owner uploads in repo Settings -> General -> Social preview).
import { chromium } from '@playwright/test'
import { copyFileSync, readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const site = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const dataUrl = (path, type) => `data:${type};base64,${readFileSync(resolve(site, path)).toString('base64')}`
const desktop = dataUrl('public/screenshots/03-desktop-transcript.png', 'image/png')
const note = dataUrl('public/screenshots/07b-mobile-note.png', 'image/png')
const mark = readFileSync(resolve(site, 'public/brand/velum-v.svg'), 'utf8')
// The site's own self-hosted fonts (@fontsource), so the image matches the website's typography.
const font = (pkg, file) => dataUrl(`node_modules/@fontsource/${pkg}/files/${file}`, 'font/woff2')
const fontFaces = [
  ['Inter Tight', 'normal', 500, font('inter-tight', 'inter-tight-latin-500-normal.woff2')],
  ['Inter Tight', 'normal', 400, font('inter-tight', 'inter-tight-latin-400-normal.woff2')],
  ['Instrument Serif', 'italic', 400, font('instrument-serif', 'instrument-serif-latin-400-italic.woff2')],
  ['IBM Plex Mono', 'normal', 500, font('ibm-plex-mono', 'ibm-plex-mono-latin-500-normal.woff2')],
].map(([family, style, weight, src]) => `@font-face { font-family: '${family}'; font-style: ${style}; font-weight: ${weight}; src: url(${src}) format('woff2'); }`).join('\n')

const html = `<!doctype html><html><head><meta charset="utf-8"><style>
  ${fontFaces}
  * { box-sizing: border-box; margin: 0; }
  body { width: 1280px; height: 640px; overflow: hidden; font-family: 'Inter Tight', 'Helvetica Neue', Arial, sans-serif; color: #151515;
    background: radial-gradient(ellipse 70% 60% at 10% 0%, #ffffff, transparent 70%), radial-gradient(ellipse 60% 60% at 95% 90%, #d9d9d4, transparent 70%), #efefeb; }
  .grid { position: absolute; inset: 0; background-image: linear-gradient(rgba(17,17,17,.045) 1px, transparent 1px), linear-gradient(90deg, rgba(17,17,17,.045) 1px, transparent 1px); background-size: 64px 64px; }
  .copy { position: absolute; left: 72px; top: 76px; width: 520px; }
  .brand { display: flex; align-items: center; gap: 16px; font-size: 46px; font-weight: 500; letter-spacing: -.05em; }
  .brand svg { width: 52px; height: 46px; }
  h1 { margin-top: 58px; font-size: 64px; line-height: .98; letter-spacing: -.055em; font-weight: 500; }
  h1 span { display: block; font-family: 'Instrument Serif', Georgia, serif; font-style: italic; font-weight: 400; letter-spacing: -.03em; }
  p { margin-top: 28px; font-size: 22px; line-height: 1.4; color: #4d4d48; max-width: 470px; }
  .tags { position: absolute; left: 72px; bottom: 62px; display: flex; gap: 10px; font-family: 'IBM Plex Mono', ui-monospace, monospace; font-size: 14px; letter-spacing: .08em; }
  .tags span { padding: 8px 14px; border-radius: 999px; border: 1px solid rgba(17,17,17,.25); background: rgba(255,255,255,.5); }
  .desktop { position: absolute; left: 620px; top: 92px; width: 700px; border-radius: 18px; overflow: hidden; box-shadow: 0 40px 80px rgba(40,40,36,.22), 0 0 0 1px rgba(17,17,17,.12); background: #fff; }
  .desktop .bar { height: 30px; background: #f6f6f3; border-bottom: 1px solid #e2e2dd; display: flex; gap: 8px; align-items: center; padding-left: 14px; }
  .desktop .bar i { width: 10px; height: 10px; border-radius: 50%; background: #d0d0ca; }
  .desktop img { display: block; width: 100%; }
  .phone { position: absolute; left: 560px; top: 196px; width: 214px; height: 470px; border-radius: 34px; background: #161616; padding: 9px; box-shadow: 0 35px 70px rgba(30,30,28,.32); }
  .phone img { width: 100%; height: 100%; object-fit: cover; object-position: top; border-radius: 26px; display: block; }
</style></head><body>
  <div class="grid"></div>
  <div class="desktop"><div class="bar"><i></i><i></i><i></i></div><img src="${desktop}"></div>
  <div class="phone"><img src="${note}"></div>
  <div class="copy">
    <div class="brand">${mark}<span>Velum</span></div>
    <h1>Every word<span>has a place.</span></h1>
    <p>Recordings, transcripts and AI notes. Local-first, with your choice of AI.</p>
  </div>
  <div class="tags"><span>OPEN SOURCE</span><span>ANDROID FIRST</span><span>NO TELEMETRY</span></div>
</body></html>`

const browser = await chromium.launch()
try {
  const page = await browser.newPage({ viewport: { width: 1280, height: 640 }, deviceScaleFactor: 1 })
  await page.setContent(html, { waitUntil: 'load' })
  await page.evaluate(() => document.fonts.ready)
  const out = resolve(site, 'public/brand/social-preview.png')
  await page.screenshot({ path: out, type: 'png' })
  copyFileSync(out, resolve(site, '../docs/brand/sized/github-social-preview-1280x640.png'))
  console.log(`wrote ${out}`)
} finally {
  await browser.close()
}
