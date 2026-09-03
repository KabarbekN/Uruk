import { expect, test } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { project, run } from '../src/test/fixtures';
import { createLayoutServer } from './fixtures/layout-server';
import {
  createScalableGraph,
  overviewLayout,
  rendererStressProjection,
} from './fixtures/scalable-graph';
import {
  assertScaleGates,
  finishScaleReport,
  graphReady,
  installScaleProbe,
  memorySampler,
  rendererGeometry,
  timedControl,
} from './helpers/scale-probe';

test.use({ trace: 'off' });

test('@scale isolated renderer stress with 2000 visible desktop nodes, not a server-flow test', async ({
  page,
  browser,
}, info) => {
  test.skip(
    info.project.name !== 'desktop',
    '2000 simultaneously visible nodes use a 1920x1080 desktop overview; mobile is covered by bounded progressive exploration.',
  );
  test.setTimeout(120000);
  await page.setViewportSize({ width: 1920, height: 1080 });
  const fullGraph = createScalableGraph();
  const graph = rendererStressProjection(fullGraph);
  const layout = overviewLayout(graph.nodes);
  const errors: string[] = [];
  const controls: Record<string, number> = {};
  const canvasControls = page.locator('.canvas-controls');
  const layoutServer = createLayoutServer(fullGraph.nodes, layout);
  let evidenceRequests = 0;
  let readyMs = 0;
  let completed = false;
  const geometry: unknown[] = [];
  const memory: unknown[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await installScaleProbe(page);
  // This route intentionally exceeds GraphQueries limits to stress rendering alone.
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path.endsWith('/projects')) return route.fulfill({ json: [project] });
    if (path.endsWith('/analysis-runs')) return route.fulfill({ json: [run] });
    if (path.endsWith(`/analysis-runs/${run.id}`))
      return route.fulfill({ json: run });
    if (path.endsWith('/canvas')) return route.fulfill({ json: graph });
    if (path.endsWith('/layout')) {
      if (request.method() === 'PUT') {
        return route.fulfill(layoutServer.put(request.postDataJSON()));
      }
      return route.fulfill({ json: layoutServer.get() });
    }
    if (path.endsWith('/evidence')) {
      evidenceRequests++;
      return route.fulfill({ json: [] });
    }
    const id = path.match(/\/semantic\/nodes\/([^/]+)$/)?.[1];
    const node = id ? fullGraph.byId.get(id) : undefined;
    if (node)
      return route.fulfill({
        json: {
          ...node,
          facts: [],
          assertions: [],
          enrichments: [],
          reviews: [],
        },
      });
    return route.fulfill({
      status: 500,
      json: { detail: `Unexpected renderer-stress request: ${path}` },
    });
  });
  const sampleMemory = await memorySampler(page);
  memory.push({ phase: 'blank-page', ...(await sampleMemory()) });
  let report!: Awaited<ReturnType<typeof finishScaleReport>>;
  try {
    const started = Date.now();
    await page.goto(`/analyses/${run.id}/canvas`);
    await page.evaluate(() => document.fonts.ready);
    await graphReady(page, 2000);
    await expect
      .poll(async () => (await rendererGeometry(page)).fullyVisible)
      .toBe(2000);
    await expect(page.locator('.react-flow__edge')).toHaveCount(graph.edges.length);
    readyMs = Date.now() - started;
    const initial = await rendererGeometry(page);
    expect(initial.rendered).toBe(2000);
    expect(initial.documentWidth).toBeLessThanOrEqual(1920);
    geometry.push({ phase: 'initial-overview', ...initial });
    expect(evidenceRequests).toBe(0);
    memory.push({ phase: '2000-visible', ...(await sampleMemory()) });
    await mkdir('output/playwright', { recursive: true });
    await page.screenshot({
      path: 'output/playwright/renderer-2000-overview-desktop.png',
    });

    await timedControl(page, controls, 'lock', async () => {
      await canvasControls
        .getByRole('button', { name: 'Lock map', exact: true })
        .click();
      await canvasControls
        .getByRole('button', { name: 'Unlock map', exact: true })
        .click();
    });
    const viewport = page.locator('.react-flow__viewport');
    const beforePan = await viewport.getAttribute('style');
    const frame = await page.locator('.graph-surface').boundingBox();
    await timedControl(page, controls, 'pan', async () => {
      await page.mouse.move(frame!.x + 12, frame!.y + 12);
      await page.mouse.down();
      await page.mouse.move(frame!.x + 52, frame!.y + 32, { steps: 4 });
      await page.mouse.up();
      await expect(viewport).not.toHaveAttribute('style', beforePan!);
    });
    const afterPan = await rendererGeometry(page);
    expect(afterPan.fullyVisible).toBe(2000);
    await expect(page.locator('.react-flow__edge')).toHaveCount(graph.edges.length);
    geometry.push({ phase: 'after-pan', ...afterPan });
    await timedControl(page, controls, 'minimap', async () => {
      await canvasControls
        .getByRole('button', { name: 'Toggle minimap', exact: true })
        .click();
      await expect(page.locator('.react-flow__minimap')).toBeVisible();
      await canvasControls
        .getByRole('button', { name: 'Toggle minimap', exact: true })
        .click();
    });
    await timedControl(page, controls, 'selectionAndClose', async () => {
      await page
        .locator(`.react-flow__node[data-id="${graph.nodes[0]!.id}"]`)
        .click();
      const drawer = page.getByTestId('evidence-drawer');
      await expect(
        drawer.getByRole('heading', {
          name: graph.nodes[0]!.label,
          exact: true,
        }),
      ).toBeVisible();
      await drawer.getByRole('button', { name: 'Close', exact: true }).click();
      await expect(drawer).toHaveCount(0);
    });
    await expect
      .poll(async () => (await rendererGeometry(page)).fullyVisible)
      .toBe(2000);
    await timedControl(page, controls, 'zoomAndFit', async () => {
      const before = await viewport.getAttribute('style');
      await canvasControls
        .getByRole('button', { name: 'Zoom in', exact: true })
        .click();
      await expect(viewport).not.toHaveAttribute('style', before!);
      await canvasControls
        .getByRole('button', { name: 'Fit map', exact: true })
        .click();
    });
    await expect
      .poll(async () => (await rendererGeometry(page)).fullyVisible)
      .toBe(2000);
    await canvasControls
      .getByRole('button', { name: 'Save layout', exact: true })
      .click();
    await expect
      .poll(
        () => Object.keys(layoutServer.accepted.at(-1)?.positions ?? {}).length,
      )
      .toBe(2000);
    expect(layoutServer.rejected).toEqual([]);
    geometry.push({
      phase: 'after-interactions',
      ...(await rendererGeometry(page)),
    });
    await expect(page.locator('.react-flow__edge')).toHaveCount(graph.edges.length);
    memory.push({ phase: 'after-interactions', ...(await sampleMemory()) });
    await timedControl(page, controls, 'nodeList', async () => {
      await page
        .locator('.map-toolbar')
        .getByRole('button', { name: 'Node list', exact: true })
        .click();
      await expect(page.locator('.node-table tbody tr')).toHaveCount(50);
      await expect(page.locator('.pagination')).toContainText('1 / 40');
    });
    await timedControl(page, controls, 'nextPage', async () => {
      await page
        .locator('.pagination')
        .getByRole('button', { name: 'Next page', exact: true })
        .click();
      await expect(page.locator('.pagination')).toContainText('2 / 40');
    });
    await page.screenshot({
      path: 'output/playwright/renderer-2000-list-desktop.png',
    });
    memory.push({ phase: 'after-list', ...(await sampleMemory()) });
    expect(errors).toEqual([]);
    completed = true;
  } finally {
    report = await finishScaleReport(page, info, 'renderer-2000', {
      functionalScenarioCompleted: completed,
      browser: browser.version(),
      workload:
        'Isolated renderer stress. Not a GraphQueries/server-flow acceptance result.',
      nodes: graph.nodes.length,
      edges: graph.edges.length,
      readyMs,
      controls,
      geometry,
      evidenceRequests,
      memory,
      errors,
      layout:
        'Pre-existing saved nonoverlapping 50-column overview at zoom 0.1. Normal application logic must restore all finite saved positions without constructing an unnecessary ELK worker. New-layout ELK is exercised separately by the 1k and progressive 10k tests.',
      visibilityDefinition:
        '2000 nonzero node rectangles fully inside the canvas at overview; zoom/drawer changes may temporarily invoke normal culling.',
    });
  }
  assertScaleGates(report, [readyMs], controls, 'saved-layout');
});
