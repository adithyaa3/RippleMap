// Playwright configuration for RippleMap browser tests.
// The tests start the packaged application (target/ripplemap-1.0.0.jar) with a fresh data directory.
// Build first: ./mvnw package -DskipTests   Then: cd e2e && npx playwright test
import { defineConfig } from '@playwright/test';
import os from 'node:os';
import path from 'node:path';
import fs from 'node:fs';

const port = Number(process.env.RIPPLEMAP_E2E_PORT || 18181);
const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'ripplemap-e2e-'));
const java = process.env.RIPPLEMAP_JAVA || (process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', 'java') : 'java');
const jar = path.resolve('..', 'target', 'ripplemap-1.0.0.jar');

export default defineConfig({
  testDir: './tests',
  timeout: 120_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],
  use: {
    baseURL: `http://127.0.0.1:${port}`,
    // Use an installed browser when no Playwright browser is downloaded: RIPPLEMAP_E2E_CHANNEL=msedge or chrome.
    channel: process.env.RIPPLEMAP_E2E_CHANNEL || undefined,
    viewport: { width: 1440, height: 900 },
    colorScheme: 'dark',
    acceptDownloads: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure'
  },
  webServer: {
    command: `"${java}" -jar "${jar}" --server.port=${port} --ripplemap.data-dir="${dataDir}"`,
    url: `http://127.0.0.1:${port}/actuator/health`,
    timeout: 180_000,
    reuseExistingServer: false,
    stdout: 'ignore',
    stderr: 'pipe'
  }
});
