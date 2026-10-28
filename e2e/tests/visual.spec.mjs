// Captures every page at desktop, tablet and phone widths for visual inspection (written to e2e/screenshots).
// Also fails on horizontal overflow, console errors and failed requests.
import { test, expect } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { importShopSample, samplePages } from '../support/api.mjs';

const widths = [1440, 768, 390];
const outDir = path.resolve('screenshots');

test('@screenshots all pages at three widths', async ({ page, request }) => {
  test.setTimeout(600_000);
  fs.mkdirSync(outDir, { recursive: true });
  const problems = [];
  page.on('console', (m) => { if (m.type() === 'error' || m.type() === 'warning') problems.push(`${m.text()} @ ${m.location().url}`); });
  page.on('pageerror', (e) => problems.push(`[pageerror] ${e.message}`));

  const ids = await importShopSample(request);
  const { id, v1 } = ids;
  const pages = samplePages(ids);
  for (const width of widths) {
    await page.setViewportSize({ width, height: width < 600 ? 844 : 900 });
    for (const [label, url] of pages) {
      await page.goto(url);
      await page.waitForLoadState('networkidle');
      await page.waitForTimeout(600);
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
      expect(overflow, `horizontal overflow on ${label} at ${width}px`).toBeLessThanOrEqual(0);
      await page.screenshot({ path: path.join(outDir, `${label}-${width}.png`), fullPage: label !== 'explorer' && label !== 'explorer-selected' });
    }
  }
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.emulateMedia({ colorScheme: 'light' });
  await page.goto('/settings');
  await page.getByLabel('Light', { exact: true }).check();
  for (const [label, url] of [['dashboard-light', '/'], ['explorer-light', `/projects/${id}/explorer?analysisId=${v1}`], ['impact-light', `/projects/${id}/impact?node=com.example.shop.domain.Order`]]) {
    await page.goto(url);
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(600);
    await page.screenshot({ path: path.join(outDir, `${label}-1440.png`) });
  }
  await page.goto('/settings');
  await page.getByLabel('Dark', { exact: true }).check();
  const unexpected = problems.filter((p) => !p.includes('999999'));
  expect(unexpected, unexpected.join(' | ')).toEqual([]);
});
