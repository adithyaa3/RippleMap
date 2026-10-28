// Automated accessibility audit: every page, populated with the shop sample, plus open search popups and dialogs,
// in both themes, against the axe-core WCAG 2.0/2.1 level A and AA rules.
// Automated rules find only part of all accessibility problems; see docs/TESTING.md.
import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { importShopSample, samplePages } from '../support/api.mjs';

test('@a11y every page passes the axe WCAG 2.1 A/AA rules in both themes', async ({ page, request }) => {
  test.setTimeout(300_000);
  const ids = await importShopSample(request);
  const pages = samplePages(ids);
  const findings = [];
  let audited = 0;
  async function audit(theme, label) {
    const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
    audited++;
    for (const v of results.violations) {
      findings.push(`${theme} ${label}: ${v.id} (${v.impact}, ${v.nodes.length} element(s), e.g. ${v.nodes[0].target.join(' ')}) ${v.help}`);
    }
  }

  for (const theme of ['dark', 'light']) {
    await page.goto('/');
    await page.evaluate((t) => window.localStorage.setItem('ripplemap.theme', t), theme);
    for (const [label, url] of pages) {
      await page.goto(url);
      await page.waitForLoadState('networkidle');
      await page.waitForTimeout(400);
      await audit(theme, label);
    }

    // states that only exist after interaction
    await page.goto(`/projects/${ids.id}/explorer?analysisId=${ids.v1}`);
    await page.locator('#node-search').fill('Order');
    await expect(page.locator('#search-results [role="option"]').first()).toBeVisible();
    await audit(theme, 'explorer search popup');

    await page.goto(`/projects/${ids.id}/impact`);
    await page.locator('#component-search').fill('Inventory');
    await expect(page.locator('#component-results [role="option"]').first()).toBeVisible();
    await audit(theme, 'impact component popup');

    await page.goto(`/projects/${ids.id}`);
    await page.getByRole('button', { name: 'Edit details' }).click();
    await expect(page.locator('dialog[open]')).toBeVisible();
    await audit(theme, 'edit dialog');
    await page.getByRole('button', { name: 'Cancel' }).click();
    await page.getByRole('button', { name: 'Delete' }).click();
    await expect(page.locator('dialog[open]')).toBeVisible();
    await audit(theme, 'delete dialog');
    await page.getByRole('button', { name: 'Cancel' }).click();
  }
  expect(audited).toBe(2 * (pages.length + 4));
  expect(findings, findings.join('\n')).toEqual([]);
});
