import { describe, expect, it } from 'vitest';
import {
  boundedProjection,
  createScalableGraph,
  EDGE_LIMIT,
  overviewLayout,
  PROJECTION_LIMITS,
  rendererStressProjection,
} from '../../e2e/fixtures/scalable-graph';

const graph = createScalableGraph();

describe('test-only scalable graph transport', () => {
  it('contains exactly 10k unique business nodes in twenty 500-node contexts', () => {
    expect(graph.nodes).toHaveLength(10000);
    expect(graph.contexts).toHaveLength(20);
    expect(graph.byId.size).toBe(10000);
    expect(new Set(graph.nodes.map((node) => node.stableKey)).size).toBe(10000);
    for (const members of graph.members.values())
      expect(members).toHaveLength(500);
    expect(
      graph.edges.every(
        (edge) => graph.byId.has(edge.source) && graph.byId.has(edge.target),
      ),
    ).toBe(true);
    expect(graph.nodes.every((node) => !('snippet' in node))).toBe(true);
  });

  it('respects every LOD cap, depth bound, and edge endpoint boundary', () => {
    for (let detail = 0; detail < PROJECTION_LIMITS.length; detail++) {
      for (let depth = 1; depth <= 5; depth++) {
        const result = boundedProjection(
          graph,
          new URLSearchParams({
            levelOfDetail: String(detail),
            depth: String(depth),
          }),
        );
        expect(result.nodes.length).toBeLessThanOrEqual(
          PROJECTION_LIMITS[detail]!,
        );
        expect(result.edges.length).toBeLessThanOrEqual(EDGE_LIMIT);
        const ids = new Set(result.nodes.map((node) => node.id));
        expect(
          result.edges.every(
            (edge) => ids.has(edge.source) && ids.has(edge.target),
          ),
        ).toBe(true);
        expect(result.totalNodes).toBe(10000);
      }
    }
    for (const params of [
      'depth=0',
      'depth=6',
      'levelOfDetail=4',
      'rootNodeId=missing',
    ])
      expect(() =>
        boundedProjection(graph, new URLSearchParams(params)),
      ).toThrow();
  });

  it('makes all 10k nodes reachable through bounded root exploration without a full-graph response', () => {
    const seen = new Set<string>();
    for (const context of graph.contexts) {
      const result = boundedProjection(
        graph,
        new URLSearchParams({
          rootNodeId: context.id,
          levelOfDetail: '3',
          depth: '2',
        }),
      );
      expect(result.nodes).toHaveLength(500);
      expect(result.truncated).toBe(false);
      expect(result.edges.length).toBeLessThanOrEqual(EDGE_LIMIT);
      result.nodes.forEach((node) => seen.add(node.id));
    }
    expect(seen.size).toBe(10000);
  });

  it('keeps the 2k renderer stress separate and supplies a nonoverlapping saved overview', () => {
    const stress = rendererStressProjection(graph);
    expect(stress.nodes).toHaveLength(2000);
    expect(stress.totalNodes).toBe(2000);
    const layout = overviewLayout(stress.nodes);
    const positions = Object.values(layout.positions);
    expect(new Set(positions.map(({ x, y }) => `${x}:${y}`)).size).toBe(2000);
    expect(positions.every(({ x, y }) => x % 284 === 0 && y % 170 === 0)).toBe(
      true,
    );
    expect(
      boundedProjection(graph, new URLSearchParams('levelOfDetail=3')).nodes,
    ).toHaveLength(500);
  });
});
