// End-to-end browser tests of the main RippleMap workflows against the real application.
import { test as base, expect } from '@playwright/test';
import fs from 'node:fs';
import { zipFixture } from '../support/zip.mjs';
import { apiImport } from '../support/api.mjs';

let allowed = [];

/** Every test fails on unexpected console errors, page errors or failed HTTP requests. */
const test = base.extend({
  page: async ({ page }, use) => {
    const problems = [];
    allowed = [];
    page.on('console', (m) => { if (m.type() === 'error' || m.type() === 'warning') problems.push(`[console ${m.type()}] ${m.text()} @ ${m.location().url || page.url()}`); });
    page.on('pageerror', (e) => problems.push(`[pageerror] ${e.message} @ ${page.url()}`));
    page.on('requestfailed', (r) => { if (!r.failure()?.errorText.includes('ERR_ABORTED')) problems.push(`[requestfailed] ${r.url()} ${r.failure()?.errorText}`); });
    page.on('response', (r) => { if (r.status() >= 400) problems.push(`[http ${r.status()}] ${r.request().method()} ${r.url()}`); });
    await use(page);
    const unexpected = problems.filter((p) => !allowed.some((a) => p.includes(a)));
    expect(unexpected, unexpected.join(' | ')).toEqual([]);
  }
});

const unique = (name) => `${name} ${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`;
const state = {};

test.beforeAll(async ({ playwright }) => {
  const request = await playwright.request.newContext();
  state.chain = await apiImport(request, unique('Chain'), 'chain');
  state.cycle = await apiImport(request, unique('Cycle'), 'cycle');
  state.versions = await apiImport(request, unique('Versions'), 'versions/v1');
  state.disposable = await apiImport(request, unique('Disposable'), 'diamond');
  await request.dispose();
});

async function importViaUi(page, name, fixture) {
  await page.goto('/import');
  await page.locator('#zip-form').getByLabel('Project name').fill(name);
  await page.locator('#zip-file').setInputFiles({ name: `${fixture.replace('/', '-')}.zip`, mimeType: 'application/zip', buffer: zipFixture(fixture) });
  await expect(page.locator('#zip-file-name')).toContainText('.zip');
  await page.getByRole('button', { name: 'Import and analyze' }).click();
  await expect(page.locator('#progress-title')).toHaveText(/Analysis completed|finished with partial results/, { timeout: 60_000 });
  const href = await page.getByRole('link', { name: 'Open project overview' }).getAttribute('href');
  return Number(href.split('/').pop());
}

test('dashboard, navigation and empty states render without errors', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Dashboard', level: 1 })).toBeVisible();
  await expect(page.getByText(/Welcome to RippleMap|Totals cover/)).toBeVisible();
  for (const [link, heading] of [['Projects', 'Projects'], ['Import Project', 'Import Project'], ['Settings & About', 'Settings & About'], ['Dashboard', 'Dashboard']]) {
    await page.locator('#sidebar').getByRole('link', { name: link, exact: true }).click();
    await expect(page.getByRole('heading', { name: heading, level: 1 })).toBeVisible();
  }
  await page.goto('/projects/987654');
  await expect(page.getByRole('heading', { name: /404/ })).toBeVisible();
  allowed.push('/projects/987654');
});

test('import validates input and rejects invalid archives', async ({ page }) => {
  await page.goto('/import');
  await page.getByRole('button', { name: 'Import and analyze' }).click();
  await expect(page.locator('#zip-form [data-field="name"] .error')).toHaveText('Enter a project name');
  await page.locator('#zip-form').getByLabel('Project name').fill(unique('Broken upload'));
  await page.getByRole('button', { name: 'Import and analyze' }).click();
  await expect(page.locator('#zip-form [data-field="file"] .error')).toHaveText('Choose a ZIP archive');
  await page.locator('#zip-file').setInputFiles({ name: 'notes.zip', mimeType: 'application/zip', buffer: Buffer.from('definitely not a zip') });
  await page.getByRole('button', { name: 'Import and analyze' }).click();
  await expect(page.locator('#zip-form [data-field="file"] .error')).toHaveText('The file is not a valid ZIP archive');
  allowed.push('/api/projects/import');
});

test('import a project through the UI and inspect its overview', async ({ page }) => {
  const name = unique('Imported');
  await importViaUi(page, name, 'chain');
  await page.getByRole('link', { name: 'Open project overview' }).click();
  await expect(page.getByRole('heading', { name })).toBeVisible();
  await expect(page.getByRole('heading', { name: /^Snapshot #1/ })).toBeVisible();
  const types = page.locator('.stat', { hasText: 'Types' }).first();
  await expect(types.locator('.stat-value')).toHaveText('3');
  await expect(page.locator('.stat', { hasText: 'Type dependencies' }).locator('.stat-value')).toHaveText('2');
  await expect(page.getByRole('link', { name: 'Repository' })).toBeVisible();
  await expect(page.getByText('No diagnostics')).toBeVisible();
});

test('dependency explorer: search, select, details, filters and controls', async ({ page }) => {
  await page.goto(`/projects/${state.chain}/explorer`);
  await expect(page.locator('#graph-summary')).toContainText('3 of 3 types');
  await expect(page.locator('#cy canvas').first()).toBeVisible();
  await page.getByLabel('Search components').fill('Repos');
  await page.getByRole('option', { name: /Repository/ }).click();
  const details = page.locator('#details-panel');
  await expect(details.locator('.details-title')).toHaveText('Repository');
  await expect(details.getByText('com.acme.chain.Repository', { exact: true })).toBeVisible();
  const dependents = details.locator('.details-section').filter({ has: page.locator('h3', { hasText: 'Dependents' }) });
  await expect(dependents.getByRole('button', { name: /Service/ })).toBeVisible();
  await dependents.getByRole('button', { name: /Service/ }).click();
  await expect(details.locator('.details-title')).toHaveText('Service');
  await expect(page).toHaveURL(/focus=com\.acme\.chain\.Service/);

  await details.getByRole('button', { name: 'Dependents', exact: true }).click();
  await expect(page.locator('#graph-summary')).toContainText('2 of 3 types');
  await page.locator('#scope-select').selectOption('ALL');
  await expect(page.locator('#graph-summary')).toContainText('3 of 3 types');

  await page.getByRole('button', { name: 'Filters' }).click();
  await page.getByRole('button', { name: 'None', exact: true }).click();
  await page.getByLabel('object creation').check();
  await page.getByRole('button', { name: 'Apply', exact: true }).click();
  await expect(page.locator('#graph-summary')).toContainText('1 dependencies shown');
  await expect(page.locator('#filter-count')).toHaveText('1');
  await page.getByRole('button', { name: 'Filters' }).click();
  await page.getByRole('button', { name: 'Reset', exact: true }).click();
  await expect(page.locator('#graph-summary')).toContainText('2 dependencies shown');

  for (const name of ['Zoom in', 'Zoom out', 'Fit graph to view', 'Reset layout']) {
    await page.getByRole('button', { name }).click();
  }
  await page.locator('#layout-select').selectOption('hierarchy');
  await expect(page).toHaveURL(/layout=hierarchy/);

  await page.getByRole('button', { name: 'Methods' }).click();
  await expect(page.locator('#graph-summary')).toContainText('4 of 4 methods');
});

test('impact analysis computes the golden score and prevents stale results', async ({ page }) => {
  await page.goto(`/projects/${state.chain}/impact`);
  await page.getByLabel('Component to change').fill('Repository');
  await page.getByRole('option', { name: /Repository/ }).click();
  await page.getByRole('button', { name: 'Analyze impact' }).click();
  const result = page.locator('#impact-result');
  await expect(result.locator('.gauge-label b')).toHaveText('68.5');
  await expect(result.locator('.badge-impact-HIGH').first()).toBeVisible();
  await expect(result.getByText('does not necessarily mean the proposed change will break')).toBeVisible();
  const rows = result.locator('table').last().locator('tbody tr');
  await expect(rows).toHaveCount(2);
  await expect(rows.nth(1)).toContainText('Controller');
  await expect(rows.nth(1).locator('.chain')).toHaveAttribute('aria-label', 'Controller depends on Service depends on Repository');
  await expect(result.locator('#impact-graph canvas').first()).toBeVisible();

  const [download] = await Promise.all([page.waitForEvent('download'), result.getByRole('link', { name: 'HTML' }).click()]);
  expect(download.suggestedFilename()).toMatch(/^ripplemap-.*-s1-impact-repository\.html$/);
  const html = fs.readFileSync(await download.path(), 'utf8');
  expect(html).toContain('68.5');
  expect(html).not.toContain('<script');

  // changing the question removes the previous answer
  await page.getByRole('button', { name: 'Change' }).click();
  await expect(result.locator('.gauge')).toHaveCount(0);
  await expect(result).toContainText('Choose a component');
  await page.getByLabel('Component to change').fill('Controller');
  await page.getByRole('option', { name: /Controller/ }).click();
  await page.getByRole('button', { name: 'Analyze impact' }).click();
  await expect(result.locator('.gauge-label b')).toHaveText('0.0');
  await expect(result).toContainText('Nothing depends on this component');
});

test('circular dependencies are listed with a representative cycle', async ({ page }) => {
  await page.goto(`/projects/${state.cycle}/cycles`);
  await expect(page.locator('#cycles-count')).toContainText('1 component(s) · 3 types involved');
  await expect(page.getByRole('button', { name: /A → B → C → A/ })).toHaveAttribute('aria-current', 'true');
  await expect(page.locator('#cycle-detail')).toContainText('Representative cycle');
  await expect(page.locator('#cycle-detail .cy canvas').first()).toBeVisible();
});

test('new version, history and snapshot comparison', async ({ page }) => {
  const id = state.versions;
  await page.goto(`/projects/${id}/history`);
  await page.getByRole('button', { name: 'Upload new version' }).click();
  const dialog = page.getByRole('dialog', { name: 'Upload new version' });
  await dialog.getByLabel('ZIP archive').setInputFiles({ name: 'v2.zip', mimeType: 'application/zip', buffer: zipFixture('versions/v2') });
  await dialog.getByLabel(/Version label/).fill('second');
  await dialog.getByRole('button', { name: 'Upload and analyze' }).click();
  await expect(dialog).toBeHidden();
  const rows = page.locator('#history-table tbody tr');
  await expect(rows).toHaveCount(2);
  await expect(rows.first()).toContainText('Completed', { timeout: 60_000 });
  await rows.first().getByRole('link', { name: /Compare with #1/ }).click();
  const result = page.locator('#compare-result');
  await expect(result).toContainText('Snapshot #1 → #2');
  await expect(result.locator('.card', { hasText: 'Added types' }).first()).toContainText('Delta');
  await expect(result.locator('.card', { hasText: 'Removed types' }).first()).toContainText('Legacy');
  await expect(result.locator('.card', { hasText: 'Added dependencies' }).first()).toContainText('Alpha');

  // historical snapshot selection drives every page
  await page.goto(`/projects/${id}/explorer`);
  const first = await page.locator('#snapshot-select option', { hasText: '#1 ·' }).getAttribute('value');
  await page.locator('#snapshot-select').selectOption(first);
  await expect(page.locator('#graph-summary')).toContainText('Historical snapshot #1');
  await page.getByLabel('Search components').fill('Legacy');
  await expect(page.getByRole('option', { name: /Legacy/ })).toBeVisible();
});

test('reports download real content for the selected snapshot', async ({ page }) => {
  await page.goto(`/projects/${state.chain}/reports`);
  const items = page.locator('#report-list li');
  await expect(items).toHaveCount(7);
  for (let i = 0; i < 7; i++) {
    const [download] = await Promise.all([page.waitForEvent('download'), items.nth(i).getByRole('link', { name: /Download/ }).click()]);
    const name = download.suggestedFilename();
    expect(name).toMatch(/^ripplemap-chain-[a-z0-9-]+-s1-[a-z-]+\.(csv|html|json)$/);
    const content = fs.readFileSync(await download.path(), 'utf8');
    expect(content.length).toBeGreaterThan(50);
    if (name.endsWith('summary.csv')) expect(content).toContain('Declarations,Types,3');
    if (name.endsWith('analysis.json')) expect(JSON.parse(content).analysis.sequenceNumber).toBe(1);
  }
});

test('settings validate thresholds, save, and switch the theme', async ({ page }) => {
  await page.goto('/settings');
  await page.getByLabel('Low / medium threshold').fill('70');
  await page.getByLabel('Medium / high threshold').fill('60');
  await page.getByRole('button', { name: 'Save settings' }).click();
  await expect(page.locator('[data-field="highThreshold"] .error')).toContainText('must be greater');
  await page.getByLabel('Low / medium threshold').fill('25');
  await page.getByLabel('Medium / high threshold').fill('65');
  await page.getByRole('button', { name: 'Save settings' }).click();
  await expect(page.getByText('Settings saved')).toBeVisible();
  await page.reload();
  await expect(page.getByLabel('Low / medium threshold')).toHaveValue('25');
  await page.getByLabel('Light', { exact: true }).check();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
  await page.getByLabel('Dark', { exact: true }).check();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  // restore defaults for other tests
  await page.getByLabel('Low / medium threshold').fill('30');
  await page.getByLabel('Medium / high threshold').fill('60');
  await page.getByRole('button', { name: 'Save settings' }).click();
  await expect(page.getByText('Settings saved')).toBeVisible();
});

test('the UI recovers from API errors', async ({ page }) => {
  let fail = true;
  await page.route('**/api/dashboard', (route) => fail
    ? route.fulfill({ status: 500, contentType: 'application/json', body: '{"status":500,"code":"INTERNAL_ERROR","message":"Simulated outage"}' })
    : route.continue());
  await page.goto('/');
  await expect(page.getByText('Simulated outage')).toBeVisible();
  fail = false;
  await page.getByRole('button', { name: 'Try again' }).click();
  await expect(page.getByText('Totals cover')).toBeVisible();
  allowed.push('/api/dashboard');
});

test('mobile layout: navigation drawer and no horizontal overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  for (const url of ['/', '/import', `/projects/${state.chain}`, `/projects/${state.chain}/explorer`, `/projects/${state.chain}/impact`, '/settings']) {
    await page.goto(url);
    await page.waitForLoadState('networkidle');
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(overflow, `horizontal overflow on ${url}`).toBeLessThanOrEqual(0);
  }
  await page.goto('/');
  await page.getByRole('button', { name: 'Open navigation' }).click();
  await expect(page.locator('#sidebar')).toHaveClass(/open/);
  await page.locator('#sidebar').getByRole('link', { name: 'Import Project' }).click();
  await expect(page.getByRole('heading', { name: 'Import Project', level: 1 })).toBeVisible();
});

test('deleting a project requires typing its name', async ({ page }) => {
  await page.goto(`/projects/${state.disposable}`);
  await page.getByRole('button', { name: 'Delete', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Delete project?' });
  const confirm = dialog.getByRole('button', { name: 'Delete project' });
  await expect(confirm).toBeDisabled();
  const name = (await page.locator('#project-heading').textContent()).trim();
  await dialog.getByRole('textbox').fill(name);
  await confirm.click();
  await expect(page).toHaveURL(/\/projects$/);
  await expect(page.getByText(name)).toHaveCount(0);
});
