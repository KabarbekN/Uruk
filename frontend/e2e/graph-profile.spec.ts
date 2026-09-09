import { expect, test, type JSHandle } from '@playwright/test';
import { writeFile } from 'node:fs/promises';
import { project, run } from '../src/test/fixtures';
import { largeProjection } from './fixtures/layered-graph';
import { createLayoutServer } from './fixtures/layout-server';
import {
  createScalableGraph,
  overviewLayout,
  rendererStressProjection,
} from './fixtures/scalable-graph';
import { profileMark, startGraphProfile } from './helpers/cdp-profile';
import { graphReady, painted, rendererGeometry } from './helpers/scale-probe';
import { createZeroEdgeZIndexProbe } from './helpers/zero-edge-zindex';

function geometrySignature(surface: Element) {
  return JSON.stringify({
    nodes: [...surface.querySelectorAll<HTMLElement>('.react-flow__node')].map(
      (node) => [
        node.dataset.id,
        node.style.transform,
        node.style.width,
        node.style.height,
        node.style.zIndex,
      ],
    ),
    edges: [...surface.querySelectorAll('.react-flow__edge')].map((edge) => [
      edge.getAttribute('data-id'),
      [...edge.querySelectorAll('path')].map((path) => [
        path.getAttribute('d'),
        path.getAttribute('marker-start'),
        path.getAttribute('marker-end'),
        path.getAttribute('stroke-width'),
        path.getAttribute('style'),
      ]),
    ]),
  });
}

// CDP supplies the trace. Playwright DOM snapshots distort large-graph diagnostics.
test.use({ trace: 'off' });

test('opt-in CDP graph first-paint and selection diagnostic', async ({
  page,
  browser,
}, info) => {
  test.skip(
    process.env.SBM_PROFILE !== '1' || info.project.name !== 'desktop',
    'Explicit diagnostic only; not a performance acceptance result.',
  );
  test.setTimeout(120000);
  const count = process.env.SBM_PROFILE_NODES === '2000' ? 2000 : 1000;
  const zeroZIndex = process.env.SBM_PROFILE_ZERO_ZINDEX === '1';
  if (
    zeroZIndex &&
    (count !== 2000 ||
      process.env.SBM_PROFILE_COMPOSITING === '1' ||
      process.env.SBM_PROFILE_EDGE_HITTEST === '1')
  )
    throw new Error(
      'Zero-z-index A/B/A requires isolated 2000-node mode, without other CSS experiments',
    );
  const label =
    process.env.SBM_PROFILE_LABEL ||
    (zeroZIndex ? `zero-zindex-${Date.now()}` : 'cdp-only');
  if (!/^[a-zA-Z0-9_-]{1,80}$/.test(label))
    throw new Error('Invalid profile artifact label');
  const name = `profile-${count}-${label}`;
  if (count === 2000) await page.setViewportSize({ width: 1920, height: 1080 });
  const graph =
    count === 2000
      ? rendererStressProjection(createScalableGraph())
      : largeProjection();
  const layoutServer = createLayoutServer(
    graph.nodes,
    count === 2000 ? overviewLayout(graph.nodes) : undefined,
  );
  const errors: string[] = [];
  const edgeEvidenceRequests: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path.endsWith('/projects')) return route.fulfill({ json: [project] });
    if (path.endsWith('/analysis-runs')) return route.fulfill({ json: [run] });
    if (path.endsWith(`/analysis-runs/${run.id}`))
      return route.fulfill({ json: run });
    if (path.endsWith('/canvas')) return route.fulfill({ json: graph });
    if (path.endsWith('/layout'))
      return route.fulfill(
        request.method() === 'PUT'
          ? layoutServer.put(request.postDataJSON())
          : { json: layoutServer.get() },
      );
    if (path.endsWith('/evidence')) {
      const edgeId = path.match(/\/semantic\/edges\/([^/]+)\/evidence$/)?.[1];
      if (edgeId) edgeEvidenceRequests.push(edgeId);
      return route.fulfill({ json: [] });
    }
    const id = path.match(/\/semantic\/nodes\/([^/]+)$/)?.[1];
    const node = graph.nodes.find((node) => node.id === id);
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
      json: { detail: `Unexpected diagnostic request: ${path}` },
    });
  });
  const finish = await startGraphProfile(page, info, name);
  let completed = false;
  const geometry: unknown[] = [];
  const controls: Record<string, number> = {};
  const dispatches: Record<
    string,
    { action: string; dispatchMs: number; paintMs: number }[]
  > = {};
  const zIndexGuards: unknown[] = [];
  let zeroProbe:
    JSHandle<ReturnType<typeof createZeroEdgeZIndexProbe>> | undefined;
  let baselineSignature = '';
  const surface = page.locator('.graph-surface');
  const checkpoint = (stage: string, extra: Record<string, unknown> = {}) =>
    writeFile(
      `output/playwright/${name}-${info.project.name}-progress.json`,
      JSON.stringify(
        {
          stage,
          completed,
          controls,
          dispatches,
          geometry,
          zIndexGuards,
          edgeEvidenceRequests,
          errors,
          ...extra,
        },
        null,
        2,
      ),
    );
  try {
    await page.goto(`/analyses/${run.id}/canvas`);
    await page.evaluate(() => document.fonts.ready);
    await graphReady(page, count);
    await profileMark(page, 'graph-ready');
    if (zeroZIndex) {
      expect(graph.edges).toHaveLength(4200);
      await expect(page.locator('.semantic-node.compact')).toHaveCount(2000);
      await expect(page.locator('.react-flow__node-group')).toHaveCount(0);
      await expect(page.locator('.react-flow__edge')).toHaveCount(4200);
      expect((await rendererGeometry(page)).fullyVisible).toBe(2000);
      baselineSignature = await surface.evaluate(geometrySignature);
      zeroProbe = await surface.evaluateHandle(createZeroEdgeZIndexProbe);
    }
    geometry.push({ phase: 'initial', ...(await rendererGeometry(page)) });
    await page.screenshot({
      path: `output/playwright/${name}-initial.png`,
    });
    const pan = async (phase: string) => {
      await profileMark(page, phase);
      const frame = await page.locator('.graph-surface').boundingBox();
      const before = await page
        .locator('.react-flow__viewport')
        .getAttribute('style');
      const start = Date.now();
      const measure = async (action: string, dispatch: () => Promise<void>) => {
        const at = Date.now();
        await dispatch();
        const sent = Date.now();
        await painted(page);
        (dispatches[phase] ??= []).push({
          action,
          dispatchMs: sent - at,
          paintMs: Date.now() - sent,
        });
      };
      await measure('move-to-pane', () =>
        page.mouse.move(frame!.x + 12, frame!.y + 12),
      );
      await measure('down', () => page.mouse.down());
      for (let step = 1; step <= 4; step++)
        await measure(`move-${step}`, () =>
          page.mouse.move(frame!.x + 12 + step * 10, frame!.y + 12 + step * 5),
        );
      await measure('up', () => page.mouse.up());
      await expect(page.locator('.react-flow__viewport')).not.toHaveAttribute(
        'style',
        before!,
      );
      await painted(page);
      controls[phase] = Date.now() - start;
      await profileMark(page, `${phase}-end`);
      const measuredGeometry = await rendererGeometry(page);
      geometry.push({ phase, ...measuredGeometry });
      if (zeroZIndex) {
        expect(measuredGeometry.rendered).toBe(2000);
        expect(measuredGeometry.fullyVisible).toBe(2000);
        await expect(page.locator('.react-flow__edge')).toHaveCount(4200);
        expect(await surface.evaluate(geometrySignature)).toBe(
          baselineSignature,
        );
      }
      await checkpoint(phase);
    };
    await pan('pan-original');
    if (zeroProbe) {
      await profileMark(page, 'enable-zero-zindex');
      const enabled = await zeroProbe.evaluate((probe) => probe.enable());
      zIndexGuards.push({ phase: 'enabled', ...enabled });
      expect(enabled).toMatchObject({
        candidates: 4200,
        overridden: 4200,
        selectedOverridden: 0,
      });
      await painted(page);
      await pan('pan-zero-auto');
      await profileMark(page, 'restore-zero-zindex');
      zIndexGuards.push({
        phase: 'restored',
        ...(await zeroProbe.evaluate((probe) => probe.restore())),
      });
      await painted(page);
      expect(
        await surface
          .locator('.react-flow__edges > svg')
          .evaluateAll(
            (svgs) =>
              svgs.filter(
                (svg) => (svg as SVGSVGElement).style.zIndex === 'auto',
              ).length,
          ),
      ).toBe(0);
      await pan('pan-restored');

      // Actual pointer hit-testing and evidence request, outside the pan measurements.
      await profileMark(page, 'edge-click-guard');
      await zeroProbe.evaluate((probe) => probe.enable());
      const hit = await surface.evaluate((element) => {
        const frame = element.getBoundingClientRect();
        const edges = [...element.querySelectorAll('.react-flow__edge')].slice(
          0,
          24,
        );
        for (const edge of edges) {
          const path = edge.querySelector<SVGPathElement>(
            '.react-flow__edge-interaction',
          );
          const matrix = path?.getScreenCTM();
          if (!path || !matrix) continue;
          for (const fraction of [0.5, 0.25, 0.75]) {
            const point = path
              .getPointAtLength(path.getTotalLength() * fraction)
              .matrixTransform(matrix);
            if (
              point.x <= frame.left ||
              point.x >= frame.right ||
              point.y <= frame.top ||
              point.y >= frame.bottom
            )
              continue;
            if (
              document
                .elementFromPoint(point.x, point.y)
                ?.closest('.react-flow__edge') === edge
            )
              return {
                id: edge.getAttribute('data-id')!,
                x: point.x,
                y: point.y,
              };
          }
        }
        throw new Error(
          'No unoccluded edge hit path found within the bounded guard sample',
        );
      });
      await page.mouse.click(hit.x, hit.y);
      const drawer = page.getByTestId('evidence-drawer');
      await expect(drawer).toBeVisible();
      await expect.poll(() => edgeEvidenceRequests.includes(hit.id)).toBe(true);
      const selectedEdge = page.locator(
        `.react-flow__edge[data-id="${hit.id}"]`,
      );
      await expect(selectedEdge).toHaveClass(/selected/);
      const selectedStyle = await selectedEdge.evaluate((edge) => ({
        inline: (edge.parentElement as unknown as SVGSVGElement).style.zIndex,
        computed: getComputedStyle(edge.parentElement!).zIndex,
        pointerEvents: getComputedStyle(
          edge.querySelector('.react-flow__edge-interaction')!,
        ).pointerEvents,
      }));
      expect(selectedStyle.inline).not.toBe('auto');
      expect(selectedStyle.computed).toBe(selectedStyle.inline);
      expect(selectedStyle.pointerEvents).not.toBe('none');
      const selectedGuard = await zeroProbe.evaluate((probe) =>
        probe.inspect(),
      );
      expect(selectedGuard.selectedOverridden).toBe(0);
      zIndexGuards.push({
        phase: 'real-edge-click',
        id: hit.id,
        ...selectedStyle,
        ...selectedGuard,
      });
      await drawer.getByRole('button', { name: 'Close', exact: true }).click();
      await expect(drawer).toHaveCount(0);
      await zeroProbe.evaluate((probe) => probe.restore());
      await expect(page.locator('.react-flow__edge')).toHaveCount(4200);
      await expect
        .poll(() => surface.evaluate(geometrySignature))
        .toBe(baselineSignature);
      await checkpoint('edge-click-guard');
    }
    if (process.env.SBM_PROFILE_COMPOSITING === '1') {
      await page.addStyleTag({
        content: '.react-flow__viewport { will-change: transform; }',
      });
      await painted(page);
      await pan('pan-composited');
    }
    if (process.env.SBM_PROFILE_EDGE_HITTEST === '1') {
      const style = await page.addStyleTag({
        content:
          '.react-flow:has(.react-flow__pane.dragging) .react-flow__edge, .react-flow:has(.react-flow__pane.dragging) .react-flow__edge * { pointer-events: none !important; }',
      });
      await painted(page);
      await pan('pan-no-edge-hits');
      expect(
        await page
          .locator('.react-flow__edge')
          .first()
          .evaluate((edge) => getComputedStyle(edge).pointerEvents),
      ).not.toBe('none');
      await style.evaluate((element) =>
        element.parentNode?.removeChild(element),
      );
    }
    await profileMark(page, 'select-node');
    await page.locator('.react-flow__node-semantic').first().click();
    await expect(page.getByTestId('evidence-drawer')).toBeVisible();
    if (zeroZIndex) {
      const zIndex = await page
        .locator('.react-flow__node-semantic.selected')
        .first()
        .evaluate((node) => getComputedStyle(node).zIndex);
      expect(Number(zIndex)).toBeGreaterThan(0);
      zIndexGuards.push({ phase: 'selected-node', zIndex });
    }
    await painted(page);
    await profileMark(page, 'close-selection');
    await page
      .getByTestId('evidence-drawer')
      .getByRole('button', { name: 'Close', exact: true })
      .click();
    await expect(page.getByTestId('evidence-drawer')).toHaveCount(0);
    await painted(page);
    await profileMark(page, 'lock');
    await page
      .locator('.canvas-controls')
      .getByRole('button', { name: 'Lock map', exact: true })
      .click();
    await page
      .locator('.canvas-controls')
      .getByRole('button', { name: 'Unlock map', exact: true })
      .click();
    await painted(page);
    await profileMark(page, 'complete');
    geometry.push({
      phase: 'after-interaction',
      ...(await rendererGeometry(page)),
    });
    expect(errors).toEqual([]);
    expect(layoutServer.rejected).toEqual([]);
    completed = true;
  } finally {
    if (zeroProbe) {
      try {
        zIndexGuards.push({
          phase: 'finally-restored',
          ...(await zeroProbe.evaluate((probe) => probe.restore())),
        });
        await zeroProbe.dispose();
      } catch (error) {
        zIndexGuards.push({ phase: 'cleanup-error', error: String(error) });
      }
    }
    await checkpoint('finalizing');
    await finish({
      completed,
      nodes: graph.nodes.length,
      edges: graph.edges.length,
      browser: browser.version(),
      geometry,
      errors,
      controls,
      dispatches,
      zIndexGuards,
      edgeEvidenceRequests,
      layoutRejections: layoutServer.rejected,
      experiment: zeroZIndex
        ? 'Test-only zero-z-index A/B/A; all 2000 nodes and 4200 edges retained. Pan includes four equal dispatch/paint steps. Not a performance gate run.'
        : undefined,
    });
  }
});
