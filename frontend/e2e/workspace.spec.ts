import { expect, test, type Page } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { evidence, project, projection, rule, run } from '../src/test/fixtures';

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('sbm.locale', 'en'));
});

async function assertFits(page: Page) {
  const width = await page.evaluate(() => document.documentElement.scrollWidth);
  expect(width).toBeLessThanOrEqual(page.viewportSize()!.width);
}

test('an empty API response starts with project creation, with responsive RU and EN states', async ({
  page,
}, info) => {
  await page.route('**/api/v1/projects', (route) =>
    route.fulfill({ json: [] }),
  );
  await page.goto('/projects');
  await expect(
    page.getByRole('heading', { name: 'No projects yet' }),
  ).toBeVisible();
  await assertFits(page);
  await mkdir('output/playwright', { recursive: true });
  await page.screenshot({
    path: `output/playwright/projects-${info.project.name}.png`,
    fullPage: true,
  });
  await page
    .getByRole('button', { name: 'Create project', exact: true })
    .first()
    .click();
  await page.getByRole('button', { name: 'Use Spring fixture' }).click();
  await expect(page.getByLabel('Local repository path')).toHaveValue(
    'fixtures/spring-order-service',
  );
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  await page.getByLabel('Language').selectOption('ru');
  await expect(
    page.getByRole('heading', { name: 'Пока нет проектов' }),
  ).toBeVisible();
  await assertFits(page);
  await page.screenshot({
    path: `output/playwright/projects-ru-${info.project.name}.png`,
    fullPage: true,
  });
});

test('API failures stay visible and never become a demo project or empty success', async ({
  page,
}) => {
  await page.route('**/api/v1/projects', (route) => route.abort('failed'));
  await page.goto('/projects');
  await expect(page.getByRole('alert')).toContainText('Cannot reach the API');
  await expect(page.getByText('No projects yet')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Retry' })).toBeVisible();
  await assertFits(page);
});

test('actual ELK worker, graph controls, saved layout, Monaco evidence and review', async ({
  page,
}, info) => {
  test.setTimeout(90000);
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  let evidenceRequests = 0;
  let savedLayout: unknown;
  const layouts = new Map<string, unknown>();
  let review: unknown;
  const requestedViews: string[] = [];
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    if (path.endsWith('/projects')) return route.fulfill({ json: [project] });
    if (path.endsWith('/analysis-runs')) return route.fulfill({ json: [run] });
    if (path.endsWith(`/analysis-runs/${run.id}`))
      return route.fulfill({ json: run });
    if (path.endsWith('/canvas')) {
      requestedViews.push(url.searchParams.get('view') ?? '');
      return route.fulfill({ json: projection });
    }
    if (path.endsWith('/layout')) {
      const view = url.searchParams.get('view') ?? 'BUSINESS';
      if (request.method() === 'PUT') {
        savedLayout = request.postDataJSON();
        layouts.set(view, savedLayout);
        return route.fulfill({ status: 204 });
      }
      return route.fulfill({
        json: layouts.get(view) ?? {
          positions: {},
          viewport: { x: 0, y: 0, zoom: 1 },
        },
      });
    }
    if (path.endsWith('/evidence')) {
      evidenceRequests++;
      return route.fulfill({ json: [evidence] });
    }
    if (path.endsWith('/reviews')) {
      review = request.postDataJSON();
      return route.fulfill({ status: 204 });
    }
    if (path.endsWith(`/nodes/${rule.id}`))
      return route.fulfill({
        json: {
          ...rule,
          facts: [],
          assertions: [{ threshold: 500 }],
          enrichments: [],
          reviews: [],
        },
      });
    return route.fulfill({
      status: 500,
      json: { detail: `Unregistered test request: ${path}` },
    });
  });
  await page.goto(`/analyses/${run.id}/canvas`);
  const nodes = page.locator('.react-flow__node-semantic');
  await expect(nodes).toHaveCount(3, { timeout: 40000 });
  await expect(page.locator('.graph-overlay')).toHaveCount(0);
  expect(evidenceRequests).toBe(0);
  await page.getByRole('button', { name: 'Fit map', exact: true }).click();
  const boxes = await nodes.evaluateAll((elements) =>
    elements.map((element) => {
      const r = element.getBoundingClientRect();
      return {
        x: r.x,
        y: r.y,
        right: r.right,
        bottom: r.bottom,
        width: r.width,
        height: r.height,
      };
    }),
  );
  for (const box of boxes) {
    expect(box.width).toBeGreaterThan(20);
    expect(box.height).toBeGreaterThan(10);
  }
  for (let a = 0; a < boxes.length; a++)
    for (let b = a + 1; b < boxes.length; b++) {
      const x = boxes[a]!;
      const y = boxes[b]!;
      expect(
        x.right <= y.x + 1 ||
          y.right <= x.x + 1 ||
          x.bottom <= y.y + 1 ||
          y.bottom <= x.y + 1,
      ).toBe(true);
    }
  await assertFits(page);
  await mkdir('output/playwright', { recursive: true });
  await page.screenshot({
    path: `output/playwright/canvas-${info.project.name}.png`,
    fullPage: true,
  });
  await page.getByRole('button', { name: 'Save layout', exact: true }).click();
  await expect
    .poll(() => savedLayout)
    .toMatchObject({
      positions: {
        'rule:minimum-order': { x: expect.any(Number), y: expect.any(Number) },
      },
    });
  const ruleNode = page.locator(`.react-flow__node[data-id="${rule.id}"]`);
  await ruleNode.focus();
  await ruleNode.press('Enter');
  const drawer = page.getByTestId('evidence-drawer');
  await expect(drawer).toBeVisible();
  await expect(
    drawer.getByTestId('source-viewer').locator('.monaco-editor'),
  ).toBeVisible({ timeout: 30000 });
  await expect(
    drawer.getByText('src/main/java/OrderService.java', { exact: true }),
  ).toBeVisible();
  await page.screenshot({
    path: `output/playwright/evidence-${info.project.name}.png`,
    fullPage: true,
  });
  await drawer
    .getByRole('button', { name: 'Pin position', exact: true })
    .click();
  await expect
    .poll(() => savedLayout)
    .toMatchObject({ pinnedStableKeys: [rule.stableKey] });
  await page.reload();
  await expect(nodes).toHaveCount(3);
  await expect(page.locator('.graph-overlay')).toHaveCount(0);
  await ruleNode.focus();
  await ruleNode.press('Enter');
  await expect(
    drawer.getByRole('button', { name: 'Unpin position', exact: true }),
  ).toBeVisible();
  await drawer.getByRole('tab', { name: 'Human reviews' }).click();
  await drawer.getByRole('button', { name: 'Submit review' }).click();
  await expect
    .poll(() => review)
    .toMatchObject({
      decision: 'CONFIRMED',
      comment: '',
      editedTitle: '',
      editedDescription: '',
    });
  await drawer.getByRole('button', { name: 'Close', exact: true }).click();
  await expect(ruleNode).toBeFocused();
  const edge = page.locator('.react-flow__edge[data-id="edge-1"]');
  await edge.focus();
  await edge.press('Space');
  await expect(drawer).toBeVisible();
  await expect(
    drawer.getByRole('heading', { name: 'requires', exact: true }),
  ).toBeVisible();
  await expect(drawer.getByRole('tab', { name: 'Human reviews' })).toHaveCount(
    0,
  );
  await page.keyboard.press('Escape');
  await expect(drawer).toHaveCount(0);
  await expect(edge).toBeFocused();
  await page.getByRole('tab', { name: 'Data ownership', exact: true }).click();
  await expect(page.locator('.react-flow__node-group')).toHaveCount(2);
  await expect(page.locator('.graph-overlay')).toHaveCount(0);
  await page.getByRole('tab', { name: 'Coverage', exact: true }).click();
  await expect.poll(() => requestedViews).toContain('CONFIDENCE');
  await page.getByRole('button', { name: 'Node list', exact: true }).click();
  await expect(
    page.getByRole('button', { name: rule.label, exact: true }),
  ).toBeVisible();
  await assertFits(page);
  expect(errors).toEqual([]);
});
