import type { Node } from '@xyflow/react';
import { describe, expect, it } from 'vitest';
import { createPresentation } from '../features/semantic-canvas/presentation';
import { projection } from './fixtures';

const nodes: Node[] = projection.nodes.map((entity, index) => ({
  id: entity.id,
  type: 'semantic',
  data: { entity },
  position: { x: index * 300, y: 0 },
}));
const entities = new Map(projection.nodes.map((node) => [node.id, node]));
const edges = new Map(projection.edges.map((edge) => [edge.id, edge]));
const label = (value: string) => value;

describe('stable graph presentation', () => {
  it('updates only selected node identities and retains their data objects', () => {
    const view = createPresentation();
    const before = view.nodes(nodes, entities, [], true, null, label);
    const selected = view.nodes(nodes, entities, [], true, nodes[1]!.id, label);
    expect(selected[0]).toBe(before[0]);
    expect(selected[2]).toBe(before[2]);
    expect(selected[1]).not.toBe(before[1]);
    expect(selected[1]!.data).toBe(before[1]!.data);
    const moved = view.nodes(nodes, entities, [], true, nodes[2]!.id, label);
    expect(moved[0]).toBe(selected[0]);
    expect(moved[1]!.selected).toBe(false);
    expect(moved[2]!.selected).toBe(true);
  });

  it('changes only the pinned entity and preserves original labels and evidence semantics', () => {
    const view = createPresentation();
    const before = view.nodes(nodes, entities, [], false, null, label);
    const pinned = view.nodes(
      nodes,
      entities,
      [projection.nodes[0]!.stableKey],
      false,
      null,
      label,
    );
    expect(pinned[0]!.draggable).toBe(false);
    expect(pinned[1]).toBe(before[1]);
    expect(pinned[2]).toBe(before[2]);
    expect(pinned[0]!.data.entity).toBe(projection.nodes[0]);
    expect(pinned[1]!.draggable).toBeUndefined();
  });

  it('retains edge prefix identities while appending and touches only changed edge selection', () => {
    const view = createPresentation();
    const first = view.edges(projection.edges.slice(0, 1), edges, true, null);
    const all = view.edges(projection.edges, edges, true, null);
    expect(all[0]).toBe(first[0]);
    const selected = view.edges(
      projection.edges,
      edges,
      true,
      projection.edges[1]!.id,
    );
    expect(selected[0]).toBe(all[0]);
    expect(selected[1]!.style).toBe(all[1]!.style);
    expect(selected[1]!.markerEnd).toBe(all[1]!.markerEnd);
    expect(selected[1]!.ariaLabel).toBe(projection.edges[1]!.label);
    const detailed = view.edges(projection.edges, edges, false, null);
    expect(detailed.map((edge) => edge.label)).toEqual(
      projection.edges.map((edge) => edge.label),
    );
  });

  it('reflects fresh semantic metadata and positions without recreating unrelated entities', () => {
    const view = createPresentation();
    const before = view.nodes(nodes, entities, [], false, null, label);
    const updated = new Map(entities);
    updated.set(nodes[0]!.id, {
      ...projection.nodes[0]!,
      label: 'Updated business rule',
    });
    const moved = nodes.map((node, index) =>
      index === 1 ? { ...node, position: { x: 20, y: 40 } } : node,
    );
    const after = view.nodes(moved, updated, [], false, null, label);
    expect(after[0]!.data.entity).toBe(updated.get(nodes[0]!.id));
    expect(after[1]!.position).toEqual({ x: 20, y: 40 });
    expect(after[1]!.data).toBe(before[1]!.data);
    expect(after[2]).toBe(before[2]);
  });
});
