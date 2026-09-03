import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactFlowProps } from '@xyflow/react';
import type { PropsWithChildren } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type {
  CanvasLayout,
  CanvasProjection,
  CanvasView,
} from '../shared/api/types';
import type { LayoutResult } from '../shared/workers/layout.worker';
import { GraphSurface } from '../features/semantic-canvas/GraphSurface';
import { useCanvasStore } from '../features/semantic-canvas/store';
import { useLocale } from '../shared/lib/i18n';
import { api } from '../shared/api/client';
import { createLayoutServer } from '../../e2e/fixtures/layout-server';
import {
  createScalableGraph,
  overviewLayout,
} from '../../e2e/fixtures/scalable-graph';
import { projection, run } from './fixtures';

type TestWorker = {
  onmessage?: (
    event: MessageEvent<{ ok: boolean; result: LayoutResult }>,
  ) => void;
  postMessage: ReturnType<typeof vi.fn>;
  terminate: ReturnType<typeof vi.fn>;
};
const harness = vi.hoisted(() => ({
  props: {} as ReactFlowProps,
  workers: [] as TestWorker[],
  viewport: { x: 10, y: 20, zoom: 0.1 },
  setViewport: vi.fn(),
}));

vi.mock('../shared/workers/layout.worker?worker', () => ({
  default: class {
    postMessage = vi.fn();
    terminate = vi.fn();
    constructor() {
      harness.workers.push(this);
    }
  },
}));
// These tests exercise layout orchestration, not browser geometry or renderer performance.
vi.mock('@xyflow/react', async (original) => ({
  ...(await original<typeof import('@xyflow/react')>()),
  ReactFlow: (props: ReactFlowProps) => {
    harness.props = props;
    return props.children;
  },
  Panel: ({ children }: PropsWithChildren) => children,
  Background: () => null,
  useStore: () => false,
  useReactFlow: () => ({
    setViewport: harness.setViewport,
    getViewport: () => harness.viewport,
  }),
  getViewportForBounds: () => harness.viewport,
}));

const scope = `${run.id}:BUSINESS`;
const fullGraph = createScalableGraph();
const asProjection = (nodes: CanvasProjection['nodes']): CanvasProjection => ({
  nodes,
  edges: [],
  totalNodes: fullGraph.nodes.length,
  truncated: false,
});

function mount(
  graph = projection,
  savedLayout: CanvasLayout | null = overviewLayout(graph.nodes),
  view: CanvasView = 'BUSINESS',
) {
  const client = new QueryClient({
    defaultOptions: { mutations: { retry: false }, queries: { retry: false } },
  });
  const element = (next: CanvasProjection) => (
    <QueryClientProvider client={client}>
      <GraphSurface
        runId={run.id}
        view={view}
        projection={next}
        savedLayout={savedLayout}
      />
    </QueryClientProvider>
  );
  const rendered = render(element(graph));
  return {
    ...rendered,
    update: (next: CanvasProjection) => rendered.rerender(element(next)),
  };
}

function reply(graph = projection) {
  const result: LayoutResult = {
    positions: Object.fromEntries(
      graph.nodes.map((node, index) => [
        node.id,
        { x: index * 300 + 15, y: 60 },
      ]),
    ),
    groups: [],
  };
  act(() =>
    harness.workers
      .at(-1)!
      .onmessage?.({ data: { ok: true, result } } as MessageEvent),
  );
  return result;
}

beforeEach(() => {
  vi.restoreAllMocks();
  harness.workers.length = 0;
  harness.setViewport.mockClear();
  useCanvasStore.setState({ layouts: {}, pins: {}, selected: null });
  useLocale.setState({ locale: 'en' });
});

describe('GraphSurface saved layout and bounded persistence', () => {
  it('restores a complete saved layout and viewport without constructing a worker', () => {
    const saved = overviewLayout(projection.nodes);
    mount(projection, saved);
    expect(harness.workers).toHaveLength(0);
    expect(screen.getByRole('button', { name: 'Save layout' })).toBeEnabled();
    expect(harness.setViewport).toHaveBeenCalledWith(saved.viewport);
    for (const node of projection.nodes)
      expect(
        harness.props.nodes!.find((item) => item.id === node.id)!.position,
      ).toEqual(saved.positions[node.stableKey]);
  });

  it('runs the worker on explicit arrange, preserving pinned positions only', () => {
    const saved = overviewLayout(projection.nodes);
    const pinned = projection.nodes[0]!;
    useCanvasStore.setState({ pins: { [scope]: [pinned.stableKey] } });
    mount(projection, saved);
    fireEvent.click(screen.getByRole('button', { name: 'Arrange nodes' }));
    expect(harness.workers).toHaveLength(1);
    const result = reply();
    expect(harness.workers[0]!.terminate).toHaveBeenCalledOnce();
    expect(harness.props.nodes![0]!.position).toEqual(
      saved.positions[pinned.stableKey],
    );
    expect(harness.props.nodes![1]!.position).toEqual(
      result.positions[projection.nodes[1]!.id],
    );
  });

  it('uses ELK for partial saved layouts and replaces invalid coordinates with its result', () => {
    const saved = overviewLayout(projection.nodes.slice(0, 2));
    saved.positions[projection.nodes[1]!.stableKey] = { x: NaN, y: 0 };
    mount(projection, saved);
    expect(harness.workers).toHaveLength(1);
    const result = reply();
    expect(harness.props.nodes![0]!.position).toEqual(
      saved.positions[projection.nodes[0]!.stableKey],
    );
    expect(harness.props.nodes![1]!.position).toEqual(
      result.positions[projection.nodes[1]!.id],
    );
    expect(harness.props.nodes![2]!.position).toEqual(
      result.positions[projection.nodes[2]!.id],
    );
  });

  it('still requests ownership group geometry when all node positions are saved', () => {
    mount(projection, overviewLayout(projection.nodes), 'DATA_OWNERSHIP');
    expect(harness.workers).toHaveLength(1);
    expect(harness.workers[0]!.postMessage).toHaveBeenCalledWith(
      expect.objectContaining({ ownership: true }),
    );
  });

  it.each(['explicit', 'debounce', 'unmount'] as const)(
    'bounds %s saves after a fifth 500-node root while retaining off-screen pins',
    async (mode) => {
      const saved = overviewLayout(fullGraph.nodes.slice(0, 2000));
      const pinned = fullGraph.nodes[0]!;
      useCanvasStore.setState({ pins: { [scope]: [pinned.stableKey] } });
      const server = createLayoutServer(fullGraph.nodes, saved);
      const save = vi
        .spyOn(api, 'saveLayout')
        .mockImplementation(async (_id, _view, body) => {
          const response = server.put(body);
          if (response.status !== 200) throw new Error('Layout rejected');
          return response.json;
        });
      const rendered = mount(
        asProjection(fullGraph.nodes.slice(1500, 2000)),
        saved,
      );
      const fifth = asProjection(fullGraph.nodes.slice(2000, 2500));
      rendered.update(fifth);
      reply(fifth);
      await waitFor(() =>
        expect(
          screen.getByRole('button', { name: 'Save layout' }),
        ).toBeEnabled(),
      );
      expect(
        Object.keys(useCanvasStore.getState().layouts[scope]!.positions),
      ).toHaveLength(2000);
      if (mode === 'explicit')
        fireEvent.click(screen.getByRole('button', { name: 'Save layout' }));
      else {
        act(() =>
          harness.props.onMoveEnd?.(
            new MouseEvent('mouseup'),
            harness.viewport,
          ),
        );
        expect(save).not.toHaveBeenCalled();
        if (mode === 'unmount') rendered.unmount();
      }
      await waitFor(() => expect(save).toHaveBeenCalledOnce(), {
        timeout: 2000,
      });
      const stored = server.get();
      expect(Object.keys(stored.positions)).toHaveLength(2000);
      expect(stored.positions[pinned.stableKey]).toEqual(
        saved.positions[pinned.stableKey],
      );
      for (const node of fifth.nodes)
        expect(stored.positions[node.stableKey]).toBeDefined();
      expect(server.rejected).toEqual([]);
    },
  );

  it('reports capacity without sending or discarding pins, then permits a bounded save after unpinning', async () => {
    const all = fullGraph.nodes.slice(0, 2500);
    const saved = overviewLayout(all);
    const pins = all.slice(0, 1501).map((node) => node.stableKey);
    useCanvasStore.setState({ pins: { [scope]: pins } });
    const save = vi.spyOn(api, 'saveLayout').mockResolvedValue(undefined);
    mount(asProjection(all.slice(2000)), saved);
    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent(
        '2,000-position storage limit',
      ),
    );
    fireEvent.click(screen.getByRole('button', { name: 'Save layout' }));
    expect(save).not.toHaveBeenCalled();
    expect(useCanvasStore.getState().pins[scope]).toEqual(pins);
    expect(useCanvasStore.getState().layouts[scope]!.positions).toEqual(
      saved.positions,
    );
    act(() => useCanvasStore.getState().togglePin(scope, pins[0]!));
    fireEvent.click(screen.getByRole('button', { name: 'Save layout' }));
    await waitFor(() => expect(save).toHaveBeenCalledOnce());
    expect(Object.keys(save.mock.calls[0]![2].positions)).toHaveLength(2000);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('cancels old-root batches before mounting a new projection and never publishes the partial layout', async () => {
    const first = asProjection(fullGraph.nodes.slice(0, 2000));
    const rendered = mount(first, null);
    reply(first);
    expect(screen.getByRole('button', { name: 'Save layout' })).toBeDisabled();
    expect(harness.props.nodes!.length).toBeLessThan(2000);
    expect(useCanvasStore.getState().layouts[scope]).toBeUndefined();
    const next = asProjection(fullGraph.nodes.slice(2000, 2003));
    rendered.update(next);
    reply(next);
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 80));
    });
    expect(harness.props.nodes!.map((node) => node.id)).toEqual(
      next.nodes.map((node) => node.id),
    );
    expect(
      Object.keys(useCanvasStore.getState().layouts[scope]!.positions),
    ).toEqual(next.nodes.map((node) => node.stableKey));
    expect(screen.getByRole('button', { name: 'Save layout' })).toBeEnabled();
  });

  it('blocks interaction saves during partial installation and cancels on unmount', async () => {
    const graph = asProjection(fullGraph.nodes.slice(0, 500));
    const save = vi.spyOn(api, 'saveLayout').mockResolvedValue(undefined);
    const rendered = mount(graph, null);
    reply(graph);
    act(() =>
      harness.props.onMoveEnd?.(new MouseEvent('mouseup'), harness.viewport),
    );
    rendered.unmount();
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 80));
    });
    expect(save).not.toHaveBeenCalled();
    expect(useCanvasStore.getState().layouts[scope]).toBeUndefined();
  });

  it('defers a pending save until the replacement projection has complete geometry', async () => {
    const save = vi.spyOn(api, 'saveLayout').mockResolvedValue(undefined);
    const rendered = mount();
    act(() =>
      harness.props.onMoveEnd?.(new MouseEvent('mouseup'), harness.viewport),
    );
    const next = asProjection(fullGraph.nodes.slice(0, 500));
    rendered.update(next);
    reply(next);
    expect(save).not.toHaveBeenCalled();
    await waitFor(() => expect(save).toHaveBeenCalledOnce());
    const positions = save.mock.calls[0]![2].positions;
    for (const node of next.nodes)
      expect(positions[node.stableKey]).toBeDefined();
    expect(harness.props.nodes).toHaveLength(500);
  });

  it('keeps callbacks and unrelated entities stable across selection and lock changes', () => {
    mount();
    const before = harness.props;
    act(() =>
      useCanvasStore
        .getState()
        .select({
          kind: 'nodes',
          id: projection.nodes[1]!.id,
          label: projection.nodes[1]!.label,
        }),
    );
    expect(harness.props.nodes![0]).toBe(before.nodes![0]);
    expect(harness.props.nodes![2]).toBe(before.nodes![2]);
    expect(harness.props.edges).toBe(before.edges);
    const selected = harness.props.nodes;
    fireEvent.click(screen.getByRole('button', { name: 'Lock map' }));
    expect(harness.props.nodes).toBe(selected);
    expect(harness.props.nodesDraggable).toBe(false);
    for (const key of [
      'onNodeClick',
      'onEdgeClick',
      'onPaneClick',
      'onMoveEnd',
      'onNodeDragStop',
    ] as const)
      expect(harness.props[key]).toBe(before[key]);
  });
});
