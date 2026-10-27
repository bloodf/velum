import { test, expect } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

const viewports = [
  { width: 360, height: 800 },
  { width: 768, height: 1024 },
  { width: 1024, height: 768 },
  { width: 1440, height: 1000 },
  { width: 1920, height: 1080 },
]

test('marketing layout and images survive the viewport matrix', async ({ page }) => {
  const runtimeErrors: string[] = []
  page.on('pageerror', (error) => runtimeErrors.push(error.message))
  for (const viewport of viewports) {
    await page.setViewportSize(viewport)
    await page.goto('/')
    await page.waitForTimeout(350)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), `horizontal overflow at ${viewport.width}px`).toBe(true)
    const sections = await page.locator('main > section').count()
    for (let i = 0; i < sections; i++) {
      await page.locator('main > section').nth(i).scrollIntoViewIfNeeded()
      await page.waitForTimeout(100)
    }
    const brokenImages = await page.locator('img').evaluateAll((images) => images.filter((image) => !(image as HTMLImageElement).complete || (image as HTMLImageElement).naturalWidth === 0).map((image) => (image as HTMLImageElement).src))
    expect(brokenImages, `broken images at ${viewport.width}px`).toEqual([])
    const ctaBounds = await page.locator('.final-cta h2 span').boundingBox()
    expect(ctaBounds && ctaBounds.x >= 0 && ctaBounds.x + ctaBounds.width <= viewport.width, `clipped CTA at ${viewport.width}px`).toBe(true)
    await page.screenshot({ path: `artifacts/audit-${viewport.width}.png` })
  }
  expect(runtimeErrors).toEqual([])
})

test('marketing accessibility passes WCAG AA checks', async ({ page }) => {
  await page.goto('/')
  await page.waitForTimeout(1200)
  const sections = await page.locator('main > section').count()
  for (let i = 0; i < sections; i++) {
    await page.locator('main > section').nth(i).scrollIntoViewIfNeeded()
    await page.waitForTimeout(120)
  }
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze()
  expect(results.violations).toEqual([])
})

test('mobile menu accessibility passes WCAG AA checks', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/')
  await page.getByRole('button', { name: 'Open menu' }).click()
  await page.waitForTimeout(350)
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze()
  expect(results.violations).toEqual([])
})

test('documentation accessibility passes WCAG AA checks', async ({ page }) => {
  await page.goto('/docs/introduction')
  await page.waitForTimeout(500)
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze()
  expect(results.violations).toEqual([])
})

test('documentation search accessibility passes WCAG AA checks', async ({ page }) => {
  await page.goto('/docs/introduction')
  await page.keyboard.press('Control+k')
  await page.getByRole('searchbox', { name: 'Search documentation' }).fill('recorder')
  await page.waitForTimeout(250)
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze()
  expect(results.violations).toEqual([])
})

test('reduced motion stops rotating content', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.goto('/')
  const thought = page.locator('.rotating-thought')
  await expect(thought).toHaveText('a decision')
  await page.waitForTimeout(3100)
  await expect(thought).toHaveText('a decision')
})
