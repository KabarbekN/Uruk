import { expect, test, type Page } from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';
import type { CanvasLayout } from '../src/shared/api/types';
import { project, run } from '../src/test/fixtures';
import { largeProjection } from './fixtures/layered-graph';

// DOM snapshot serialization is measured separately by the opt-in CDP diagnostic.
// Functional suites retain traces; performance keeps screenshots and JSON metrics.
test.use({ trace: 'off' });

type Probe = {
  workers: { url: string; started: number; finished?: number }[];
  longTasks: { start: number; duration: number }[];
  heartbeatDelays: number[];
};
declare global {
  interface Window {
    sbmPerfProbe: Probe;
  }
}

test.afterEach(async ({ page }, info) => {
  if (info.status !== info.expectedStatus && !page.isClosed()) {
    await info.attach('performance-probe-at-failure', {
      body: JSON.stringify(await page.evaluate(() => window.sbmPerfProbe)),
      contentType: 'application/json',
    });
  }
});

async function painted(page: Page) {
  await page.evaluate(
    () =>
      new Promise<void>((resolve) =>
        requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
      ),
  );
}

test('bounded 1000-node / 2000-edge performance acceptance with the real layout worker', async ({
  page,
  browser,
}, info) => {
  test.setTimeout(120000);
  const graph = largeProjection();
  const errors: string[] = [];
  const controls: Record<string, number> = {};
  const canvasControls = page.locator('.canvas-controls');
  const mapToolbar = page.locator('.map-toolbar');
  let saved: CanvasLayout | undefined;
  let evidenceRequests = 0;
  page.on('pageerror', (error) => errors.push(error.message));
  await page.addInitScript(() => {
    localStorage.setItem('sbm.locale', 'en');
    const probe: Probe = { workers: [], longTasks: [], heartbeatDelays: [] };
    window.sbmPerfProbe = probe;
    const NativeWorker = window.Worker;
    window.Worker = class extends NativeWorker {
      constructor(url: string | URL, options?: WorkerOptions) {
        super(url, options);
        const record: Probe['workers'][number] = {
          url: String(url),
          started: performance.now(),
        };
        probe.workers.push(record);
        this.addEventListener('message', () => {
          record.finished = performance.now();
        });
      }
    };
    if (PerformanceObserver.supportedEntryTypes.includes('longtask')) {
      new PerformanceObserver((list) => {
        for (const entry of list.getEntries())
          probe.longTasks.push({
            start: entry.startTime,
            duration: entry.duration,
          });
      }).observe({ type: 'longtask', buffered: true });
    }
    let previous = performance.now();
    setInterval(() => {
      const now = performance.now();
      probe.heartbeatDelays.push(Math.max(0, now - previous - 100));
      previous = now;
    }, 100);
  });
  await page.route('**/api/v1/**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/projects')) return route.fulfill({ json: [project] });
    if (path.endsWith('/analysis-runs')) return route.fulfill({ json: [run] });
    if (path.endsWith(`/analysis-runs/${run.id}`))
      return route.fulfill({ json: run });
    if (path.endsWith('/canvas')) return route.fulfill({ json: graph });
    if (path.endsWith('/layout')) {
      if (route.request().method() === 'PUT') {
        saved = route.request().postDataJSON() as CanvasLayout;
        return route.fulfill({ status: 204 });
      }
      return route.fulfill({
        json: { positions: {}, viewport: { x: 0, y: 0, zoom: 1 } },
      });
    }
    if (path.endsWith('/evidence')) evidenceRequests++;
    return route.fulfill({
      status: 500,
      json: { detail: `Unexpected performance-test request: ${path}` },
    });
  });
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Performance.enable');
  const memory = async () => {
    const result = await cdp.send('Performance.getMetrics');
    const counters = await cdp.send('Memory.getDOMCounters');
    return {
      jsHeapUsedBytes: result.metrics.find(
        (metric) => metric.name === 'JSHeapUsedSize',
      )?.value,
      jsHeapTotalBytes: result.metrics.find(
        (metric) => metric.name === 'JSHeapTotalSize',
      )?.value,
      ...counters,
    };
  };
  await page.goto('/projects');
  await expect(
    page.getByRole('heading', { name: 'Projects', exact: true }),
  ).toBeVisible();
  const baselineMemory = await memory();
  const started = Date.now();
  await page.goto(`/analyses/${run.id}/canvas`);
  await expect(page.locator('.graph-surface')).toBeVisible();
  const timed = async (name: string, action: () => Promise<unknown>) => {
    const start = Date.now();
    await action();
    await painted(page);
    controls[name] = Date.now() - start;
  };
  await timed('filtersDuringLayout', async () => {
    await mapToolbar
      .getByRole('button', { name: 'Filters', exact: true })
      .click();
    await expect(page.locator('.filter-panel')).toBeVisible();
    await mapToolbar
      .getByRole('button', { name: 'Filters', exact: true })
      .click();
    await expect(page.locator('.filter-panel')).toHaveCount(0);
  });
  await expect(
    canvasControls.getByRole('button', { name: 'Save layout', exact: true }),
  ).toBeEnabled({ timeout: 40000 });
  await expect(page.locator('.graph-message')).toHaveCount(0);
  await expect(
    page.locator('.react-flow__node-semantic').first(),
  ).toBeVisible();
  await painted(page);
  const readyMs = Date.now() - started;
  await canvasControls
    .getByRole('button', { name: 'Save layout', exact: true })
    .click();
  await expect
    .poll(() => Object.keys(saved?.positions ?? {}).length)
    .toBe(1000);
  const positions = Object.values(saved!.positions);
  for (const position of positions) {
    expect(Number.isFinite(position.x) && Number.isFinite(position.y)).toBe(
      true,
    );
  }
  let overlaps = 0;
  for (let a = 0; a < positions.length; a++) {
    for (let b = a + 1; b < positions.length; b++) {
      const left = positions[a]!;
      const right = positions[b]!;
      if (Math.abs(left.x - right.x) < 263 && Math.abs(left.y - right.y) < 149)
        overlaps++;
    }
  }
  expect(overlaps).toBe(0);
  const afterLayoutMemory = await memory();
  const renderedAtFit = await page
    .locator('.react-flow__node-semantic')
    .count();
  await mkdir('output/playwright', { recursive: true });
  await page.screenshot({
    path: `output/playwright/large-graph-${info.project.name}.png`,
  });
  const viewport = page.locator('.react-flow__viewport');
  const beforeZoom = await viewport.getAttribute('style');
  await timed('zoom', async () => {
    await canvasControls
      .getByRole('button', { name: 'Zoom in', exact: true })
      .click();
    await expect(viewport).not.toHaveAttribute('style', beforeZoom!);
  });
  await timed('lock', async () => {
    await canvasControls
      .getByRole('button', { name: 'Lock map', exact: true })
      .click();
    await expect(
      canvasControls.getByRole('button', { name: 'Unlock map', exact: true }),
    ).toHaveAttribute('aria-pressed', 'true');
    await canvasControls
      .getByRole('button', { name: 'Unlock map', exact: true })
      .click();
  });
  await timed('minimap', async () => {
    await canvasControls
      .getByRole('button', { name: 'Toggle minimap', exact: true })
      .click();
    await expect(page.locator('.react-flow__minimap')).toBeVisible();
    const minimap = await page.locator('.react-flow__minimap').boundingBox();
    const toolbar = await page.locator('.canvas-controls').boundingBox();
    expect(minimap!.y + minimap!.height).toBeLessThan(toolbar!.y);
    await canvasControls
      .getByRole('button', { name: 'Toggle minimap', exact: true })
      .click();
  });
  for (let index = 0; index < 8; index++)
    await canvasControls
      .getByRole('button', { name: 'Zoom in', exact: true })
      .click();
  await painted(page);
  const beforePan = await viewport.getAttribute('style');
  const panPoint = await page.locator('.graph-surface').evaluate((element) => {
    const bounds = element.getBoundingClientRect();
    for (let y = bounds.y + 30; y < bounds.bottom - 80; y += 25) {
      for (let x = bounds.x + 30; x < bounds.right - 100; x += 25) {
        if (
          document
            .elementFromPoint(x, y)
            ?.classList.contains('react-flow__pane')
        )
          return { x, y };
      }
    }
    return null;
  });
  expect(panPoint).not.toBeNull();
  await timed('pan', async () => {
    await page.mouse.move(panPoint!.x, panPoint!.y);
    await page.mouse.down();
    await page.mouse.move(panPoint!.x + 70, panPoint!.y + 20, { steps: 4 });
    await page.mouse.up();
    await expect(viewport).not.toHaveAttribute('style', beforePan!);
  });
  await page.screenshot({
    path: `output/playwright/large-graph-detail-${info.project.name}.png`,
  });
  await timed('nodeList', async () => {
    await mapToolbar
      .getByRole('button', { name: 'Node list', exact: true })
      .click();
    await expect(page.locator('.node-table tbody tr')).toHaveCount(50);
    await expect(page.locator('.pagination')).toContainText('1 / 20');
  });
  await timed('nextPage', async () => {
    await page
      .locator('.pagination')
      .getByRole('button', { name: 'Next page', exact: true })
      .click();
    await expect(
      page
        .locator('.node-table')
        .getByRole('button', { name: 'Order policy 0051', exact: true }),
    ).toBeVisible();
    await page
      .locator('.pagination')
      .getByRole('button', { name: 'Previous page', exact: true })
      .click();
    await expect(page.locator('.pagination')).toContainText('1 / 20');
  });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth + 1,
    ),
  ).toBe(true);
  await page.screenshot({
    path: `output/playwright/large-graph-list-${info.project.name}.png`,
  });
  const endMemory = await memory();
  const probe = await page.evaluate(() => window.sbmPerfProbe);
  const workers = probe.workers.filter((worker) =>
    worker.url.includes('layout.worker'),
  );
  const report = {
    measuredAt: new Date().toISOString(),
    browser: browser.version(),
    viewport: info.project.use.viewport,
    baseURL: info.project.use.baseURL,
    nodes: graph.nodes.length,
    edges: graph.edges.length,
    readyMs,
    controls,
    renderedAtFit,
    nonoverlappingPositions: positions.length,
    workers: workers.map((worker) => ({
      durationMs: worker.finished ? worker.finished - worker.started : null,
    })),
    longTasks: {
      count: probe.longTasks.length,
      maxMs: Math.max(0, ...probe.longTasks.map((entry) => entry.duration)),
      totalMs: probe.longTasks.reduce((sum, entry) => sum + entry.duration, 0),
    },
    heartbeatMaxDelayMs: Math.max(0, ...probe.heartbeatDelays),
    memory: {
      baseline: baselineMemory,
      afterLayout: afterLayoutMemory,
      afterList: endMemory,
    },
    caveats: [
      'Performance-only Playwright DOM tracing is off after CDP proved captureSnapshot overhead; locators are scoped to their control panels. Original traced baseline is retained separately. Functional tracing is unchanged.',
      'Single cold navigation, no CPU/network throttling, mobile viewport emulation on the same desktop CPU.',
      'ELK time includes worker module loading and message transfer, not just algorithm CPU.',
      'Control timings include Playwright actionability and frame waits; not input-event latency percentiles.',
      'CDP JS heap excludes worker heaps and browser/GPU/native memory; no forced GC or process RSS measurement.',
      'Synthetic connected 20-layer graph; not a production topology, backend benchmark or leak/soak test.',
    ],
  };
  await writeFile(
    `output/playwright/large-graph-${info.project.name}.json`,
    JSON.stringify(report, null, 2),
  );
  await info.attach('performance-report', {
    body: JSON.stringify(report, null, 2),
    contentType: 'application/json',
  });
  expect(workers.some((worker) => worker.finished !== undefined)).toBe(true);
  expect(evidenceRequests).toBe(0);
  expect(errors).toEqual([]);
  expect(readyMs).toBeLessThan(35000);
  expect(report.heartbeatMaxDelayMs).toBeLessThan(1500);
  expect(report.longTasks.maxMs).toBeLessThan(1500);
  for (const duration of Object.values(controls))
    expect(duration).toBeLessThan(3000);
});
