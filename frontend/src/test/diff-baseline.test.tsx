import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import DiffPage from '../pages/DiffPage';
import type { AnalysisRun } from '../shared/api/types';
import { useLocale } from '../shared/lib/i18n';
import { run } from './fixtures';

const target: AnalysisRun = { ...run, id: 'target', createdAt: '2026-09-03T12:00:00Z' };
const older: AnalysisRun = { ...run, id: 'older', createdAt: '2026-09-01T12:00:00Z' };
const baseline: AnalysisRun = { ...run, id: 'baseline', status: 'PARTIALLY_SUCCEEDED', createdAt: '2026-09-02T12:00:00Z' };
const cancelled: AnalysisRun = { ...run, id: 'cancelled', status: 'CANCELLED', createdAt: '2026-09-04T12:00:00Z' };

function mount(runs: AnalysisRun[]) {
  const fetchMock = vi.fn(async (input: string) => {
    const path = new URL(input, 'http://localhost').pathname;
    return new Response(JSON.stringify(path.endsWith('/semantic-diff') ? [] : path.endsWith('/analysis-runs') ? runs : target));
  });
  vi.stubGlobal('fetch', fetchMock);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/analyses/target/diff']}><Routes><Route path="/analyses/:analysisRunId/diff" element={<DiffPage />} /></Routes></MemoryRouter></QueryClientProvider>);
  return fetchMock;
}

beforeEach(() => useLocale.getState().setLocale('en'));

describe('diff baseline defaults from the actual run response contract', () => {
  it('uses the latest earlier completed run after a newer cancellation, preserving manual choices', async () => {
    const fetchMock = mount([cancelled, target, older, baseline,
      { ...older, id: 'failed', status: 'FAILED', createdAt: '2026-09-03T11:59:00Z' },
      { ...older, id: 'running', status: 'RUNNING', createdAt: '2026-09-03T11:58:00Z' },
      { ...older, id: 'future-success', createdAt: '2026-09-05T12:00:00Z' },
    ]);
    const from = await screen.findByLabelText('Baseline');
    expect(from).toHaveValue(baseline.id);
    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => url.includes('fromAnalysisRun=baseline&toAnalysisRun=target'))).toBe(true));
    await userEvent.selectOptions(from, older.id);
    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => url.includes('fromAnalysisRun=older&toAnalysisRun=target'))).toBe(true));
    expect(from).toHaveValue(older.id);
  });

  it('has no automatic baseline when no earlier successful run exists', async () => {
    const fetchMock = mount([cancelled, target, { ...older, status: 'FAILED' }]);
    expect(await screen.findByLabelText('Baseline')).toHaveValue('');
    expect(fetchMock.mock.calls.some(([url]) => url.includes('/semantic-diff'))).toBe(false);
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
});
