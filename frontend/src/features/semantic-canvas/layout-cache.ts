import type {
  CanvasLayout,
  CanvasView,
  SemanticNode,
} from '../../shared/api/types';
import type { LayoutResult } from '../../shared/workers/layout.worker';

export const LAYOUT_POSITION_LIMIT = 2000;
type Positions = CanvasLayout['positions'];
type LayoutNode = Pick<SemanticNode, 'id' | 'stableKey'>;

export function validPosition(position: Positions[string] | undefined) {
  return (
    position != null &&
    Number.isFinite(position.x) &&
    Number.isFinite(position.y) &&
    Math.abs(position.x) <= 1e7 &&
    Math.abs(position.y) <= 1e7
  );
}

export function restoreSavedLayout(
  nodes: LayoutNode[],
  layout: CanvasLayout,
  view: CanvasView,
  explicitRelayout: boolean,
): LayoutResult | null {
  // Ownership positions are parent-relative; its group geometry still requires ELK.
  if (
    explicitRelayout ||
    view === 'DATA_OWNERSHIP' ||
    nodes.length === 0 ||
    !nodes.every((node) => validPosition(layout.positions[node.stableKey]))
  )
    return null;
  return {
    positions: Object.fromEntries(
      nodes.map((node) => [node.id, layout.positions[node.stableKey]!]),
    ),
    groups: [],
  };
}

export function boundedLayout(
  layout: CanvasLayout,
  nodes: LayoutNode[],
  pins: readonly string[],
): CanvasLayout | null {
  const valid = Object.entries(layout.positions).filter(
    ([key, position]) => key.length <= 2048 && validPosition(position),
  );
  const available = new Set(valid.map(([key]) => key));
  const current = new Set(
    nodes.map((node) => node.stableKey).filter((key) => available.has(key)),
  );
  const retained = new Set([
    ...current,
    ...pins.filter((key) => available.has(key)),
  ]);
  if (retained.size > LAYOUT_POSITION_LIMIT) return null;
  // Entries are oldest-first. Touch current nodes last so later roots evict stale unpinned positions.
  for (
    let index = valid.length - 1;
    index >= 0 && retained.size < LAYOUT_POSITION_LIMIT;
    index--
  )
    retained.add(valid[index]![0]);
  const positions = Object.fromEntries([
    ...valid.filter(([key]) => retained.has(key) && !current.has(key)),
    ...nodes
      .filter((node) => current.has(node.stableKey))
      .map((node) => [node.stableKey, layout.positions[node.stableKey]!]),
  ]);
  return { positions, viewport: layout.viewport };
}
