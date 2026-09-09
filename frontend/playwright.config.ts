import { defineConfig, devices } from '@playwright/test';

const production = process.env.PLAYWRIGHT_PRODUCTION === 'true';
const localURL = `http://127.0.0.1:${production ? 5174 : 5173}`;
const externalURL = process.env.PLAYWRIGHT_BASE_URL;

export default defineConfig({
  testDir: './e2e',
  timeout: 45000,
  expect: { timeout: 15000 },
  fullyParallel: false,
  workers: 1,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: externalURL || localURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    channel:
      process.env.PLAYWRIGHT_CHANNEL ||
      (process.platform === 'win32' ? 'chrome' : undefined),
  },
  projects: [
    {
      name: 'desktop',
      use: {
        ...devices['Desktop Chrome'],
        viewport: { width: 1440, height: 960 },
      },
    },
    {
      name: 'mobile',
      use: {
        ...devices['Desktop Chrome'],
        viewport: { width: 390, height: 844 },
        isMobile: true,
        hasTouch: true,
      },
    },
  ],
  webServer: externalURL
    ? undefined
    : {
        command: production ? 'pnpm preview --port 5174' : 'pnpm dev',
        url: localURL,
        reuseExistingServer: !process.env.CI,
        timeout: 120000,
      },
});
