import { MarkerType, type Edge, type Node } from '@xyflow/react';
import type { CanvasProjection, SemanticNode } from '../../shared/api/types';

const marker = { type: MarkerType.ArrowClosed, width: 16, height: 16 };
const solid = { stroke: '#8c9997', strokeWidth: 1.5 };
const uncertain = {
  stroke: '#b7791f',
  strokeWidth: 1.5,
  strokeDasharray: '5 4',
};
const labelStyle = { fontSize: 11, fill: '#52605d' };
const labelBgStyle = { fill: '#fafcfb' };
type NodeEntry = { source: Node; output: Node; groupLabel?: string };
type EdgeEntry = {
  source: CanvasProjection['edges'][number];
  compact: boolean;
  output: Edge;
};

// A per-surface cache preserves unchanged React Flow objects across selection and batches.
export function createPresentation() {
  let previousNodes = new Map<string, NodeEntry>();
  let previousEdges = new Map<string, EdgeEntry>();
  return {
    nodes(
      nodes: Node[],
      entities: Map<string, SemanticNode>,
      pins: readonly string[],
      compact: boolean,
      selectedId: string | null,
      label: (value: string) => string,
      steps?: Map<string, number>,
    ): Node[] {
      const pinned = new Set(pins);
      const next = new Map<string, NodeEntry>();
      const output = nodes.map((source) => {
        const previous = previousNodes.get(source.id);
        if (source.type === 'group') {
          const groupLabel = label(String(source.data.label));
          const output =
            previous?.source === source && previous.groupLabel === groupLabel
              ? previous.output
              : { ...source, data: { label: groupLabel } };
          next.set(source.id, { source, groupLabel, output });
          return output;
        }
        const entity =
          entities.get(source.id) ?? (source.data.entity as SemanticNode);
        const isPinned = pinned.has(entity.stableKey);
        const selected = source.id === selectedId;
        const stepIndex = steps?.get(source.id);
        const previousData = previous?.output.data;
        const data =
          previousData?.entity === entity &&
          previousData.pinned === isPinned &&
          previousData.compact === compact &&
          previousData.stepIndex === stepIndex
            ? previousData
            : { entity, pinned: isPinned, compact, stepIndex };
        const output =
          previous?.source === source &&
          previous.output.data === data &&
          previous.output.selected === selected
            ? previous.output
            : {
                ...source,
                data,
                selected,
                draggable: isPinned ? false : undefined,
              };
        next.set(source.id, { source, output });
        return output;
      });
      previousNodes = next;
      return output;
    },
    edges(
      edges: CanvasProjection['edges'],
      entities: Map<string, CanvasProjection['edges'][number]>,
      compact: boolean,
      selectedId: string | null,
      steps?: Map<string, number>,
    ): Edge[] {
      const next = new Map<string, EdgeEntry>();
      const output = edges.map((installed) => {
        const source = entities.get(installed.id) ?? installed;
        const previous = previousEdges.get(source.id);
        const selected = source.id === selectedId;
        const isReturnEdge = source.kind === 'RETURNS';
        const isPathEdge = Boolean(
          steps &&
          steps.has(source.source) &&
          steps.has(source.target) &&
          steps.get(source.target) === (steps.get(source.source) ?? 0) + 1
        );
        const output =
          previous?.source === source &&
          previous.compact === compact &&
          Boolean(previous.output.animated) === (isPathEdge || isReturnEdge)
            ? previous.output.selected === selected
              ? previous.output
              : { ...previous.output, selected }
            : {
                id: source.id,
                source: source.source,
                target: source.target,
                type: 'smoothstep',
                label: compact
                  ? undefined
                  : source.label || (isReturnEdge ? 'Возврат ответа в начало' : source.kind.replaceAll('_', ' ')),
                selected,
                animated: isPathEdge || isReturnEdge,
                markerEnd: marker,
                style: isReturnEdge
                  ? { stroke: '#0284c7', strokeWidth: 2.5, strokeDasharray: '6 4' }
                  : isPathEdge
                    ? { stroke: '#0e7466', strokeWidth: 2.5 }
                    : source.confidence < 0.7
                      ? uncertain
                      : solid,
                labelStyle: isReturnEdge
                  ? { ...labelStyle, fontWeight: 700, fill: '#0284c7' }
                  : isPathEdge
                    ? { ...labelStyle, fontWeight: 600, fill: '#0e7466' }
                    : labelStyle,
                labelBgStyle,
                interactionWidth: 24,
                ariaLabel: source.label || source.kind,
              };
        next.set(source.id, { source, compact, output });
        return output;
      });
      previousEdges = next;
      return output;
    },
  };
}
