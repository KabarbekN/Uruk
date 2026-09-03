import { expect, type Page, type TestInfo } from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';

type ScaleProbe = {
  workers: {
    url: string;
    started: number;
    finished?: number;
    ok?: boolean;
    error?: string;
  }[];
  longTasks: number[];
  heartbeatDelays: number[];
};
declare global {
  interface Window {
    sbmScaleProbe: ScaleProbe;
  }
}

export const SCALE_LIMITS = {
  readyMs: 35000,
  longTaskMs: 1500,
  heartbeatMs: 1500,
  controlMs: 3000,
} as const;

export async function installScaleProbe(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('sbm.locale', 'en');
    const probe: ScaleProbe = {
      workers: [],
      longTasks: [],
      heartbeatDelays: [],
    };
    window.sbmScaleProbe = probe;
    const NativeWorker = window.Worker;
    window.Worker = class extends NativeWorker {
      constructor(url: string | URL, options?: WorkerOptions) {
        super(url, options);
        const record: ScaleProbe['workers'][number] = {
          url: String(url),
          started: performance.now(),
        };
        probe.workers.push(record);
        this.addEventListener('message', (event) => {
          record.finished = performance.now();
          record.ok = event.data.ok;
          record.error = event.data.error;
        });
        this.addEventListener('error', (event) => {
          record.error = event.message;
        });
      }
    };
    if (PerformanceObserver.supportedEntryTypes.includes('longtask')) {
      new PerformanceObserver((entries) => {
        for (const entry of entries.getEntries())
          probe.longTasks.push(entry.duration);
      }).observe({ type: 'longtask', buffered: true });
    }
    let previous = performance.now();
    setInterval(() => {
      const now = performance.now();
      probe.heartbeatDelays.push(Math.max(0, now - previous - 100));
      previous = now;
    }, 100);
  });
}

export async function painted(page: Page) {
  await page.evaluate(
    () =>
      new Promise<void>((resolve) =>
        requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
      ),
  );
}

export async function timedControl(
  page: Page,
  controls: Record<string, number>,
  name: string,
  action: () => Promise<unknown>,
) {
  const start = Date.now();
  await action();
  await painted(page);
  controls[name] = Date.now() - start;
}

export async function graphReady(
  page: Page,
  expectedCount: number,
  fit = false,
) {
  await expect(page.locator('.map-status strong').first()).toHaveText(
    String(expectedCount),
  );
  await expect(
    page
      .locator('.canvas-controls')
      .getByRole('button', { name: 'Save layout', exact: true }),
  ).toBeEnabled({ timeout: SCALE_LIMITS.readyMs });
  await expect(page.locator('.graph-overlay')).toHaveCount(0);
  await expect(page.locator('.graph-message')).toHaveCount(0);
  if (fit)
    await page
      .locator('.canvas-controls')
      .getByRole('button', { name: 'Fit map', exact: true })
      .click();
  await expect(
    page.locator('.react-flow__node-semantic').first(),
  ).toBeVisible();
  await painted(page);
}

export async function rendererGeometry(page: Page) {
  return page.locator('.graph-surface').evaluate((surface) => {
    const frame = surface.getBoundingClientRect();
    const boxes = [
      ...surface.querySelectorAll('.react-flow__node-semantic'),
    ].map((node) => node.getBoundingClientRect());
    return {
      rendered: boxes.length,
      fullyVisible: boxes.filter(
        (box) =>
          box.width > 0 &&
          box.height > 0 &&
          box.left >= frame.left &&
          box.right <= frame.right &&
          box.top >= frame.top &&
          box.bottom <= frame.bottom,
      ).length,
      documentWidth: document.documentElement.scrollWidth,
      canvasWidth: frame.width,
      canvasHeight: frame.height,
    };
  });
}

export async function memorySampler(page: Page) {
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Performance.enable');
  return async () => {
    const { metrics } = await cdp.send('Performance.getMetrics');
    return {
      jsHeapUsedBytes: metrics.find(
        (metric) => metric.name === 'JSHeapUsedSize',
      )?.value,
      jsHeapTotalBytes: metrics.find(
        (metric) => metric.name === 'JSHeapTotalSize',
      )?.value,
      ...(await cdp.send('Memory.getDOMCounters')),
    };
  };
}

export async function finishScaleReport(
  page: Page,
  info: TestInfo,
  name: string,
  details: Record<string, unknown>,
) {
  const probe = await page.evaluate(() => window.sbmScaleProbe);
  const workers = probe.workers.filter((worker) =>
    worker.url.includes('layout.worker'),
  );
  const report = {
    ...details,
    measuredAt: new Date().toISOString(),
    viewport: page.viewportSize(),
    baseURL: info.project.use.baseURL,
    limits: SCALE_LIMITS,
    workers: workers.map((worker) => ({
      ...worker,
      durationMs:
        worker.finished === undefined ? null : worker.finished - worker.started,
    })),
    longestTaskMs: Math.max(0, ...probe.longTasks),
    heartbeatMaxDelayMs: Math.max(0, ...probe.heartbeatDelays),
    caveats: [
      'Performance-only Playwright DOM tracing is off and role locators are scoped to control panels after CDP identified snapshot traversal overhead. Functional traces and original traced baselines are retained.',
      'Test-only transport data through the real API client and React Flow; ELK is required for new layouts and must be skipped for complete saved flat layouts. Not backend/analyzer acceptance.',
      'Single run, no CPU/network throttle or forced GC. Repeat on a quiet host; no FPS or percentile claim.',
      'CDP renderer JS heap excludes worker, GPU, native and process RSS memory; not a leak/soak test.',
      'Control timings include Playwright actionability and paint waits. Worker lifetime includes module loading and message transfer.',
      'Unfinished workers may be cancelled by normal projection/list navigation; completed workers must succeed.',
    ],
  };
  await mkdir('output/playwright', { recursive: true });
  await writeFile(
    `output/playwright/${name}-${info.project.name}.json`,
    JSON.stringify(report, null, 2),
  );
  await info.attach(name, {
    body: JSON.stringify(report, null, 2),
    contentType: 'application/json',
  });
  return report;
}

export function assertScaleGates(
  report: Awaited<ReturnType<typeof finishScaleReport>>,
  readyTimes: number[],
  controls: Record<string, number>,
  workerPolicy: 'elk' | 'saved-layout' = 'elk',
) {
  if (workerPolicy === 'saved-layout') expect(report.workers).toHaveLength(0);
  else expect(report.workers.some((worker) => worker.ok === true)).toBe(true);
  expect(
    report.workers.filter((worker) => worker.error || worker.ok === false),
  ).toEqual([]);
  assertScaleTimings(report, readyTimes, controls);
}

function assertScaleTimings(
  report: { longestTaskMs: number; heartbeatMaxDelayMs: number },
  readyTimes: number[],
  controls: Record<string, number>,
) {
  for (const duration of readyTimes)
    expect(duration, 'projection ready time').toBeLessThan(
      SCALE_LIMITS.readyMs,
    );
  for (const [name, duration] of Object.entries(controls))
    expect(duration, name).toBeLessThan(SCALE_LIMITS.controlMs);
  expect(report.longestTaskMs, 'longest main-thread task').toBeLessThan(
    SCALE_LIMITS.longTaskMs,
  );
  expect(report.heartbeatMaxDelayMs, 'heartbeat delay').toBeLessThan(
    SCALE_LIMITS.heartbeatMs,
  );
}

export async function assertScaleCheckpoint(
  page: Page,
  readyTimes: number[],
  controls: Record<string, number>,
) {
  const timings = await page.evaluate(() => ({
    longestTaskMs: Math.max(0, ...window.sbmScaleProbe.longTasks),
    heartbeatMaxDelayMs: Math.max(0, ...window.sbmScaleProbe.heartbeatDelays),
  }));
  // Stop at a completed projection, while finally still records diagnostics.
  assertScaleTimings(timings, readyTimes, controls);
}
