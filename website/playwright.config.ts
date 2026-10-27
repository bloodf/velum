import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './tests',
  timeout: 45_000,
  fullyParallel: false,
  workers: 1,
  use: {
    baseURL: 'http://127.0.0.1:7100',
    headless: true,
    viewport: { width: 1440, height: 1000 },
    deviceScaleFactor: 1,
  },
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 7100',
    url: 'http://127.0.0.1:7100',
    reuseExistingServer: false,
    timeout: 30_000,
  },
})
