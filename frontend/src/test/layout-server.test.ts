import { describe, expect, it } from 'vitest';
import { createLayoutServer } from '../../e2e/fixtures/layout-server';
import {
  createScalableGraph,
  overviewLayout,
} from '../../e2e/fixtures/scalable-graph';

const nodes = createScalableGraph().nodes;

describe('test-only layout API contract', () => {
  it('accepts exactly 2000 known positions and rejects 2001 atomically', () => {
    const server = createLayoutServer(nodes);
    const valid = {
      ...overviewLayout(nodes.slice(0, 2000)),
      pinnedStableKeys: [],
    };
    expect(server.put(valid).status).toBe(200);
    expect(server.put(overviewLayout(nodes.slice(0, 2001))).status).toBe(400);
    expect(server.get()).toEqual(valid);
  });

  it('replaces rather than merges old positions and preserves the submitted viewport', () => {
    const server = createLayoutServer(
      nodes,
      overviewLayout(nodes.slice(0, 500)),
    );
    const next = {
      ...overviewLayout(nodes.slice(500, 1000)),
      pinnedStableKeys: [],
    };
    next.viewport = { x: -12, y: 84, zoom: 0.4 };
    expect(server.put(next)).toEqual({ status: 200, json: next });
    expect(server.get()).toEqual(next);
    expect(server.get().positions[nodes[0]!.stableKey]).toBeUndefined();
  });

  it.each([
    {
      positions: { foreign: { x: 0, y: 0 } },
      viewport: { x: 0, y: 0, zoom: 1 },
    },
    {
      positions: { [nodes[0]!.stableKey]: { x: Infinity, y: 0 } },
      viewport: { x: 0, y: 0, zoom: 1 },
    },
    { positions: {}, viewport: { x: 1e7 + 1, y: 0, zoom: 1 } },
    { positions: {}, viewport: { x: 0, y: 0, zoom: 0 } },
    { positions: null, viewport: { x: 0, y: 0, zoom: 1 } },
    { positions: {}, viewport: null },
  ])(
    'rejects malformed or cross-run payloads without deleting the previous layout',
    (body) => {
      const initial = {
        ...overviewLayout(nodes.slice(0, 1)),
        pinnedStableKeys: [],
      };
      const server = createLayoutServer(nodes, initial);
      expect(server.put(body).status).toBe(400);
      expect(server.get()).toEqual(initial);
    },
  );

  it('persists pins, rejects pins without a position, and lets older clients unpin', () => {
    const server = createLayoutServer(nodes);
    const layout = overviewLayout(nodes.slice(0, 1));
    expect(
      server.put({ ...layout, pinnedStableKeys: [nodes[0]!.stableKey] }).status,
    ).toBe(200);
    expect(server.get().pinnedStableKeys).toEqual([nodes[0]!.stableKey]);
    expect(
      server.put({ ...layout, pinnedStableKeys: [nodes[1]!.stableKey] }).status,
    ).toBe(400);
    expect(
      server.put({
        ...layout,
        pinnedStableKeys: [nodes[0]!.stableKey, nodes[0]!.stableKey],
      }).status,
    ).toBe(400);
    expect(server.get().pinnedStableKeys).toEqual([nodes[0]!.stableKey]);
    expect(server.put(layout).status).toBe(200);
    expect(server.get().pinnedStableKeys).toEqual([]);
  });
});
