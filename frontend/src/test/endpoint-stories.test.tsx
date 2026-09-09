import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';
import { EndpointStories } from '../features/endpoint-stories/EndpointStories';

beforeEach(() => {
  vi.restoreAllMocks();
});

it('renders progressively enriched endpoint stories and opens the selected source flow', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify([
          {
            controllerKey: 'java:type:example.orders.OrderController',
            controllerName: 'OrderController',
            packageName: 'example.orders',
            filePath: 'src/main/java/example/orders/OrderController.java',
            endpointCount: 2,
            endpoints: [
              {
                id: 'endpoint-create',
                label: 'create',
                method: 'POST',
                path: '/api/orders',
                methodName: 'create',
                hasAi: true,
                aiTitle: 'Создание заказа',
                aiDescription: 'Проверяет заказ и сохраняет его.',
                scenarioId: 'scenario-create',
                stepCount: 7,
                sideEffectCount: 2,
                unresolvedCount: 1,
                pathOrderKnown: false,
                storyTruncated: false,
                storyStatus: 'READY',
              },
              {
                id: 'endpoint-get',
                label: 'get',
                method: 'GET',
                path: '/api/orders/{id}',
                methodName: 'get',
                hasAi: false,
                scenarioId: 'scenario-get',
                stepCount: 3,
                sideEffectCount: 1,
                unresolvedCount: 0,
                pathOrderKnown: false,
                storyTruncated: false,
                storyStatus: 'ANALYZING',
              },
            ],
          },
        ]),
      ),
    ),
  );
  const select = vi.fn();
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <EndpointStories runId="run-1" active={false} onSelectEndpoint={select} />
    </QueryClientProvider>,
  );

  expect(await screen.findByText('Создание заказа')).toBeInTheDocument();
  expect(screen.getByText('История готова')).toBeInTheDocument();
  expect(screen.getByText('ИИ анализирует')).toBeInTheDocument();
  expect(screen.getByText('7 находок')).toBeInTheDocument();
  expect(screen.getByText('2 эффектов')).toBeInTheDocument();
  expect(screen.getByText('1 не разрешено')).toBeInTheDocument();

  await userEvent.click(screen.getByText('Создание заказа'));
  expect(select).toHaveBeenCalledWith(
    expect.objectContaining({ id: 'endpoint-create' }),
    expect.objectContaining({ controllerName: 'OrderController' }),
  );
});
