import { defineConfig } from '@playwright/test'

/**
 * End-to-end suite. Runs against an already started stack (scripts/e2e.sh starts one); E2E_BASE_URL is the frontend
 * (nginx), which also proxies /api to the backend, so every request takes the same path as a user's browser.
 */
export default defineConfig({
  testDir: './tests',
  // The scenarios share one backend (and its scheduler and execution queue); serial keeps timing assertions honest.
  workers: 1,
  fullyParallel: false,
  timeout: 90_000,
  expect: { timeout: 15_000 },
  retries: 0,
  reporter: [['list'], ['json', { outputFile: 'report.json' }], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://127.0.0.1:25173',
    channel: process.env.E2E_BROWSER_CHANNEL ?? 'chrome',
    headless: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    viewport: { width: 1440, height: 900 },
  },
})
