import { describe, expect, it } from 'vitest';
import {
  boundedLayout,
  restoreSavedLayout,
} from '../features/semantic-canvas/layout-cache';
import { createLayoutServer } from '../../e2e/fixtures/layout-server';
import {
  boundedProjection,
  createScalableGraph,
  overviewLayout,
} from '../../e2e/fixtures/scalable-graph';
import type { CanvasLayout, CanvasView } from '../shared/api/types';

const graph = createScalableGraph();
const nodes = graph.nodes.slice(0, 2000);
const saved = overviewLayout(nodes);

describe('saved layout restoration', () => {
  it.each<CanvasView>([
    'BUSINESS',
    'DEVELOPER',
    'SECURITY',
    'DATA_PIPELINE',
    'CONFIDENCE',
  ])(
    'restores all 2000 saved stable keys in %s without requiring ELK',
    (view) => {
      const changedIds = nodes.map((node) => ({
        ...node,
        id: `new:${node.id}`,
      }));
      const restored = restoreSavedLayout(changedIds, saved, view, false)!;
      expect(restored.groups).toEqual([]);
      expect(Object.keys(restored.positions)).toHaveLength(2000);
      for (const node of changedIds)
        expect(restored.positions[node.id]).toEqual(
          saved.positions[node.stableKey],
        );
    },
  );

  it('retains the worker path for explicit relayout and parent-relative ownership geometry', () => {
    expect(restoreSavedLayout(nodes, saved, 'BUSINESS', true)).toBeNull();
    expect(
      restoreSavedLayout(nodes, saved, 'DATA_OWNERSHIP', false),
    ).toBeNull();
  });

  it.each([
    undefined,
    { x: NaN, y: 0 },
    { x: 0, y: Infinity },
    { x: 1e7 + 1, y: 0 },
  ])('requires ELK for a missing or invalid saved position: %j', (position) => {
    const positions = { ...saved.positions };
    if (position) positions[nodes[0]!.stableKey] = position;
    else delete positions[nodes[0]!.stableKey];
    expect(
      restoreSavedLayout(nodes, { ...saved, positions }, 'BUSINESS', false),
    ).toBeNull();
  });
});

describe('bounded full-replacement layout persistence', () => {
  it('visits all 10k nodes in 500-node responses, preserving current positions and all visited context pins', () => {
    const server = createLayoutServer(graph.nodes);
    let layout: CanvasLayout = { positions: {}, viewport: saved.viewport };
    const pins: string[] = [];
    const pinnedPositions: CanvasLayout['positions'] = {};
    const seen = new Set<string>();
    for (const context of graph.contexts) {
      const projection = boundedProjection(
        graph,
        new URLSearchParams({
          rootNodeId: context.id,
          levelOfDetail: '3',
          depth: '2',
        }),
      );
      expect(projection.nodes).toHaveLength(500);
      const current = overviewLayout(projection.nodes).positions;
      pins.push(context.stableKey);
      pinnedPositions[context.stableKey] = current[context.stableKey]!;
      layout = boundedLayout(
        { ...layout, positions: { ...layout.positions, ...current } },
        projection.nodes,
        pins,
      )!;
      expect(Object.keys(layout.positions).length).toBeLessThanOrEqual(2000);
      expect(layout.positions).toMatchObject(current);
      expect(layout.positions).toMatchObject(pinnedPositions);
      expect(server.put(layout).status).toBe(200);
      expect(server.get().positions).toEqual(layout.positions);
      expect(server.get().pinnedStableKeys).toEqual(pins);
      projection.nodes.forEach((node) => seen.add(node.id));
    }
    expect(seen.size).toBe(10000);
    expect(server.accepted).toHaveLength(20);
    expect(server.rejected).toEqual([]);
    expect(pins).toHaveLength(20);
    expect(Object.keys(layout.positions)).toHaveLength(2000);
    expect(layout.positions[graph.nodes[1]!.stableKey]).toBeUndefined();
  });

  it('touches the current projection and evicts older unpinned history first', () => {
    const revisited = nodes.slice(0, 500);
    const touched = boundedLayout(saved, revisited, [])!;
    const newNodes = graph.nodes.slice(2000, 2500);
    const next = boundedLayout(
      {
        ...touched,
        positions: {
          ...touched.positions,
          ...overviewLayout(newNodes).positions,
        },
      },
      newNodes,
      [],
    )!;
    for (const node of [...revisited, ...newNodes])
      expect(next.positions[node.stableKey]).toBeDefined();
    for (const node of nodes.slice(500, 1000))
      expect(next.positions[node.stableKey]).toBeUndefined();
    expect(saved.positions).toHaveProperty(nodes[500]!.stableKey);
  });

  it('does not silently discard protected positions or mutate pins when the protected union exceeds 2000', () => {
    const current = graph.nodes.slice(2000, 2500);
    const pins = nodes.slice(0, 1501).map((node) => node.stableKey);
    const layout = {
      ...saved,
      positions: { ...saved.positions, ...overviewLayout(current).positions },
    };
    expect(boundedLayout(layout, current, pins)).toBeNull();
    expect(pins).toHaveLength(1501);
    expect(Object.keys(layout.positions)).toHaveLength(2500);
    const afterUnpin = boundedLayout(layout, current, pins.slice(1))!;
    expect(Object.keys(afterUnpin.positions)).toHaveLength(2000);
    expect(afterUnpin.positions).toMatchObject(
      overviewLayout(current).positions,
    );
  });

  it('filters invalid coordinates without using an invalid cache entry as a saved position', () => {
    const layout = boundedLayout(
      {
        ...saved,
        positions: {
          ...saved.positions,
          bad: { x: NaN, y: 1 },
          ['k'.repeat(2049)]: { x: 0, y: 0 },
        },
      },
      nodes,
      [],
    )!;
    expect(layout.positions).toEqual(saved.positions);
  });
});
