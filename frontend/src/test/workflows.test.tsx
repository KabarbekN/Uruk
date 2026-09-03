import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReactNode } from 'react';
import {
  ProjectEditor,
  StartAnalysis,
} from '../features/project-management/forms';
import { EvidenceDrawer } from '../features/evidence-drawer/EvidenceDrawer';
import { ReviewForm } from '../features/review-workflow/ReviewForm';
import { useLocale } from '../shared/lib/i18n';
import { evidence, project, rule, run } from './fixtures';

vi.setConfig({ testTimeout: 15000 });

function mount(children: ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>{children}</MemoryRouter>
    </QueryClientProvider>,
  );
}
beforeEach(() => useLocale.getState().setLocale('en'));

describe('real request workflows with isolated test transport', () => {
  it('quick-fills a real repository path and submits the project contract', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify(project), { status: 201 }),
      );
    vi.stubGlobal('fetch', fetchMock);
    const close = vi.fn();
    mount(<ProjectEditor onClose={close} />);
    expect(screen.getByLabelText('Name')).toHaveAttribute('maxlength', '120');
    expect(screen.getByLabelText('Description')).toHaveAttribute(
      'maxlength',
      '2000',
    );
    await userEvent.click(
      screen.getByRole('button', { name: 'Use Spring fixture' }),
    );
    expect(screen.getByLabelText('Local repository path')).toHaveValue(
      'fixtures/spring-order-service',
    );
    await userEvent.click(screen.getByTestId('create-project'));
    await waitFor(() => expect(close).toHaveBeenCalledOnce());
    expect(JSON.parse(fetchMock.mock.calls[0]?.[1].body)).toEqual({
      name: 'spring-order-service',
      description: '',
      repositoryPath: 'fixtures/spring-order-service',
    });
  });
  it('does not close a project form or report success when the server rejects it', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ detail: 'Name already exists' }), {
          status: 409,
        }),
      ),
    );
    const close = vi.fn();
    mount(<ProjectEditor project={project} onClose={close} />);
    await userEvent.click(screen.getByTestId('save-project'));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Name already exists',
    );
    expect(close).not.toHaveBeenCalled();
  });
  it('offers only completed baselines and sends optional analysis fields precisely', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(new Response(JSON.stringify(run), { status: 201 }));
    vi.stubGlobal('fetch', fetchMock);
    const failed = {
      ...run,
      id: 'failed-run',
      status: 'FAILED',
      revision: 'bad-revision',
    };
    mount(
      <StartAnalysis
        projectId={project.id}
        runs={[run, failed]}
        onClose={vi.fn()}
      />,
    );
    expect(
      screen.queryByRole('option', { name: /bad-revision/ }),
    ).not.toBeInTheDocument();
    await userEvent.type(
      screen.getByLabelText('Branch, tag or commit'),
      'feature/minimum',
    );
    await userEvent.selectOptions(
      screen.getByLabelText('Baseline analysis'),
      run.id,
    );
    await userEvent.click(screen.getByTestId('start-analysis'));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledOnce());
    expect(JSON.parse(fetchMock.mock.calls[0]?.[1].body)).toEqual({
      ref: 'feature/minimum',
      baselineRunId: run.id,
    });
  });
  it('requires a valid distinct merge target and sends a human review', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);
    mount(<ReviewForm node={rule} />);
    await userEvent.selectOptions(screen.getByLabelText('Decision'), 'MERGED');
    expect(
      screen.getByRole('button', { name: 'Submit review' }),
    ).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Target node UUID'), project.id);
    await userEvent.type(
      screen.getByLabelText('Review comment'),
      'Same constraint',
    );
    await userEvent.click(
      screen.getByRole('button', { name: 'Submit review' }),
    );
    await waitFor(() => expect(fetchMock).toHaveBeenCalledOnce());
    expect(JSON.parse(fetchMock.mock.calls[0]?.[1].body)).toEqual({
      decision: 'MERGED',
      comment: 'Same constraint',
      editedTitle: '',
      editedDescription: '',
      mergeTargetId: project.id,
    });
  });
  it('displays retained evidence metadata and a source expiry message instead of a blank editor', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockImplementation((path: string) =>
          Promise.resolve(
            new Response(
              JSON.stringify(
                path.endsWith('/evidence')
                  ? [{ ...evidence, snippet: '', sourceAvailable: false }]
                  : { ...rule, assertions: [], reviews: [] },
              ),
            ),
          ),
        ),
    );
    mount(
      <EvidenceDrawer
        selection={{ kind: 'nodes', id: rule.id, label: rule.label }}
        onClose={vi.fn()}
      />,
    );
    expect(
      await screen.findByText(
        'Source expired under the retention policy. Evidence metadata is preserved.',
      ),
    ).toBeInTheDocument();
    expect(screen.queryByTestId('source-viewer')).not.toBeInTheDocument();
    expect(screen.getByText('java-spring')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Copy' })).toBeDisabled();
  });
});
