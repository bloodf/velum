// Build-time site metadata: absolute share URLs, canonical link, sitemap and robots.txt.
// The production origin comes from VITE_SITE_URL, or on Vercel from VERCEL_PROJECT_PRODUCTION_URL (a system variable
// exposed to builds). Without either, the page keeps relative URLs and no sitemap is emitted: nothing is guessed.

export function siteOrigin(env: Record<string, string | undefined>): string | null {
  const raw = env.VITE_SITE_URL?.trim() || (env.VERCEL_PROJECT_PRODUCTION_URL?.trim() ? `https://${env.VERCEL_PROJECT_PRODUCTION_URL.trim()}` : '')
  if (!raw) return null
  let url: URL
  try {
    url = new URL(raw)
  } catch {
    throw new Error(`Site URL "${raw}" is not a valid absolute URL`)
  }
  if (url.protocol !== 'https:' && url.hostname !== 'localhost') throw new Error(`Site URL "${raw}" must use https`)
  return url.origin
}

/** Makes og:image absolute and adds og:url + canonical. Link-preview crawlers ignore relative og:image URLs. */
export function withAbsoluteMeta(html: string, origin: string | null): string {
  if (!origin) return html
  const out = html.replace(/(<meta property="og:image" content=")(\/[^"]*)(")/, `$1${origin}$2$3`)
  const extra = `    <meta property="og:url" content="${origin}/" />\n    <link rel="canonical" href="${origin}/" />\n`
  return out.replace('    <meta name="twitter:card"', `${extra}    <meta name="twitter:card"`)
}

export function sitemapXml(origin: string, paths: string[]): string {
  const urls = paths.map((p) => `  <url><loc>${origin}${p}</loc></url>`).join('\n')
  return `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${urls}\n</urlset>\n`
}

export function robotsTxt(origin: string | null): string {
  return `User-agent: *\nAllow: /\n${origin ? `\nSitemap: ${origin}/sitemap.xml\n` : ''}`
}
