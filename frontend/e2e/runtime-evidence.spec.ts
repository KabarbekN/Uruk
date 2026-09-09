import { expect, test } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { evidence, project, rule, run } from '../src/test/fixtures';

test('runtime import keeps source evidence inside the desktop and mobile workspace', async ({
  page,
}, info) => {
  const errors: string[] = [];
  const imports: unknown[] = [];
  let evidenceRequests = 0;
  page.on('pageerror', (error) => errors.push(error.message));
  await page.addInitScript(() => localStorage.setItem('sbm.locale', 'en'));
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path.endsWith('/projects')) return route.fulfill({ json: [project] });
    if (path.endsWith('/analysis-runs')) return route.fulfill({ json: [run] });
    if (path.endsWith(`/analysis-runs/${run.id}`))
      return route.fulfill({ json: run });
    if (path.endsWith('/runtime/traces')) {
      imports.push(request.postDataJSON());
      return route.fulfill({
        json: {
          status: 'INGESTED',
          acceptedSpans: 1,
          duplicateSpans: 0,
          matchedSpans: 1,
          unresolvedSpans: 0,
          coverageScope: 'UPLOADED_SPANS_ONLY',
        },
      });
    }
    if (path.endsWith('/runtime'))
      return route.fulfill({
        json: {
          analysisRunId: run.id,
          spans: imports.length
            ? [
                {
                  id: '40000000-0000-4000-8000-000000000001',
                  traceId: '12345678901234567890123456789012',
                  spanId: '1234567890123456',
                  parentSpanId: null,
                  name: 'Create order',
                  kind: 2,
                  startNanos: '1788500000000000000',
                  endNanos: '1788500000000000123',
                  attributes: {},
                  resourceAttributes: {},
                  scope: {},
                  status: {},
                  events: [],
                  links: [],
                  matchedNodeId: rule.id,
                  matchStatus: 'MATCHED_STABLE_KEY',
                  createdAt: run.createdAt,
                },
              ]
            : [],
          summary: {
            totalSpans: imports.length,
            traceCount: imports.length,
            matchedSpans: imports.length,
            unresolvedSpans: 0,
            observedNodes: imports.length,
            notObservedNodes: 3 - imports.length,
            coverageScope: 'UPLOADED_SPANS_ONLY',
            wholeApplicationCoverageKnown: false,
            notObservedMeaning: 'NOT_OBSERVED_IN_UPLOADED_TRACES',
          },
          observedPaths: [],
          pathsTruncated: false,
          offset: 0,
          limit: 50,
          hasMore: false,
        },
      });
    if (path.endsWith('/evidence')) {
      evidenceRequests++;
      return route.fulfill({ json: [evidence] });
    }
    if (path.endsWith(`/nodes/${rule.id}`))
      return route.fulfill({
        json: {
          ...rule,
          facts: [],
          assertions: [],
          enrichments: [],
          reviews: [],
        },
      });
    return route.fulfill({
      status: 500,
      json: { detail: `Unexpected runtime UI request: ${path}` },
    });
  });
  await page.goto(`/analyses/${run.id}/runtime`);
  await expect(
    page.getByRole('heading', { name: 'No runtime observations yet' }),
  ).toBeVisible();
  const body = {
    resourceSpans: [
      {
        scopeSpans: [
          {
            spans: [
              {
                traceId: '12345678901234567890123456789012',
                spanId: '1234567890123456',
                name: 'Create order',
                kind: 2,
                startTimeUnixNano: '1788500000000000000',
                endTimeUnixNano: '1788500000000000123',
                attributes: [
                  {
                    key: 'semantic.stable_key',
                    value: { stringValue: rule.stableKey },
                  },
                ],
              },
            ],
          },
        ],
      },
    ],
  };
  await page.getByLabel('OTLP JSON file').setInputFiles({
    name: 'trace.json',
    mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify(body)),
  });
  await page
    .getByRole('button', { name: 'Import traces', exact: true })
    .click();
  await expect(page.getByText('0.000123', { exact: true })).toBeVisible();
  expect(imports).toEqual([body]);
  expect(evidenceRequests).toBe(0);
  const source = page.getByRole('button', {
    name: 'Source evidence',
    exact: true,
  });
  await source.click();
  const drawer = page.getByTestId('evidence-drawer');
  await expect(drawer.locator('.monaco-editor')).toBeVisible();
  const frame = await drawer.boundingBox();
  const viewport = page.viewportSize()!;
  expect(frame).not.toBeNull();
  expect(frame!.x).toBeGreaterThanOrEqual(0);
  expect(frame!.x + frame!.width).toBeLessThanOrEqual(viewport.width);
  expect(frame!.y).toBeGreaterThanOrEqual(0);
  expect(frame!.y + frame!.height).toBeLessThanOrEqual(viewport.height);
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth),
  ).toBeLessThanOrEqual(viewport.width);
  await mkdir('output/playwright', { recursive: true });
  await page.screenshot({
    path: `output/playwright/runtime-evidence-${info.project.name}.png`,
    fullPage: true,
  });
  await page.keyboard.press('Escape');
  await expect(drawer).toHaveCount(0);
  await expect(source).toBeFocused();
  expect(errors).toEqual([]);
});
