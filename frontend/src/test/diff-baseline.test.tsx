import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Link, MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import DiffPage from '../pages/DiffPage';
import type { AnalysisRun, SemanticChange } from '../shared/api/types';
import { useLocale } from '../shared/lib/i18n';
import { run } from './fixtures';

const target: AnalysisRun = {
  ...run,
  id: 'target',
  createdAt: '2026-09-03T12:00:00Z',
};
const older: AnalysisRun = {
  ...run,
  id: 'older',
  createdAt: '2026-09-01T12:00:00Z',
};
const baseline: AnalysisRun = {
  ...run,
  id: 'baseline',
  status: 'PARTIALLY_SUCCEEDED',
  createdAt: '2026-09-02T12:00:00Z',
};
const cancelled: AnalysisRun = {
  ...run,
  id: 'cancelled',
  status: 'CANCELLED',
  createdAt: '2026-09-04T12:00:00Z',
};

function mount(runs: AnalysisRun[], changes: SemanticChange[] = []) {
  const fetchMock = vi.fn(async (input: string) => {
    const path = new URL(input, 'http://localhost').pathname;
    return new Response(
      JSON.stringify(
        path.endsWith('/semantic-diff')
          ? changes
          : path.endsWith('/analysis-runs')
            ? runs
            : (runs.find((item) => path.endsWith(`/${item.id}`)) ?? target),
      ),
    );
  });
  vi.stubGlobal('fetch', fetchMock);
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/analyses/target/diff']}>
        <Link to="/analyses/baseline/diff">Open another analysis</Link>
        <Routes>
          <Route path="/analyses/:analysisRunId/diff" element={<DiffPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return fetchMock;
}

beforeEach(() => useLocale.getState().setLocale('en'));

describe('diff baseline defaults from the actual run response contract', () => {
  it.each([
    [
      'renamed node',
      { stableKey: 'method:old' },
      { stableKey: 'method:new' },
      'method:old',
      'method:new',
    ],
    [
      'changed edge',
      { stableKey: 'edge:old', properties: { sourceKey: 'method:caller-old' } },
      { stableKey: 'edge:new', properties: { sourceKey: 'method:caller-new' } },
      'method:caller-old',
      'method:caller-new',
    ],
  ])(
    'opens each side of a %s at its existing node',
    async (_label, before, after, beforeKey, afterKey) => {
      mount(
        [baseline, target],
        [
          {
            id: 'change',
            subjectStableKey: 'shared-key',
            changeType: 'MODIFIED',
            impactType: 'THRESHOLD',
            before,
            after,
            beforeEvidenceIds: [],
            afterEvidenceIds: [],
            confidence: 1,
          },
        ],
      );
      await userEvent.click(await screen.findByText('shared-key'));
      expect(
        screen.getByRole('link', { name: 'Open baseline map' }),
      ).toHaveAttribute(
        'href',
        `/analyses/baseline/canvas?search=${encodeURIComponent(String(beforeKey))}`,
      );
      expect(
        screen.getByRole('link', { name: 'Open target map' }),
      ).toHaveAttribute(
        'href',
        `/analyses/target/canvas?search=${encodeURIComponent(String(afterKey))}`,
      );
    },
  );

  it('does not link to evidence on an absent side of an added node', async () => {
    mount(
      [baseline, target],
      [
        {
          id: 'change',
          subjectStableKey: 'added-key',
          changeType: 'ADDED',
          impactType: 'STRUCTURAL',
          before: null,
          after: { stableKey: 'added-key' },
          beforeEvidenceIds: [],
          afterEvidenceIds: [],
          confidence: 1,
        },
      ],
    );
    await userEvent.click(await screen.findByText('added-key'));
    expect(
      screen.queryByRole('link', { name: 'Open baseline map' }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: 'Open target map' }),
    ).toHaveAttribute('href', '/analyses/target/canvas?search=added-key');
  });

  it('uses the latest earlier completed run after a newer cancellation, preserving manual choices', async () => {
    const fetchMock = mount([
      cancelled,
      target,
      older,
      baseline,
      {
        ...older,
        id: 'failed',
        status: 'FAILED',
        createdAt: '2026-09-03T11:59:00Z',
      },
      {
        ...older,
        id: 'running',
        status: 'RUNNING',
        createdAt: '2026-09-03T11:58:00Z',
      },
      { ...older, id: 'future-success', createdAt: '2026-09-05T12:00:00Z' },
    ]);
    const from = await screen.findByLabelText('Baseline');
    expect(from).toHaveValue(baseline.id);
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(([url]) =>
          url.includes('fromAnalysisRun=baseline&toAnalysisRun=target'),
        ),
      ).toBe(true),
    );
    await userEvent.selectOptions(from, older.id);
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(([url]) =>
          url.includes('fromAnalysisRun=older&toAnalysisRun=target'),
        ),
      ).toBe(true),
    );
    expect(from).toHaveValue(older.id);
  });

  it('has no automatic baseline when no earlier successful run exists', async () => {
    const fetchMock = mount([
      cancelled,
      target,
      { ...older, status: 'FAILED' },
    ]);
    expect(await screen.findByLabelText('Baseline')).toHaveValue('');
    expect(
      fetchMock.mock.calls.some(([url]) => url.includes('/semantic-diff')),
    ).toBe(false);
  });

  it('recomputes the default for the selected target and leaves an explicit empty choice unchanged', async () => {
    mount([cancelled, target, older, baseline]);
    const from = await screen.findByLabelText('Baseline');
    await userEvent.selectOptions(screen.getByLabelText('Target'), baseline.id);
    expect(from).toHaveValue(older.id);
    await userEvent.selectOptions(from, '');
    await userEvent.selectOptions(screen.getByLabelText('Target'), target.id);
    expect(from).toHaveValue('');
  });

  it('excludes failed and cancelled runs from both revision choices', async () => {
    mount([
      cancelled,
      target,
      older,
      baseline,
      { ...older, id: 'failed', status: 'FAILED' },
    ]);
    const from = await screen.findByLabelText('Baseline');
    for (const select of [from, screen.getByLabelText('Target')]) {
      expect(select.querySelector('option[value="cancelled"]')).toBeNull();
      expect(select.querySelector('option[value="failed"]')).toBeNull();
    }
  });

  it('resets manual choices when routing to another analysis, without requesting a stale comparison', async () => {
    const fetchMock = mount([target, older, baseline]);
    const from = await screen.findByLabelText('Baseline');
    await userEvent.selectOptions(from, '');
    await userEvent.click(
      screen.getByRole('link', { name: 'Open another analysis' }),
    );
    await waitFor(() =>
      expect(screen.getByLabelText('Target')).toHaveValue(baseline.id),
    );
    expect(screen.getByLabelText('Baseline')).toHaveValue(older.id);
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(([url]) =>
          url.includes('fromAnalysisRun=older&toAnalysisRun=baseline'),
        ),
      ).toBe(true),
    );
  });
});
