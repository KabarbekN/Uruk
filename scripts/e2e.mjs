import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';

const require = createRequire(new URL('../frontend/package.json', import.meta.url));
const { chromium, expect } = require('@playwright/test');
const apiBase = process.env.E2E_API_URL || 'http://127.0.0.1:8080';
const uiBase = process.env.E2E_UI_URL || 'http://127.0.0.1:5173';
const fixtureRoot = process.env.E2E_FIXTURE_ROOT || 'fixtures';
const output = path.resolve('output/playwright');
await mkdir(output, { recursive: true });
const report = { startedAt: new Date().toISOString(), checks: [], screenshots: [] };
const check = (name, data = {}) => { report.checks.push({ name, ...data }); console.log(`PASS ${name}`); };
async function api(route, options = {}) {
  const response = await fetch(`${apiBase}/api/v1${route}`, {
    ...options, headers: { 'Content-Type': 'application/json', ...options.headers },
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    signal: AbortSignal.timeout(30000),
  });
  const text = await response.text();
  assert(response.ok, `${options.method || 'GET'} ${route}: ${response.status} ${text.slice(0, 1200)}`);
  return text ? JSON.parse(text) : null;
}
async function finished(id) {
  const deadline = Date.now() + 15 * 60 * 1000;
  let printed = '';
  while (Date.now() < deadline) {
    const run = await api(`/analysis-runs/${id}`);
    const state = `${run.status}: ${run.currentStage} (${run.progress}%)`;
    if (printed !== state) { console.log(state); printed = state; }
    if (['SUCCEEDED', 'PARTIALLY_SUCCEEDED', 'FAILED', 'CANCELLED'].includes(run.status)) {
      if (!['SUCCEEDED', 'PARTIALLY_SUCCEEDED'].includes(run.status)) {
        console.log(JSON.stringify(await api(`/analysis-runs/${id}/diagnostics`)).slice(0, 4000));
        assert.fail(`Analysis ${id} ended ${run.status}`);
      }
      return run;
    }
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
  assert.fail(`Analysis timed out: ${id}`);
}
async function screenshot(page, name) {
  const file = path.join(output, name);
  await page.screenshot({ path: file, fullPage: true });
  report.screenshots.push(file);
}
const browser = await chromium.launch({
  headless: true,
  channel: process.env.PLAYWRIGHT_CHANNEL || (process.platform === 'win32' ? 'chrome' : undefined),
});
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' });
await context.addInitScript(() => {
  if (!localStorage.getItem('sbm.locale')) localStorage.setItem('sbm.locale', 'en');
});
const page = await context.newPage();
const errors = [];
page.on('pageerror', error => errors.push(error.message));
try {
  assert.equal((await api('/settings')).aiMode, 'DISABLED');
  check('AI disabled configuration');
  await page.goto(`${uiBase}/projects`);
  await page.getByRole('button', { name: 'Create project', exact: true }).first().click();
  const dialog = page.getByRole('dialog', { name: 'Create project', exact: true });
  await expect(dialog).toBeVisible();
  const name = `Order acceptance ${new Date().toISOString().slice(0, 19)}`;
  await dialog.getByLabel('Name', { exact: true }).fill(name);
  await dialog.getByLabel('Description', { exact: true }).fill('Real parser acceptance: evidence, semantic diff and review.');
  await dialog.getByLabel('Local repository path', { exact: true }).fill(`${fixtureRoot}/spring-order-service`);
  await dialog.getByRole('button', { name: 'Create project', exact: true }).click();
  await page.waitForURL(/\/projects\/[0-9a-f-]{36}$/);
  const projectId = page.url().split('/').at(-1);
  report.projectId = projectId;
  check('Project created through UI', { projectId });
  await page.getByRole('button', { name: 'Run analysis', exact: true }).first().click();
  await page.getByRole('dialog', { name: 'New analysis', exact: true }).getByRole('button', { name: 'Run analysis', exact: true }).click();
  await page.waitForURL(/\/analyses\/[0-9a-f-]{36}\/canvas$/);
  const firstId = page.url().split('/').at(-2);
  report.baselineRun = firstId;
  report.baselineStatus = (await finished(firstId)).status;
  const technical = await api(`/analysis-runs/${firstId}/canvas?view=DEVELOPER&depth=5&lod=3`);
  assert(technical.nodes.some(node => node.kind === 'ENDPOINT' && node.stableKey.includes('/api/orders')));
  const baseGraph = await api(`/analysis-runs/${firstId}/canvas?view=BUSINESS&depth=5&lod=3`);
  const rule = baseGraph.nodes.find(node => node.kind === 'BUSINESS_RULE'
    && node.properties.normalizedCondition?.operator === 'LESS_THAN'
    && node.properties.normalizedCondition?.right?.value === 500);
  assert(rule, 'Expected evidence-backed minimum amount rule');
  const evidence = await api(`/semantic/nodes/${rule.id}/evidence`);
  assert(evidence.length > 0);
  assert(evidence.every(item => item.verified && item.startLine > 0 && item.endLine >= item.startLine && /^sha256:[a-f0-9]{64}$/.test(item.snippetHash)));
  assert(evidence.some(item => item.snippet.includes('500') || item.snippet.includes('MINIMUM_AMOUNT')));
  assert(baseGraph.nodes.filter(node => node.supportLevel === 'PROVEN').every(node => node.evidenceCount > 0));
  check('Real Spring endpoint and verified source evidence', { nodeCount: baseGraph.nodes.length, ruleId: rule.id });
  const ownership = await api(`/analysis-runs/${firstId}/canvas?view=DATA_OWNERSHIP&depth=5`);
  assert(ownership.nodes.some(node => JSON.stringify(node).includes('DATABASE_DEFAULT')), 'Database defaults missing');
  assert(ownership.nodes.some(node => node.kind === 'DATABASE_TRIGGER'), 'Database trigger missing');
  check('Database defaults and trigger ownership');
  const quarantined = await api(`/analysis-runs/${firstId}/quarantined-facts`);
  assert(Array.isArray(quarantined) && quarantined.length === 0, 'Valid fixture facts must not enter quarantine');
  check('Cross-analyzer schema compatibility');
  await page.reload();
  await expect(page.locator('.react-flow__node').first()).toBeVisible({ timeout: 60000 });
  await screenshot(page, 'business-desktop.png');
  await page.getByRole('searchbox', { name: 'Search semantic map', exact: true }).fill('MINIMUM_AMOUNT');
  await page.getByRole('button', { name: 'Node list', exact: true }).click();
  await page.getByRole('button', { name: rule.label, exact: true }).first().click();
  const drawer = page.getByRole('dialog', { name: 'Evidence', exact: true });
  await expect(drawer).toBeVisible();
  await expect(drawer.locator('.monaco-editor')).toBeVisible({ timeout: 60000 });
  await screenshot(page, 'evidence-desktop.png');
  await drawer.getByRole('tab', { name: 'Human reviews', exact: true }).click();
  await drawer.getByRole('combobox', { name: 'Decision', exact: true }).selectOption('CONFIRMED');
  await drawer.getByLabel('Review comment', { exact: true }).fill('Confirmed against source in the acceptance test.');
  await drawer.getByRole('button', { name: 'Submit review', exact: true }).click();
  await expect.poll(async () => (await api(`/semantic/nodes/${rule.id}`)).reviewStatus).toBe('CONFIRMED');
  check('Evidence drawer and human review through UI');
  await drawer.getByRole('button', { name: 'Close', exact: true }).click();
  await page.getByRole('searchbox', { name: 'Search semantic map', exact: true }).fill('');
  for (const view of ['Developer', 'Data ownership', 'Security', 'Data pipeline', 'Coverage']) {
    await page.getByRole('tab', { name: view, exact: true }).click();
    await expect(page.getByRole('tab', { name: view, exact: true })).toHaveAttribute('aria-selected', 'true');
  }
  check('Canvas view switching');
  await api(`/projects/${projectId}`, { method: 'PATCH', body: { name, repositoryPath: `${fixtureRoot}/spring-order-service-revision2` } });
  const key = `acceptance-${Date.now()}`;
  const second = await api(`/projects/${projectId}/analysis-runs`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: { baselineRunId: firstId } });
  const repeated = await api(`/projects/${projectId}/analysis-runs`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: { baselineRunId: firstId } });
  assert.equal(second.id, repeated.id);
  check('Analysis idempotency');
  report.targetRun = second.id;
  report.targetStatus = (await finished(second.id)).status;
  const changes = await api(`/projects/${projectId}/semantic-diff?fromAnalysisRun=${firstId}&toAnalysisRun=${second.id}`);
  const thresholdChange = changes.find(change => change.impactType === 'THRESHOLD_CHANGED' && JSON.stringify(change.before).includes('500') && JSON.stringify(change.after).includes('300') && change.beforeEvidenceIds.length && change.afterEvidenceIds.length);
  assert(thresholdChange);
  assert(changes.some(change => change.changeType === 'REVIEW_BECAME_STALE'));
  check('Deterministic threshold diff and stale review', { changeCount: changes.length });
  await page.goto(`${uiBase}/analyses/${second.id}/diff`);
  await expect(page.locator('.compare-bar select').first()).toHaveValue(firstId);
  await page.locator('.section-toolbar input[type="search"]').fill('THRESHOLD_CHANGED');
  const changeItem = page.locator('.change-item').filter({ hasText: thresholdChange.subjectStableKey }).first();
  await expect(changeItem).toBeVisible();
  await changeItem.locator('summary').click();
  await expect(changeItem.locator('.diff-side.before .json-view')).toContainText('500');
  await expect(changeItem.locator('.diff-side.after .json-view')).toContainText('300');
  await expect(changeItem.locator('.reference-list').first()).not.toBeEmpty();
  await expect(changeItem.locator('.reference-list').last()).not.toBeEmpty();
  await page.evaluate(() => document.fonts.ready);
  await screenshot(page, 'diff-desktop.png');
  check('Threshold change and before/after evidence rendered through UI');
  await page.goto(`${uiBase}/analyses/${second.id}/canvas`);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.evaluate(() => localStorage.setItem('sbm.locale', 'ru'));
  await page.reload();
  await expect(page.locator('.canvas-page')).toBeVisible();
  await expect(page.locator('.react-flow__node').first()).toBeVisible({ timeout: 60000 });
  await page.evaluate(() => document.fonts.ready);
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), 'Mobile horizontal overflow');
  await screenshot(page, 'canvas-mobile.png');
  await page.goto(`${uiBase}/projects`);
  await page.locator('.section-toolbar input[type="search"]').fill(name);
  await expect(page.locator('.project-link').filter({ hasText: name })).toBeVisible();
  await screenshot(page, 'projects-mobile.png');
  check('Responsive desktop/mobile rendering');
  const cancellation = await api(`/projects/${projectId}/analysis-runs`, { method: 'POST', body: {} });
  const cancelled = await api(`/analysis-runs/${cancellation.id}/cancel`, { method: 'POST', body: {} });
  assert.equal(cancelled.status, 'CANCELLED');
  check('Analysis cancellation');
  await page.goto(`${uiBase}/analyses/${second.id}/diff`);
  await expect(page.locator('.compare-bar select').first()).toHaveValue(firstId);
  await expect(page.locator('.change-item').first()).toBeVisible();
  check('Diff baseline excludes newer cancelled runs');
  const typescriptProject = await api('/projects', { method: 'POST', body: {
    name: `NestJS acceptance ${new Date().toISOString().slice(0, 19)}`,
    description: 'Native TypeScript fixture through the real shared analysis pipeline.',
    repositoryPath: path.posix.join(fixtureRoot, '..', 'analyzers', 'typescript-node', 'tests', 'fixtures'),
  } });
  const typescriptRun = await api(`/projects/${typescriptProject.id}/analysis-runs`, { method: 'POST', body: {} });
  report.typescript = { projectId: typescriptProject.id, runId: typescriptRun.id,
    status: (await finished(typescriptRun.id)).status };
  const typescriptPlan = await api(`/analysis-runs/${typescriptRun.id}/analyzer-plan`);
  const nativeTypescript = typescriptPlan.analyzers.find(analyzer => analyzer.analyzerKey === 'typescript-node');
  assert(nativeTypescript && /^sha256:[a-f0-9]{64}$/.test(nativeTypescript.imageDigest));
  assert(typescriptPlan.analyzers.every(analyzer => ['typescript-node', 'tree-sitter'].includes(analyzer.analyzerKey)),
    'Unrelated ecosystem analyzer was scheduled');
  report.typescript.imageDigest = nativeTypescript.imageDigest;
  check('TypeScript on-demand analyzer selection and immutable image identity');
  const typescriptGraph = await api(`/analysis-runs/${typescriptRun.id}/canvas?view=DEVELOPER&depth=5&lod=3`);
  const endpoint = typescriptGraph.nodes.find(node => node.kind === 'ENDPOINT'
    && node.properties.httpMethod === 'POST' && node.properties.path === '/orders');
  const minimum = typescriptGraph.nodes.find(node => node.kind === 'VALIDATION_RULE'
    && node.properties.constraint === 'Min' && node.properties.arguments?.[0] === 500);
  assert(endpoint && minimum, 'Native NestJS endpoint or minimum validation missing');
  for (const node of [endpoint, minimum]) {
    const sources = await api(`/semantic/nodes/${node.id}/evidence`);
    assert(sources.length > 0 && sources.every(item => item.verified
      && /^sha256:[a-f0-9]{64}$/.test(item.snippetHash) && item.startLine > 0));
  }
  assert.deepEqual(await api(`/analysis-runs/${typescriptRun.id}/quarantined-facts`), []);
  check('Native NestJS endpoint and validation evidence through the shared pipeline');
  assert.deepEqual(errors, [], 'Browser runtime errors');
  check('No browser runtime errors');
  report.status = 'PASSED';
} catch (error) {
  report.status = 'FAILED'; report.error = error.stack;
  await screenshot(page, 'failure.png').catch(() => {});
  throw error;
} finally {
  report.finishedAt = new Date().toISOString();
  await writeFile(path.join(output, 'acceptance.json'), JSON.stringify(report, null, 2));
  await context.close(); await browser.close();
}
