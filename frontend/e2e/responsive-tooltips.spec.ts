import { expect, test, type Locator, type Page } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { project, projection, run } from '../src/test/fixtures';

async function assertFits(page: Page) {
  const width = await page.evaluate(() => document.documentElement.scrollWidth);
  expect(width).toBeLessThanOrEqual(page.viewportSize()!.width);
}

async function assertTooltipFits(page: Page, button: Locator) {
  const label = await button.getAttribute('aria-label');
  const tooltip = page.getByRole('tooltip', { name: label!, exact: true });
  await expect(tooltip).toBeVisible();
  const box = await tooltip.boundingBox();
  const viewport = page.viewportSize()!;
  expect(box).not.toBeNull();
  expect(box!.x).toBeGreaterThanOrEqual(8);
  expect(box!.x + box!.width).toBeLessThanOrEqual(viewport.width - 8);
  expect(box!.y).toBeGreaterThanOrEqual(8);
  expect(box!.y + box!.height).toBeLessThanOrEqual(viewport.height - 8);
  await assertFits(page);
}

test('long RU canvas content and edge tooltips fit while loading, hovered and keyboard focused', async ({
  page,
}, info) => {
  test.setTimeout(90000);
  const partialRun = { ...run, status: 'PARTIALLY_SUCCEEDED' };
  const longProject = {
    ...project,
    name: 'Проверка согласования заказов корпоративных клиентов и правил минимальной суммы после изменения условий договора',
  };
  const longProjection = {
    ...projection,
    nodes: projection.nodes.map((node) => ({
      ...node,
      label: `Проверка минимальной суммы заказа корпоративного клиента: ${node.label}`,
      subtitle:
        'После изменения порога требуется повторное подтверждение бизнес-правила ответственным сотрудником',
    })),
  };
  let releaseCanvas!: () => void;
  const canvasReady = new Promise<void>((resolve) => {
    releaseCanvas = resolve;
  });
  await page.addInitScript(() => localStorage.setItem('sbm.locale', 'ru'));
  await page.route('**/api/v1/**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/projects'))
      return route.fulfill({ json: [longProject] });
    if (path.endsWith('/analysis-runs'))
      return route.fulfill({ json: [partialRun] });
    if (path.endsWith(`/analysis-runs/${run.id}`))
      return route.fulfill({ json: partialRun });
    if (path.endsWith('/canvas')) {
      await canvasReady;
      return route.fulfill({ json: longProjection });
    }
    if (path.endsWith('/layout'))
      return route.fulfill({
        json: { positions: {}, viewport: { x: 0, y: 0, zoom: 1 } },
      });
    return route.fulfill({
      status: 500,
      json: { detail: `Unregistered test request: ${path}` },
    });
  });
  try {
    await page.goto(`/analyses/${run.id}/canvas`);
    await expect(page.locator('.analysis-progress .notice')).toContainText(
      'Частичный результат',
    );
    const refresh = page.getByRole('button', { name: 'Обновить', exact: true });
    await expect(refresh).toBeDisabled();
    await assertFits(page);
    releaseCanvas();
    await expect(page.locator('.react-flow__node-semantic')).toHaveCount(3, {
      timeout: 40000,
    });
    await expect(page.locator('.graph-overlay')).toHaveCount(0);
    await expect(refresh).toBeEnabled();
    await assertFits(page);

    const buttons = [
      refresh,
      page.getByRole('button', { name: 'Заблокировать карту', exact: true }),
    ];
    if (info.project.name === 'mobile')
      buttons.push(
        page.getByRole('button', { name: 'Открыть навигацию', exact: true }),
      );
    await mkdir('output/playwright', { recursive: true });
    for (const [index, button] of buttons.entries()) {
      await button.hover();
      await assertTooltipFits(page, button);
      await page.screenshot({
        path: `output/playwright/ru-tooltip-${info.project.name}-${index}.png`,
        fullPage: true,
      });
      await page.mouse.move(0, 0);
      await expect(page.getByRole('tooltip')).toHaveCount(0);
      await button.focus();
      await assertTooltipFits(page, button);
      await page.keyboard.press('Escape');
      await expect(page.getByRole('tooltip')).toHaveCount(0);
      await assertFits(page);
      await page.keyboard.press('Tab');
      await page.getByLabel('Поиск по карте').focus();
      await expect(page.getByRole('tooltip')).toHaveCount(0);
    }
    await page.screenshot({
      path: `output/playwright/ru-canvas-long-${info.project.name}.png`,
      fullPage: true,
    });
  } finally {
    releaseCanvas();
  }
});
