import type { CanvasProjection } from '../../src/shared/api/types';
import { rule } from '../../src/test/fixtures';

// The original 1k acceptance topology, also used by the opt-in CPU diagnostic.
export function largeProjection(): CanvasProjection {
  const nodes = Array.from({ length: 1000 }, (_, index) => ({
    ...rule,
    id: `perf-node-${index}`,
    stableKey: `perf:rule:${index}`,
    label: `Order policy ${String(index + 1).padStart(4, '0')}`,
    subtitle: `Stage ${Math.floor(index / 50) + 1} / component ${index % 50}`,
    properties: { sourceLayer: 'BACKEND', hasTests: index % 3 !== 0 },
  }));
  const edges: CanvasProjection['edges'] = [];
  const connect = (source: number, target: number) => {
    edges.push({
      id: `perf-edge-${edges.length}`,
      source: nodes[source]!.id,
      target: nodes[target]!.id,
      kind: 'PRECEDES',
      label: 'then',
      confidence: 0.95,
      evidenceCount: 1,
    });
  };
  for (let index = 0; index < 950; index++) {
    connect(index, index + 50);
    connect(index, Math.floor(index / 50) * 50 + 50 + ((index + 1) % 50));
  }
  for (let index = 0; index < 100; index++) connect(index, index + 100);
  return { nodes, edges, truncated: false, totalNodes: nodes.length };
}
