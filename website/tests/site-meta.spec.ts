import { test, expect } from '@playwright/test'
import { robotsTxt, siteOrigin, sitemapXml, withAbsoluteMeta } from '../src/siteMeta'

const head = '    <meta property="og:image" content="/brand/social-preview.png" />\n    <meta name="twitter:card" content="summary_large_image" />\n'

test('site origin comes from VITE_SITE_URL, else the Vercel production domain, else nothing', () => {
  expect(siteOrigin({ VITE_SITE_URL: 'https://velum.example/' })).toBe('https://velum.example')
  expect(siteOrigin({ VERCEL_PROJECT_PRODUCTION_URL: 'velum.example' })).toBe('https://velum.example')
  expect(siteOrigin({ VITE_SITE_URL: 'https://a.example', VERCEL_PROJECT_PRODUCTION_URL: 'b.example' })).toBe('https://a.example')
  expect(siteOrigin({})).toBeNull()
  expect(() => siteOrigin({ VITE_SITE_URL: 'http://velum.example' })).toThrow(/https/)
  expect(() => siteOrigin({ VITE_SITE_URL: 'not a url' })).toThrow(/valid/)
})

test('share metadata becomes absolute only when the origin is known', () => {
  expect(withAbsoluteMeta(head, null)).toBe(head)
  const html = withAbsoluteMeta(head, 'https://velum.example')
  expect(html).toContain('content="https://velum.example/brand/social-preview.png"')
  expect(html).toContain('<meta property="og:url" content="https://velum.example/" />')
  expect(html).toContain('<link rel="canonical" href="https://velum.example/" />')
})

test('sitemap and robots.txt', () => {
  const xml = sitemapXml('https://velum.example', ['/', '/docs/privacy'])
  expect(xml).toContain('<loc>https://velum.example/</loc>')
  expect(xml).toContain('<loc>https://velum.example/docs/privacy</loc>')
  expect(robotsTxt(null)).not.toContain('Sitemap')
  expect(robotsTxt('https://velum.example')).toContain('Sitemap: https://velum.example/sitemap.xml')
})
