import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import RuntimePage from '../pages/RuntimePage';
import {
  durationMilliseconds,
  runtimeSchema,
} from '../features/runtime-evidence/api';
import { useLocale } from '../shared/lib/i18n';
import { run, rule } from './fixtures';

vi.mock('../features/evidence-drawer/EvidenceDrawer', () => ({
  EvidenceDrawer: ({ selection }: { selection: { id: string } }) => (
    <div role="dialog">Evidence for {selection.id}</div>
  ),
}));

const span = {
  id: '40000000-0000-4000-8000-000000000001',
  traceId: '12345678901234567890123456789012',
  spanId: '1234567890123456',
  parentSpanId: null,
  name: 'Charge payment',
  kind: 3,
  startNanos: '1788500000000000000',
  endNanos: '1788500000000000123',
  attributes: {
    'server.address': 'payment.example',
    'semantic.stable_key': rule.stableKey,
  },
  resourceAttributes: {},
  scope: {},
  status: { code: 1 },
  events: [],
  links: [],
  matchedNodeId: rule.id,
  matchStatus: 'MATCHED_STABLE_KEY',
  createdAt: run.createdAt,
};
const snapshot = {
  analysisRunId: run.id,
  spans: [span],
  summary: {
    totalSpans: 1,
    traceCount: 1,
    matchedSpans: 1,
    unresolvedSpans: 0,
    observedNodes: 1,
    notObservedNodes: 100,
    coverageScope: 'UPLOADED_SPANS_ONLY',
    wholeApplicationCoverageKnown: false,
    notObservedMeaning: 'NOT_OBSERVED_IN_UPLOADED_TRACES',
  },
  observedPaths: [],
  pathsTruncated: false,
  offset: 0,
  limit: 50,
  hasMore: false,
};
function mount(data = snapshot, status = 'SUCCEEDED') {
  let imported = false;
  const fetchMock = vi.fn(async (input: string, init?: RequestInit) => {
    const url = new URL(input, 'http://localhost');
    if (init?.method === 'POST') {
      imported = true;
      return new Response(
        JSON.stringify({
          status: 'INGESTED',
          acceptedSpans: 1,
          duplicateSpans: 0,
          matchedSpans: 1,
          unresolvedSpans: 0,
          coverageScope: 'UPLOADED_SPANS_ONLY',
        }),
      );
    }
    return new Response(
      JSON.stringify(
        url.pathname.endsWith('/runtime')
          ? imported
            ? snapshot
            : {
                ...data,
                offset: Number(url.searchParams.get('offset')),
                hasMore: url.searchParams.get('offset') === '0' && data.hasMore,
              }
          : { ...run, status },
      ),
    );
  });
  vi.stubGlobal('fetch', fetchMock);
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/analyses/${run.id}/runtime`]}>
        <Routes>
          <Route
            path="/analyses/:analysisRunId/runtime"
            element={<RuntimePage />}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return fetchMock;
}
beforeEach(() => useLocale.getState().setLocale('en'));

describe('runtime evidence', () => {
  it('preserves nanosecond precision and rejects whole-application coverage claims', () => {
    expect(durationMilliseconds(span.startNanos, span.endNanos)).toBe(
      '0.000123',
    );
    expect(runtimeSchema.safeParse(snapshot).success).toBe(true);
    expect(
      runtimeSchema.safeParse({
        ...snapshot,
        summary: { ...snapshot.summary, wholeApplicationCoverageKnown: true },
      }).success,
    ).toBe(false);
    expect(
      runtimeSchema.safeParse({
        ...snapshot,
        spans: [{ ...span, startNanos: Number(span.startNanos) }],
      }).success,
    ).toBe(false);
  });
  it('shows observations and opens matched source evidence only on request', async () => {
    const fetchMock = mount();
    expect(await screen.findByText('Charge payment')).toBeVisible();
    expect(screen.getByText('0.000123')).toBeVisible();
    expect(
      screen.getByText(/whole-application coverage is unknown/),
    ).toBeVisible();
    expect(
      fetchMock.mock.calls.some(([url]) => url.includes('/semantic/')),
    ).toBe(false);
    await userEvent.click(
      screen.getByRole('button', { name: 'Source evidence' }),
    );
    expect(await screen.findByRole('dialog')).toHaveTextContent(rule.id);
  });
  it('paginates spans through bounded requests', async () => {
    const fetchMock = mount({
      ...snapshot,
      hasMore: true,
      summary: { ...snapshot.summary, totalSpans: 51 },
    });
    await userEvent.click(
      await screen.findByRole('button', { name: 'Next page' }),
    );
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(([url]) =>
          url.endsWith('/runtime?limit=50&offset=50'),
        ),
      ).toBe(true),
    );
    expect(await screen.findByText('Page 2')).toBeVisible();
  });
  it('does not offer import until static matching is complete', async () => {
    mount(snapshot, 'RUNNING');
    expect(await screen.findByLabelText('OTLP JSON file')).toBeDisabled();
    expect(
      screen.getByRole('button', { name: 'Import traces' }),
    ).toBeDisabled();
    expect(
      screen.getByText(/after the static analysis completes/),
    ).toBeVisible();
  });
  it('rejects oversized files before making a request', async () => {
    const fetchMock = mount();
    const file = new File(['x'.repeat(1_048_577)], 'oversized.json', {
      type: 'application/json',
    });
    await userEvent.upload(
      await screen.findByLabelText('OTLP JSON file'),
      file,
    );
    expect(await screen.findByRole('alert')).toHaveTextContent('exceeds 1 MiB');
    expect(
      fetchMock.mock.calls.some(([, init]) => init?.method === 'POST'),
    ).toBe(false);
  });
  it('imports user-selected OTLP and refreshes the observations', async () => {
    const fetchMock = mount({
      ...snapshot,
      spans: [],
      summary: { ...snapshot.summary, totalSpans: 0 },
    });
    const body = JSON.stringify({
      resourceSpans: [{ scopeSpans: [{ spans: [] }] }],
    });
    const file = new File([body], 'trace.json', { type: 'application/json' });
    Object.defineProperty(file, 'text', { value: async () => body });
    await userEvent.upload(
      await screen.findByLabelText('OTLP JSON file'),
      file,
    );
    await userEvent.click(
      screen.getByRole('button', { name: 'Import traces' }),
    );
    expect(await screen.findByText('Charge payment')).toBeVisible();
    expect(
      fetchMock.mock.calls.some(
        ([url, init]) =>
          url.endsWith('/runtime/traces') &&
          init?.method === 'POST' &&
          init.body === body,
      ),
    ).toBe(true);
    expect(
      screen.getByRole('button', { name: 'Import traces' }),
    ).toBeDisabled();
  });
});
