import { test, expect } from '@playwright/test'

test('desktop marketing, workflow, and devices render correctly', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.goto('/')
  await page.evaluate(() => document.fonts.ready)
  await page.waitForTimeout(1200)
  await expect(page.getByRole('heading', { name: 'Every word has a place' })).toBeVisible()
  await expect(page.locator('canvas').first()).toBeVisible()
  await page.screenshot({ path: 'artifacts/marketing-desktop.png' })

  await page.locator('#workflow').scrollIntoViewIfNeeded()
  await page.evaluate(() => window.scrollBy(0, window.innerHeight * 1.2))
  await page.waitForTimeout(500)
  await page.screenshot({ path: 'artifacts/workflow-desktop.png' })

  await page.locator('#everywhere').scrollIntoViewIfNeeded()
  await page.waitForTimeout(700)
  await expect(page.locator('.desktop-shell')).toBeVisible()
  await expect(page.locator('.phone-shell')).toBeVisible()
  await page.screenshot({ path: 'artifacts/devices-desktop.png' })

  await page.locator('#supported-devices').scrollIntoViewIfNeeded()
  for (const device of ['Plaud', 'Bee', 'Limitless', 'Omi', 'Soundcore Work', 'iFLYTEK', 'External recorders']) {
    await expect(page.getByRole('tab', { name: new RegExp(device, 'i') })).toBeVisible()
  }
  await page.waitForTimeout(500)
  await page.screenshot({ path: 'artifacts/supported-devices.png' })
  expect(errors).toEqual([])
})

test('documentation search and navigation work', async ({ page }) => {
  await page.goto('/docs/introduction')
  await page.evaluate(() => document.fonts.ready)
  await page.waitForTimeout(1200)
  await expect(page.getByRole('heading', { name: 'Meet Velum', level: 1 })).toBeVisible()
  await page.screenshot({ path: 'artifacts/docs-desktop.png' })
  await page.keyboard.press('Control+k')
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.getByRole('textbox', { name: 'Search documentation' }).fill('Plaud')
  await expect(page.locator('.search-result')).toHaveCount(1)
  await page.locator('.search-result').click()
  await expect(page).toHaveURL(/\/docs\/recorders$/)
  await expect(page.getByRole('heading', { name: 'AI notetakers & recorders', level: 1 })).toBeVisible()
})

test('mobile marketing and menu are responsive', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/')
  await page.evaluate(() => document.fonts.ready)
  await page.waitForTimeout(1200)
  await expect(page.getByRole('heading', { name: 'Every word has a place' })).toBeVisible()
  await page.screenshot({ path: 'artifacts/marketing-mobile.png' })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.getByRole('button', { name: 'Open menu' }).click()
  await expect(page.locator('.mobile-menu')).toBeVisible()
  await page.locator('.mobile-menu').getByRole('link', { name: /Documentation/ }).click()
  await expect(page).toHaveURL(/\/docs\/introduction$/)
  await expect(page.getByRole('heading', { name: 'Meet Velum', level: 1 })).toBeVisible()
  await page.screenshot({ path: 'artifacts/docs-mobile.png' })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})
