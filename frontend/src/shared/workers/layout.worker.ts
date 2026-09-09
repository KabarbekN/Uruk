import ELK, { type ElkNode } from 'elkjs/lib/elk-api.js';
import ElkEngineWorker from 'elkjs/lib/elk-worker.min.js?worker';

export type LayoutRequest = {
  nodes: { id: string; layer: string }[];
  edges: { id: string; source: string; target: string }[];
  ownership: boolean;
};
export type LayoutResult = {
  positions: Record<string, { x: number; y: number; parentId?: string }>;
  groups: {
    id: string;
    layer: string;
    x: number;
    y: number;
    width: number;
    height: number;
  }[];
};
const elk = new ELK({ workerFactory: () => new ElkEngineWorker() });
const width = 264;
const height = 150;

self.onmessage = async (event: MessageEvent<LayoutRequest>) => {
  try {
    const { nodes, edges, ownership } = event.data;

    // Fast-path: When there are no edges, lay out nodes in a clean 2D grid matrix
    // instead of stacking them in a single vertical column.
    if (!ownership && edges.length === 0 && nodes.length > 0) {
      const cols =
        nodes.length <= 3
          ? Math.max(1, nodes.length)
          : nodes.length <= 8
            ? 3
            : nodes.length <= 20
              ? 4
              : 5;
      const colWidth = width + 48;
      const rowHeight = height + 40;
      const output: LayoutResult = { positions: {}, groups: [] };
      nodes.forEach((node, index) => {
        const col = index % cols;
        const row = Math.floor(index / cols);
        output.positions[node.id] = {
          x: 32 + col * colWidth,
          y: 32 + row * rowHeight,
        };
      });
      self.postMessage({ ok: true, result: output });
      return;
    }

    // Bound optimization work for large projections; retain ELK's layered layout.
    const scalableOptions: Record<string, string> =
      nodes.length >= 500 || edges.length >= 1000
        ? {
            'elk.layered.layering.strategy': 'LONGEST_PATH',
            'elk.layered.nodePlacement.strategy': 'BRANDES_KOEPF',
            'elk.layered.thoroughness': '1',
          }
        : {};
    const groupLayers = [...new Set(nodes.map((node) => node.layer))];
    const groups = ownership
      ? groupLayers.map((layer, index) => ({ id: `lane:${index}`, layer }))
      : [];
    const children: ElkNode[] = ownership
      ? groups.map((group) => ({
          id: group.id,
          layoutOptions: {
            'elk.algorithm': 'layered',
            'elk.direction': 'RIGHT',
            'elk.separateConnectedComponents': 'true',
            'elk.aspectRatio': '1.6',
            'elk.padding': '[top=56,left=24,bottom=24,right=24]',
            'elk.spacing.nodeNode': '36',
            'elk.layered.spacing.nodeNodeBetweenLayers': '60',
            ...scalableOptions,
          },
          children: nodes
            .filter((node) => node.layer === group.layer)
            .map((node) => ({ id: node.id, width, height })),
        }))
      : nodes.map((node) => ({ id: node.id, width, height }));
    const result = await elk.layout({
      id: 'root',
      layoutOptions: {
        'elk.algorithm': 'layered',
        'elk.direction': 'RIGHT',
        'elk.hierarchyHandling': 'INCLUDE_CHILDREN',
        'elk.separateConnectedComponents': 'true',
        'elk.spacing.componentComponent': '70',
        'elk.aspectRatio': '1.6',
        'elk.layered.spacing.nodeNodeBetweenLayers': '100',
        'elk.spacing.nodeNode': '40',
        'elk.layered.nodePlacement.strategy': 'NETWORK_SIMPLEX',
        'elk.layered.compaction.postCompaction.strategy': 'EDGE_LENGTH',
        ...scalableOptions,
        'elk.padding': '[top=24,left=24,bottom=24,right=24]',
      },
      children,
      edges: edges.map((edge) => ({
        id: edge.id,
        sources: [edge.source],
        targets: [edge.target],
      })),
    });
    const output: LayoutResult = { positions: {}, groups: [] };
    for (const child of result.children ?? []) {
      if (child.children) {
        const group = groups.find((item) => item.id === child.id)!;
        output.groups.push({
          id: child.id,
          layer: group.layer,
          x: child.x ?? 0,
          y: child.y ?? 0,
          width: child.width ?? 320,
          height: child.height ?? 240,
        });
        for (const node of child.children)
          output.positions[node.id] = {
            x: node.x ?? 24,
            y: node.y ?? 56,
            parentId: child.id,
          };
      } else output.positions[child.id] = { x: child.x ?? 0, y: child.y ?? 0 };
    }
    self.postMessage({ ok: true, result: output });
  } catch (error) {
    self.postMessage({
      ok: false,
      error: error instanceof Error ? error.message : String(error),
    });
  }
};
