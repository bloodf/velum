import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'
import { docs } from './src/content'
import { robotsTxt, siteOrigin, sitemapXml, withAbsoluteMeta } from './src/siteMeta'

function siteMeta(): Plugin {
  const origin = siteOrigin(process.env)
  return {
    name: 'velum-site-meta',
    transformIndexHtml: {
      order: 'post',
      handler: (html, ctx) => {
        // Preload the two text faces above the fold, so the first paint already uses them (no late font swap).
        const fonts = Object.keys(ctx.bundle ?? {}).filter((file) => /inter-tight-latin-(400|500)-normal-[\w-]+\.woff2$/.test(file))
        const preloads = fonts.map((file) => `    <link rel="preload" href="/${file}" as="font" type="font/woff2" crossorigin />\n`).join('')
        const withMeta = withAbsoluteMeta(html, origin).replace('    <title>', `${preloads}    <title>`)
        if (!ctx.bundle) return withMeta
        // Built pages are prerendered, so the app script only hydrates them: start it after the first paint, not
        // in parallel with the stylesheet and fonts that the first paint needs.
        return withMeta.replace(
          /<script type="module" crossorigin src="([^"]+)"><\/script>/,
          (_, src) => `<script type="module">requestAnimationFrame(() => requestAnimationFrame(() => import('${src}')))</script>`,
        )
      },
    },
    generateBundle() {
      this.emitFile({ type: 'asset', fileName: 'robots.txt', source: robotsTxt(origin) })
      if (origin) {
        const paths = ['/', ...docs.map((doc) => `/docs/${doc.slug}`)]
        this.emitFile({ type: 'asset', fileName: 'sitemap.xml', source: sitemapXml(origin, paths) })
      }
    },
  }
}

export default defineConfig({
  plugins: [react(), siteMeta()],
  define: {
    __VELUM_VERCEL_ANALYTICS__: JSON.stringify(Boolean(process.env.VERCEL)),
  },
  build: {
    target: 'es2022',
    cssCodeSplit: true,
  },
})
