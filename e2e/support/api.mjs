// API helpers used to prepare test data (the UI workflows themselves are exercised through the browser).
import fs from 'node:fs';
import path from 'node:path';
import { zipFixture } from './zip.mjs';

export const port = Number(process.env.RIPPLEMAP_E2E_PORT || 18181);
export const baseURL = `http://127.0.0.1:${port}`;

export async function csrfToken(request) {
  const html = await (await request.get(baseURL + '/import')).text();
  const match = /name="ripplemap-csrf" content="([^"]+)"/.exec(html);
  if (!match) throw new Error('CSRF token not found');
  return match[1];
}

export async function waitForAnalysis(request, projectId, analysisId) {
  const deadline = Date.now() + 90_000;
  while (Date.now() < deadline) {
    const analysis = await (await request.get(`${baseURL}/api/projects/${projectId}/analyses/${analysisId}`)).json();
    if (analysis.status !== 'PENDING' && analysis.status !== 'RUNNING') {
      if (!analysis.hasResults) throw new Error(`Analysis ${analysisId} failed: ${analysis.errorMessage}`);
      return analysis;
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error(`Analysis ${analysisId} did not finish`);
}

/** Imports a fixture through the REST API and waits for its analysis; returns the project id. */
export async function apiImport(request, name, fixture) {
  const token = await csrfToken(request);
  const response = await request.post(baseURL + '/api/projects/import', {
    headers: { 'X-RippleMap-CSRF': token },
    multipart: { name, file: { name: `${fixture.replace('/', '-')}.zip`, mimeType: 'application/zip', buffer: zipFixture(fixture) } }
  });
  if (response.status() !== 201) throw new Error(`Import failed: ${response.status()} ${await response.text()}`);
  const body = await response.json();
  await waitForAnalysis(request, body.project.id, body.analysis.id);
  return body.project.id;
}

function sampleFile(file) {
  return { name: file, mimeType: 'application/zip', buffer: fs.readFileSync(path.resolve('..', 'sample-projects', file)) };
}

/** Imports sample-projects/shop-v1.zip, uploads shop-v2.zip as its second version, and waits for both analyses. */
export async function importShopSample(request) {
  const token = await csrfToken(request);
  const created = await request.post(baseURL + '/api/projects/import', {
    headers: { 'X-RippleMap-CSRF': token },
    multipart: { name: `Shop sample ${Date.now().toString(36)}`, description: 'Sample order-management service shipped with RippleMap.',
      file: sampleFile('shop-v1.zip') }
  });
  if (created.status() !== 201) throw new Error(`Import failed: ${created.status()} ${await created.text()}`);
  const body = await created.json();
  await waitForAnalysis(request, body.project.id, body.analysis.id);
  const upload = await request.post(`${baseURL}/api/projects/${body.project.id}/versions`, {
    headers: { 'X-RippleMap-CSRF': token },
    multipart: { label: 'shipping', file: sampleFile('shop-v2.zip') }
  });
  if (upload.status() !== 201) throw new Error(`Version upload failed: ${upload.status()} ${await upload.text()}`);
  const v2 = (await upload.json()).analysis;
  await waitForAnalysis(request, body.project.id, v2.id);
  return { id: body.project.id, v1: body.analysis.id, v2: v2.id };
}

/** [label, url] of every page, populated with the shop sample (see importShopSample). */
export function samplePages({ id, v1, v2 }) {
  return [
    ['dashboard', '/'],
    ['projects', '/projects'],
    ['import', '/import'],
    ['overview', `/projects/${id}`],
    ['explorer', `/projects/${id}/explorer?analysisId=${v1}`],
    ['explorer-selected', `/projects/${id}/explorer?analysisId=${v1}&focus=com.example.shop.service.InventoryService&scope=NEIGHBORHOOD`],
    ['impact', `/projects/${id}/impact?node=com.example.shop.domain.Order`],
    ['cycles', `/projects/${id}/cycles?analysisId=${v1}`],
    ['history', `/projects/${id}/history`],
    ['compare', `/projects/${id}/compare?base=${v1}&target=${v2}`],
    ['reports', `/projects/${id}/reports`],
    ['settings', '/settings'],
    ['not-found', '/projects/999999']
  ];
}
