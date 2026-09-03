import { expect, test } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import type { CanvasLayout } from '../src/shared/api/types';
import { project, run } from '../src/test/fixtures';
import { createLayoutServer } from './fixtures/layout-server';
import {
  boundedProjection,
  createScalableGraph,
  EDGE_LIMIT,
  PROJECTION_LIMITS,
} from './fixtures/scalable-graph';
import {
  assertScaleGates,
  assertScaleCheckpoint,
  finishScaleReport,
  graphReady,
  installScaleProbe,
  memorySampler,
  rendererGeometry,
  SCALE_LIMITS,
  timedControl,
} from './helpers/scale-probe';

test.use({ trace: 'off' });

test('@scale 10k business graph explored through bounded LOD/root projections', async ({
  page,
  browser,
}, info) => {
  const graph = createScalableGraph();
  // Twenty bounded loads, each retaining the same 35-second ready gate.
  test.setTimeout(graph.contexts.length * SCALE_LIMITS.readyMs + 180000);
  const errors: string[] = [];
  const responses: {
    root: string | null;
    detail: number;
    depth: number;
    nodes: number;
    edges: number;
  }[] = [];
  const evidenceRequests: string[] = [];
  const seen = new Set<string>();
  const controls: Record<string, number> = {};
  const canvasControls = page.locator('.canvas-controls');
  const mapToolbar = page.locator('.map-toolbar');
  const phases: {
    context: string;
    readyMs: number;
    rendered: number;
    fullyVisible: number;
  }[] = [];
  const memory: unknown[] = [];
  const layoutServer = createLayoutServer(graph.nodes);
  const pinnedPositions: CanvasLayout['positions'] = {};
  let completed = false;
  page.on('pageerror', (error) => errors.push(error.message));
  await installScaleProbe(page);
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    if (path.endsWith('/projects')) return route.fulfill({ json: [project] });
    if (path.endsWith('/analysis-runs')) return route.fulfill({ json: [run] });
    if (path.endsWith(`/analysis-runs/${run.id}`))
      return route.fulfill({ json: run });
    if (path.endsWith('/canvas')) {
      const result = boundedProjection(graph, url.searchParams);
      responses.push({
        root: url.searchParams.get('rootNodeId'),
        detail: Number(url.searchParams.get('levelOfDetail') ?? 2),
        depth: Number(url.searchParams.get('depth') ?? 2),
        nodes: result.nodes.length,
        edges: result.edges.length,
      });
      result.nodes.forEach((node) => seen.add(node.id));
      return route.fulfill({ json: result });
    }
    if (path.endsWith('/layout')) {
      if (request.method() === 'PUT') {
        return route.fulfill(layoutServer.put(request.postDataJSON()));
      }
      return route.fulfill({ json: layoutServer.get() });
    }
    const evidence = path.match(/\/semantic\/nodes\/([^/]+)\/evidence$/);
    if (evidence) {
      evidenceRequests.push(evidence[1]!);
      return route.fulfill({ json: [] });
    }
    const id = path.match(/\/semantic\/nodes\/([^/]+)$/)?.[1];
    const node = id ? graph.byId.get(id) : undefined;
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
      json: { detail: `Unexpected scale-test request: ${path}` },
    });
  });
  const sampleMemory = await memorySampler(page);
  let report!: Awaited<ReturnType<typeof finishScaleReport>>;
  const initialStart = Date.now();
  let initialReadyMs = 0;
  try {
    await page.goto(`/analyses/${run.id}/canvas`);
    await graphReady(page, PROJECTION_LIMITS[2], true);
    initialReadyMs = Date.now() - initialStart;
    expect(evidenceRequests).toHaveLength(0);
    memory.push({ phase: 'initial-250', ...(await sampleMemory()) });
    await assertScaleCheckpoint(page, [initialReadyMs], controls);
    await mapToolbar
      .getByRole('button', { name: 'Node list', exact: true })
      .click();
    for (const [detail, limit] of PROJECTION_LIMITS.entries()) {
      await timedControl(page, controls, `lod-${detail}`, async () => {
        await mapToolbar
          .getByLabel('Detail level', { exact: true })
          .selectOption(String(detail));
        await expect(page.locator('.map-status strong').first()).toHaveText(
          String(limit),
        );
      });
    }

    await mkdir('output/playwright', { recursive: true });
    for (const [index, context] of graph.contexts.entries()) {
      if (index > 0) {
        await page
          .locator('.root-indicator')
          .getByRole('button', { name: 'Show full projection', exact: true })
          .click();
        await mapToolbar
          .getByRole('button', { name: 'Node list', exact: true })
          .click();
      }
      const choose = page.locator('.node-table').getByRole('button', {
        name: context.label,
        exact: true,
      });
      await expect(choose).toBeVisible();
      await timedControl(
        page,
        controls,
        `select-context-${index}`,
        async () => {
          await choose.click();
          await expect(
            page
              .getByTestId('evidence-drawer')
              .getByRole('heading', { name: context.label, exact: true }),
          ).toBeVisible();
        },
      );
      await page
        .getByTestId('evidence-drawer')
        .getByRole('button', { name: 'Pin position', exact: true })
        .click();
      const started = Date.now();
      await page
        .getByTestId('evidence-drawer')
        .getByRole('button', { name: 'Explore this node', exact: true })
        .click();
      await expect(page.getByTestId('evidence-drawer')).toHaveCount(0);
      await expect(page.locator('.root-indicator')).toContainText(context.id);
      await expect(page.locator('.map-status strong').first()).toHaveText(
        '500',
      );
      await mapToolbar
        .getByRole('button', { name: 'Graph', exact: true })
        .click();
      await graphReady(page, 500, true);
      const readyMs = Date.now() - started;
      const geometry = await rendererGeometry(page);
      expect(geometry.rendered).toBeLessThanOrEqual(500);
      expect(geometry.documentWidth).toBeLessThanOrEqual(
        page.viewportSize()!.width,
      );
      phases.push({
        context: context.label,
        readyMs,
        rendered: geometry.rendered,
        fullyVisible: geometry.fullyVisible,
      });
      await timedControl(page, controls, `lock-context-${index}`, async () => {
        await canvasControls
          .getByRole('button', { name: 'Lock map', exact: true })
          .click();
        await canvasControls
          .getByRole('button', { name: 'Unlock map', exact: true })
          .click();
      });
      await timedControl(page, controls, `save-context-${index}`, async () => {
        const response = page.waitForResponse(
          (response) =>
            new URL(response.url()).pathname.endsWith('/layout') &&
            response.request().method() === 'PUT',
        );
        await canvasControls
          .getByRole('button', { name: 'Save layout', exact: true })
          .click();
        expect((await response).status()).toBe(200);
      });
      const saved = layoutServer.get();
      expect(Object.keys(saved.positions).length).toBeLessThanOrEqual(2000);
      expect(saved.positions).toMatchObject(pinnedPositions);
      expect(saved.positions[context.stableKey]).toBeDefined();
      pinnedPositions[context.stableKey] = saved.positions[context.stableKey]!;
      for (const node of graph.members.get(context.id)!)
        expect(saved.positions[node.stableKey]).toBeDefined();
      expect(layoutServer.rejected).toEqual([]);
      if ([0, 9, 19].includes(index)) {
        await page.screenshot({
          path: `output/playwright/progressive-10k-${info.project.name}-${index + 1}.png`,
        });
        memory.push({
          phase: `context-${index + 1}`,
          ...(await sampleMemory()),
        });
      }
      await assertScaleCheckpoint(page, [readyMs], controls);
    }
    expect(seen.size).toBe(10000);
    expect(
      new Set(responses.map((response) => response.root).filter(Boolean)).size,
    ).toBe(20);
    for (const response of responses) {
      expect(response.nodes).toBeLessThanOrEqual(
        PROJECTION_LIMITS[response.detail]!,
      );
      expect(response.edges).toBeLessThanOrEqual(EDGE_LIMIT);
      expect(response.depth).toBeGreaterThanOrEqual(1);
      expect(response.depth).toBeLessThanOrEqual(5);
    }
    expect(
      evidenceRequests.every((id) =>
        graph.contexts.some((context) => context.id === id),
      ),
    ).toBe(true);
    expect(errors).toEqual([]);
    completed = true;
  } finally {
    report = await finishScaleReport(page, info, 'progressive-10k', {
      functionalScenarioCompleted: completed,
      browser: browser.version(),
      totalFixtureNodes: graph.nodes.length,
      totalFixtureEdges: graph.edges.length,
      discoveredNodes: seen.size,
      initialReadyMs,
      phases,
      responses,
      controls,
      evidenceRequests,
      memory,
      errors,
      layoutPersistence: {
        limit: 2000,
        semantics:
          'Full replacement; current projection and previously pinned positions must survive each save.',
        acceptedPositionCounts: layoutServer.accepted.map(
          (layout) => Object.keys(layout.positions).length,
        ),
        rejected: layoutServer.rejected,
        retainedPins: Object.keys(pinnedPositions).length,
      },
    });
  }
  assertScaleGates(
    report,
    [initialReadyMs, ...phases.map((phase) => phase.readyMs)],
    controls,
  );
});
