import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent,
} from 'react';
import {
  applyNodeChanges,
  Background,
  BackgroundVariant,
  getNodesBounds,
  getViewportForBounds,
  MiniMap,
  Panel,
  ReactFlow,
  useReactFlow,
  useStore,
  type Node,
  type NodeChange,
  type ReactFlowProps,
} from '@xyflow/react';
import {
  ChevronLeft,
  ChevronRight,
  Focus,
  LayoutGrid,
  LockKeyhole,
  Map as MapIcon,
  Minus,
  Plus,
  Save,
  Sparkles,
  UnlockKeyhole,
} from 'lucide-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import LayoutWorker from '../../shared/workers/layout.worker?worker';
import type {
  LayoutRequest,
  LayoutResult,
} from '../../shared/workers/layout.worker';
import type {
  CanvasLayout,
  CanvasProjection,
  CanvasView,
} from '../../shared/api/types';
import { api } from '../../shared/api/client';
import { useT } from '../../shared/lib/i18n';
import { ErrorState, IconButton, Loading } from '../../shared/ui';
import { SemanticNode } from './SemanticNode';
import { sourceLayer } from './filters';
import {
  boundedLayout,
  restoreSavedLayout,
  validPosition,
} from './layout-cache';
import { useCanvasStore } from './store';
import { installGraph } from './install-graph';
import { createPresentation } from './presentation';

const nodeTypes = { semantic: SemanticNode };
const emptyPins: string[] = [];

export function GraphSurface({
  runId,
  view,
  rootNodeId,
  projection,
  savedLayout,
}: {
  runId: string;
  view: CanvasView;
  rootNodeId?: string;
  projection: CanvasProjection;
  savedLayout: CanvasLayout | null;
}) {
  const { t, label } = useT();
  const scope = rootNodeId
    ? `${runId}:${view}:${rootNodeId}`
    : `${runId}:${view}`;
  const flow = useReactFlow();
  const flowRef = useRef(flow);
  flowRef.current = flow;
  const surfaceRef = useRef<HTMLDivElement>(null);
  const detailZoom = useStore((state) => state.transform[2] >= 0.45);
  const compact = projection.nodes.length >= 500 && !detailZoom;
  const client = useQueryClient();
  const [nodes, setNodes] = useState<Node[]>([]);
  const [installedEdges, setInstalledEdges] = useState<
    CanvasProjection['edges']
  >([]);
  const [presentation] = useState(createPresentation);
  const geometryReady = useRef(false);
  const completedNodes = useRef(projection.nodes);
  const [layoutError, setLayoutError] = useState<Error | null>(null);
  const [capacityExceeded, setCapacityExceeded] = useState(false);
  const [arranging, setArranging] = useState(true);
  const [generation, setGeneration] = useState(0);
  const [locked, setLocked] = useState(false);
  const [showMinimap, setShowMinimap] = useState(false);
  const selectedNodeId = useCanvasStore((state) =>
    state.selected?.kind === 'nodes' ? state.selected.id : null,
  );
  const selectedEdgeId = useCanvasStore((state) =>
    state.selected?.kind === 'edges' ? state.selected.id : null,
  );
  const select = useCanvasStore((state) => state.select);
  const pins = useCanvasStore((state) => state.pins[scope] ?? emptyPins);
  const useAiLabels = useCanvasStore((state) => state.useAiLabels);
  const toggleAiLabels = useCanvasStore((state) => state.toggleAiLabels);
  const layoutRef = useRef<CanvasLayout>(
    useCanvasStore.getState().layouts[scope] ??
      (rootNodeId ? null : savedLayout) ?? {
        positions: {},
        viewport: { x: 0, y: 0, zoom: 1 },
      },
  );
  const defaultViewport = useRef(
    Object.keys(layoutRef.current.positions).length > 0
      ? layoutRef.current.viewport
      : { x: 0, y: 0, zoom: projection.nodes.length >= 500 ? 0.1 : 1 },
  ).current;
  const freshLayout = useRef(Boolean(rootNodeId));
  const initialViewport = useRef(
    !rootNodeId && Object.keys(layoutRef.current.positions).length > 0,
  );
  const latestProjection = useRef(projection);
  latestProjection.current = projection;
  const save = useMutation({
    scope: { id: `layout:${scope}` },
    mutationFn: (body: CanvasLayout) => api.saveLayout(runId, view, body),
    onSuccess: (_result, body) =>
      client.setQueryData(['layout', runId, view], body),
  });
  const saveRef = useRef(save.mutate);
  saveRef.current = save.mutate;
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const pending = useRef(false);
  const snapshotLayout = useCallback(() => {
    const snapshot = boundedLayout(
      layoutRef.current,
      completedNodes.current,
      useCanvasStore.getState().pins[scope] ?? emptyPins,
    );
    if (snapshot) layoutRef.current = snapshot;
    // An over-capacity protected layout stays local until the user can save it.
    useCanvasStore.getState().rememberLayout(scope, layoutRef.current);
    return snapshot;
  }, [scope]);
  const flush = useCallback(() => {
    if (!pending.current) return true;
    pending.current = false;
    // PUT replaces the whole layout. Recheck the current root and pins at flush time.
    const snapshot = snapshotLayout();
    if (!snapshot) return false;
    saveRef.current(snapshot);
    return true;
  }, [snapshotLayout]);
  const persist = useCallback(
    (immediate = false) => {
      if (!geometryReady.current) return;
      clearTimeout(timer.current);
      const snapshot = snapshotLayout();
      setCapacityExceeded(!snapshot);
      pending.current = !!snapshot;
      if (!snapshot) return;
      if (immediate) flush();
      else timer.current = setTimeout(() => setCapacityExceeded(!flush()), 700);
    },
    [snapshotLayout, flush],
  );
  useEffect(() => {
    useCanvasStore
      .getState()
      .restorePins(scope, layoutRef.current.pinnedStableKeys ?? []);
    return useCanvasStore.subscribe((state, previous) => {
      if (state.pins[scope] !== previous.pins[scope]) {
        if (!geometryReady.current) pending.current = true;
        persist();
      }
    });
  }, [scope, persist]);
  useEffect(
    () => () => {
      clearTimeout(timer.current);
      flush();
    },
    [flush],
  );
  const topology = useMemo(
    () =>
      JSON.stringify({
        nodes: projection.nodes.map((node) => [node.id, sourceLayer(node)]),
        edges: projection.edges.map((edge) => [
          edge.id,
          edge.source,
          edge.target,
        ]),
      }),
    [projection],
  );
  useEffect(() => {
    const graph = latestProjection.current;
    let cancelled = false;
    let cancelInstallation = () => {};
    geometryReady.current = false;
    clearTimeout(timer.current);
    setArranging(true);
    setLayoutError(null);
    setNodes([]);
    setInstalledEdges([]);
    const installLayout = (result: LayoutResult) => {
      const currentPins = useCanvasStore.getState().pins[scope] ?? [];
      const groups: Node[] = result.groups.map((group) => ({
        id: group.id,
        type: 'group',
        position: { x: group.x, y: group.y },
        data: { label: group.layer },
        style: { width: group.width, height: group.height },
        width: group.width,
        height: group.height,
        draggable: false,
        selectable: false,
        ariaLabel: group.layer,
      }));
      const mapped: Node[] = graph.nodes.map((entity) => {
        const position = result.positions[entity.id]!;
        const saved = layoutRef.current.positions[entity.stableKey];
        const keep =
          validPosition(saved) &&
          (!freshLayout.current || currentPins.includes(entity.stableKey));
        return {
          id: entity.id,
          type: 'semantic',
          width: 264,
          height: 150,
          position: keep ? saved! : { x: position.x, y: position.y },
          ...(position.parentId
            ? { parentId: position.parentId, extent: 'parent' as const }
            : {}),
          data: { entity, pinned: currentPins.includes(entity.stableKey) },
          ariaLabel: entity.label,
        };
      });
      const entities = new Map(
        graph.nodes.map((entity) => [entity.id, entity]),
      );
      const positions = { ...layoutRef.current.positions };
      for (const node of mapped)
        positions[entities.get(node.id)!.stableKey] = node.position;
      // Find initial / root endpoint node to center on
      const targetRootId =
        rootNodeId ||
        (graph.nodes.length <= 15
          ? graph.nodes.find(
              (n) => n.kind === 'ENDPOINT' || n.kind === 'BUSINESS_SCENARIO',
            )?.id
          : undefined);
      const rootNode = targetRootId
        ? mapped.find((n) => n.id === targetRootId)
        : null;

      let rootCenter: { x: number; y: number } | null = null;
      if (rootNode) {
        let absX = rootNode.position.x;
        let absY = rootNode.position.y;
        if (rootNode.parentId) {
          const parentGroup = groups.find((g) => g.id === rootNode.parentId);
          if (parentGroup) {
            absX += parentGroup.position.x;
            absY += parentGroup.position.y;
          }
        }
        const w = rootNode.width ?? 264;
        const h = rootNode.height ?? 150;
        rootCenter = {
          x: absX + w / 2,
          y: absY + h / 2,
        };
      }

      const shouldFit =
        freshLayout.current || !initialViewport.current || Boolean(rootNodeId);
      const surface = surfaceRef.current;
      let viewport = layoutRef.current.viewport;

      // Center immediately on the start node at 100% zoom when focused on an endpoint
      if (rootCenter && surface) {
        const hasRightDock = Boolean(selectedNodeId || rootNodeId) && surface.clientWidth >= 1000;
        const rightDockWidth = hasRightDock ? Math.min(580, surface.clientWidth * 0.42) : 0;
        const visibleWidth = surface.clientWidth - rightDockWidth;
        const targetScreenX = Math.max(150, visibleWidth / 2);
        const targetScreenY = surface.clientHeight / 2;
        const zoom = 1.0;
        viewport = {
          x: targetScreenX - rootCenter.x * zoom,
          y: targetScreenY - rootCenter.y * zoom,
          zoom,
        };
        if (typeof flowRef.current.setViewport === 'function') {
          void flowRef.current.setViewport(viewport);
        }
      } else if (shouldFit && surface) {
        const bounds = getNodesBounds(groups.length ? groups : mapped);
        viewport = getViewportForBounds(
          bounds,
          surface.clientWidth,
          surface.clientHeight,
          0.1,
          1,
          0.2,
        );
        if (typeof flowRef.current.setViewport === 'function') {
          void flowRef.current.setViewport(viewport);
        }
      } else if (!shouldFit) {
        if (typeof flowRef.current.setViewport === 'function') {
          void flowRef.current.setViewport(layoutRef.current.viewport);
        }
      }
      cancelInstallation = installGraph(
        [...groups, ...mapped],
        graph.edges,
        (batch) => setNodes((current) => [...current, ...batch]),
        (batch) => setInstalledEdges((current) => [...current, ...batch]),
        () => {
          if (cancelled) return;
          const previousPins = layoutRef.current.pinnedStableKeys ?? [];
          const currentPins = useCanvasStore.getState().pins[scope] ?? [];
          // List selection can pin before geometry exists. Persist that change
          // once the graph has real positions for the selected stable keys.
          if (
            currentPins.length !== previousPins.length ||
            currentPins.some((key) => !previousPins.includes(key))
          )
            pending.current = true;
          // Publish only complete geometry. Cancelled partial DOM never enters persistence.
          layoutRef.current = { positions, viewport };
          completedNodes.current = graph.nodes;
          geometryReady.current = true;
          setCapacityExceeded(!snapshotLayout());
          initialViewport.current = true;
          freshLayout.current = false;
          setArranging(false);
          if (pending.current) setCapacityExceeded(!flush());

          if (rootCenter && surfaceRef.current) {
            const surfaceEl = surfaceRef.current;
            setTimeout(() => {
              const hasRightDock = Boolean(selectedNodeId || rootNodeId) && surfaceEl.clientWidth >= 1000;
              const rightDockWidth = hasRightDock ? Math.min(580, surfaceEl.clientWidth * 0.42) : 0;
              const visibleWidth = surfaceEl.clientWidth - rightDockWidth;
              const targetScreenX = Math.max(150, visibleWidth / 2);
              const targetScreenY = surfaceEl.clientHeight / 2;
              const zoom = 1.0;
              if (typeof flowRef.current.setViewport === 'function') {
                void flowRef.current.setViewport(
                  {
                    x: targetScreenX - rootCenter.x * zoom,
                    y: targetScreenY - rootCenter.y * zoom,
                    zoom,
                  },
                  { duration: 250 },
                );
              }
            }, 60);
          } else if (shouldFit) {
            setTimeout(() => {
              if (typeof flowRef.current.fitView === 'function') {
                void flowRef.current.fitView({ padding: 0.2, maxZoom: 1 });
              }
            }, 60);
          }
        },
      );
    };
    const restored = restoreSavedLayout(
      graph.nodes,
      layoutRef.current,
      view,
      freshLayout.current,
    );
    if (restored) {
      installLayout(restored);
      return () => {
        cancelled = true;
        cancelInstallation();
      };
    }
    const worker = new LayoutWorker();
    let done = false;
    const timeout = setTimeout(() => {
      if (!done) {
        done = true;
        worker.terminate();
        setLayoutError(new Error('Layout timeout'));
        setArranging(false);
      }
    }, 30000);
    worker.onmessage = (
      event: MessageEvent<{
        ok: boolean;
        result?: LayoutResult;
        error?: string;
      }>,
    ) => {
      if (done) return;
      done = true;
      clearTimeout(timeout);
      const result = event.data.result;
      if (!event.data.ok || !result) {
        setLayoutError(new Error(event.data.error));
        setArranging(false);
        worker.terminate();
        return;
      }
      installLayout(result);
      worker.terminate();
    };
    worker.onerror = () => {
      done = true;
      clearTimeout(timeout);
      setArranging(false);
      setLayoutError(new Error('Worker failure'));
      worker.terminate();
    };
    const request: LayoutRequest = {
      nodes: graph.nodes.map((node) => ({
        id: node.id,
        layer: sourceLayer(node),
      })),
      edges: graph.edges,
      ownership: view === 'DATA_OWNERSHIP',
    };
    worker.postMessage(request);
    return () => {
      cancelled = true;
      cancelInstallation();
      done = true;
      clearTimeout(timeout);
      worker.terminate();
    };
  }, [topology, generation, scope, view, snapshotLayout, flush]);
  const entitiesById = useMemo(
    () => new Map(projection.nodes.map((node) => [node.id, node])),
    [projection.nodes],
  );
  const edgesById = useMemo(
    () => new Map(projection.edges.map((edge) => [edge.id, edge])),
    [projection.edges],
  );
  // Compute call path step levels from scenario origin (rootNodeId, sole endpoint, or by walking back along FLOWS_TO from selectedNodeId)
  const scenarioOriginId = useMemo(() => {
    if (rootNodeId) return rootNodeId;
    if (!selectedNodeId) {
      const endpoints = projection.nodes.filter(
        (n) => n.kind === 'ENDPOINT' || n.kind === 'BUSINESS_SCENARIO',
      );
      if (endpoints.length === 1 && endpoints[0]) return endpoints[0].id;
      return undefined;
    }

    // Map incoming edges to find the root endpoint of the scenario
    const incomingFlowsTo = new Map<string, string>();
    const incomingBranches = new Map<string, string>();

    for (const edge of projection.edges) {
      if (edge.kind === 'RETURNS') continue;
      if (edge.kind === 'FLOWS_TO') {
        incomingFlowsTo.set(edge.target, edge.source);
      } else {
        incomingBranches.set(edge.target, edge.source);
      }
    }

    // If selected node is a branch (e.g. exception), hop to its parent spine step first
    let curr = selectedNodeId;
    const branchParent = incomingBranches.get(curr);
    if (branchParent) {
      curr = branchParent;
    }

    // Walk backwards along FLOWS_TO to find the origin of the pipeline
    const visited = new Set<string>();
    while (curr && !visited.has(curr)) {
      visited.add(curr);
      const prev = incomingFlowsTo.get(curr);
      if (!prev) break;
      curr = prev;
    }

    return curr;
  }, [rootNodeId, selectedNodeId, projection.nodes, projection.edges]);

  const stepDistances = useMemo(() => {
    if (!scenarioOriginId) return new Map<string, number>();
    const steps = new Map<string, number>();
    steps.set(scenarioOriginId, 0);

    const adj = new Map<string, string[]>();
    for (const edge of projection.edges) {
      if (edge.kind === 'RETURNS') continue;
      const list = adj.get(edge.source) ?? [];
      list.push(edge.target);
      adj.set(edge.source, list);
    }

    const queue: [string, number][] = [[scenarioOriginId, 0]];
    while (queue.length > 0) {
      const [curr, d] = queue.shift()!;
      if (d >= 8) continue;
      const neighbors = adj.get(curr) ?? [];
      for (const next of neighbors) {
        if (!steps.has(next)) {
          steps.set(next, d + 1);
          queue.push([next, d + 1]);
        }
      }
    }
    return steps;
  }, [scenarioOriginId, projection.edges]);

  // Sequential list of step nodes for keyboard arrow navigation (← / →)
  const orderedStepSequence = useMemo(() => {
    const flowsToAdj = new Map<string, string>();
    const branchesAdj = new Map<string, string[]>();

    for (const edge of projection.edges) {
      if (edge.kind === 'RETURNS') continue;
      if (edge.kind === 'FLOWS_TO') {
        flowsToAdj.set(edge.source, edge.target);
      } else {
        const list = branchesAdj.get(edge.source) ?? [];
        list.push(edge.target);
        branchesAdj.set(edge.source, list);
      }
    }

    const sequence: string[] = [];
    const visited = new Set<string>();

    if (scenarioOriginId) {
      let curr: string | undefined = scenarioOriginId;
      while (curr && !visited.has(curr)) {
        visited.add(curr);
        sequence.push(curr);

        const branches = branchesAdj.get(curr) ?? [];
        for (const b of branches) {
          if (!visited.has(b)) {
            visited.add(b);
            sequence.push(b);
          }
        }

        curr = flowsToAdj.get(curr);
      }
    }

    if (sequence.length <= 1) {
      if (stepDistances.size > 1) {
        return Array.from(stepDistances.entries())
          .sort((a, b) => a[1] - b[1])
          .map(([id]) => id);
      }
      // General canvas fallback: order nodes by horizontal X position (left to right)
      const sorted = [...projection.nodes].sort((a, b) => {
        const na = nodes.find((n) => n.id === a.id);
        const nb = nodes.find((n) => n.id === b.id);
        if (na && nb) {
          if (Math.abs(na.position.x - nb.position.x) > 40) {
            return na.position.x - nb.position.x;
          }
          return na.position.y - nb.position.y;
        }
        return a.label.localeCompare(b.label);
      });
      return sorted.map((n) => n.id);
    }

    return sequence;
  }, [scenarioOriginId, projection.edges, projection.nodes, nodes, stepDistances]);

  const currentStepIndex = selectedNodeId
    ? orderedStepSequence.indexOf(selectedNodeId)
    : -1;

  const centerOnNode = useCallback(
    (targetId: string, duration = 300) => {
      const surface = surfaceRef.current;
      const flowNode = nodes.find((n) => n.id === targetId);
      if (!surface || !flowNode) return;

      let absX = flowNode.position.x;
      let absY = flowNode.position.y;
      if (flowNode.parentId) {
        const parentNode = nodes.find((n) => n.id === flowNode.parentId);
        if (parentNode) {
          absX += parentNode.position.x;
          absY += parentNode.position.y;
        }
      }
      const nodeWidth = flowNode.width ?? 264;
      const nodeHeight = flowNode.height ?? 150;
      const centerX = absX + nodeWidth / 2;
      const centerY = absY + nodeHeight / 2;

      const hasRightDock = Boolean(selectedNodeId || rootNodeId) && surface.clientWidth >= 1000;
      const rightDockWidth = hasRightDock ? Math.min(580, surface.clientWidth * 0.42) : 0;
      const visibleWidth = surface.clientWidth - rightDockWidth;
      const targetScreenX = Math.max(150, visibleWidth / 2);
      const targetScreenY = surface.clientHeight / 2;

      const zoom = 1.0;
      const nextViewport = {
        x: targetScreenX - centerX * zoom,
        y: targetScreenY - centerY * zoom,
        zoom,
      };

      if (typeof flowRef.current.setViewport === 'function') {
        void flowRef.current.setViewport(nextViewport, { duration });
      }
    },
    [nodes, selectedNodeId, rootNodeId],
  );

  useEffect(() => {
    const handleCenterRoot = () => {
      const targetId = rootNodeId || scenarioOriginId;
      if (targetId) {
        centerOnNode(targetId, 350);
      }
    };

    window.addEventListener('center-root-node', handleCenterRoot);
    return () => {
      window.removeEventListener('center-root-node', handleCenterRoot);
    };
  }, [rootNodeId, scenarioOriginId, centerOnNode]);

  const navigateStep = useCallback(
    (direction: 1 | -1) => {
      if (orderedStepSequence.length === 0) return;

      const currentIdx = selectedNodeId
        ? orderedStepSequence.indexOf(selectedNodeId)
        : -1;

      let nextIdx: number;
      if (currentIdx === -1) {
        nextIdx = direction === 1 ? 0 : orderedStepSequence.length - 1;
      } else {
        nextIdx =
          (currentIdx + direction + orderedStepSequence.length) %
          orderedStepSequence.length;
      }

      const targetId = orderedStepSequence[nextIdx];
      if (!targetId) return;
      const entity = entitiesById.get(targetId);
      if (!entity) return;

      select({
        kind: 'nodes',
        id: targetId,
        label: entity.label,
      });

      centerOnNode(targetId, 350);
    },
    [orderedStepSequence, selectedNodeId, entitiesById, select, centerOnNode],
  );

  useEffect(() => {
    const handleKeyDown = (e: globalThis.KeyboardEvent) => {
      if (orderedStepSequence.length <= 1) return;

      const active = document.activeElement;
      if (
        active &&
        (active.tagName === 'INPUT' ||
          active.tagName === 'TEXTAREA' ||
          active.tagName === 'SELECT' ||
          (active as HTMLElement).isContentEditable)
      ) {
        return;
      }

      // Do not capture if inside a modal dialog (e.g. ModelManagerModal)
      if (
        active?.closest('.modal-backdrop, .modal-dialog, dialog') ||
        document.querySelector('.modal-backdrop, .modal-dialog')
      ) {
        return;
      }

      const isRight =
        e.key === 'ArrowRight' ||
        e.key === 'ArrowDown' ||
        e.code === 'ArrowRight' ||
        e.code === 'ArrowDown';
      const isLeft =
        e.key === 'ArrowLeft' ||
        e.key === 'ArrowUp' ||
        e.code === 'ArrowLeft' ||
        e.code === 'ArrowUp';

      if (isRight) {
        e.preventDefault();
        e.stopPropagation();
        navigateStep(1);
      } else if (isLeft) {
        e.preventDefault();
        e.stopPropagation();
        navigateStep(-1);
      }
    };

    window.addEventListener('keydown', handleKeyDown, true);
    return () => window.removeEventListener('keydown', handleKeyDown, true);
  }, [orderedStepSequence, navigateStep]);

  const displayNodes = useMemo(
    () =>
      presentation.nodes(
        nodes,
        entitiesById,
        pins,
        compact,
        selectedNodeId,
        label,
        stepDistances,
      ),
    [
      presentation,
      nodes,
      entitiesById,
      pins,
      compact,
      selectedNodeId,
      label,
      stepDistances,
    ],
  );
  const edges = useMemo(
    () =>
      presentation.edges(
        installedEdges,
        edgesById,
        compact,
        selectedEdgeId,
        stepDistances,
      ),
    [
      presentation,
      installedEdges,
      edgesById,
      compact,
      selectedEdgeId,
      stepDistances,
    ],
  );
  const onNodesChange = useCallback(
    (changes: NodeChange[]) =>
      setNodes((current) =>
        applyNodeChanges(
          changes.filter((change) => change.type !== 'remove'),
          current,
        ),
      ),
    [],
  );
  const onNodeClick = useCallback<NonNullable<ReactFlowProps['onNodeClick']>>(
    (_event, node) => {
      if (node.type === 'semantic')
        select({
          kind: 'nodes',
          id: node.id,
          label: node.ariaLabel ?? node.id,
        });
    },
    [select],
  );
  const onEdgeClick = useCallback<NonNullable<ReactFlowProps['onEdgeClick']>>(
    (_event, edge) => {
      select({ kind: 'edges', id: edge.id, label: edge.ariaLabel ?? edge.id });
    },
    [select],
  );
  // React Flow's keyboard selection updates its own store. Evidence selection
  // belongs to our UI store, so activate focused wrappers through the same path.
  const onGraphKeyDown = useCallback(
    (event: KeyboardEvent<HTMLDivElement>) => {
      if (event.repeat || !['Enter', ' '].includes(event.key)) return;
      const target = event.target;
      if (!(target instanceof Element)) return;
      const id = target.getAttribute('data-id');
      if (!id) return;
      const node = target.matches('.react-flow__node')
        ? entitiesById.get(id)
        : undefined;
      const edge = target.matches('.react-flow__edge')
        ? edgesById.get(id)
        : undefined;
      if (!node && !edge) return;
      event.preventDefault();
      event.stopPropagation();
      select({
        kind: node ? 'nodes' : 'edges',
        id,
        label: node?.label ?? edge!.label ?? edge!.kind,
      });
    },
    [entitiesById, edgesById, select],
  );
  const onPaneClick = useCallback(() => select(null), [select]);
  const onNodeDragStop = useCallback<
    NonNullable<ReactFlowProps['onNodeDragStop']>
  >(
    (_event, node) => {
      if (!geometryReady.current) return;
      const entity = latestProjection.current.nodes.find(
        (item) => item.id === node.id,
      );
      if (entity) {
        layoutRef.current = {
          positions: {
            ...layoutRef.current.positions,
            [entity.stableKey]: node.position,
          },
          viewport: flowRef.current.getViewport(),
        };
        persist();
      }
    },
    [persist],
  );
  const onMoveEnd = useCallback<NonNullable<ReactFlowProps['onMoveEnd']>>(
    (event, viewport) => {
      if (!geometryReady.current) return;
      layoutRef.current = { ...layoutRef.current, viewport };
      if (event) persist();
    },
    [persist],
  );
  return (
    <div
      className="graph-surface"
      ref={surfaceRef}
      onKeyDownCapture={onGraphKeyDown}
    >
      <ReactFlow
        nodes={displayNodes}
        edges={edges}
        nodeTypes={nodeTypes}
        onNodesChange={onNodesChange}
        onNodeClick={onNodeClick}
        onEdgeClick={onEdgeClick}
        onPaneClick={onPaneClick}
        onNodeDragStop={onNodeDragStop}
        onMoveEnd={onMoveEnd}
        nodesDraggable={!locked}
        nodesConnectable={false}
        edgesReconnectable={false}
        deleteKeyCode={null}
        panOnDrag={!locked}
        zoomOnScroll={!locked}
        minZoom={0.1}
        maxZoom={2}
        onlyRenderVisibleElements
        fitView={false}
        defaultViewport={defaultViewport}
        proOptions={{ hideAttribution: false }}
      >
        <Background
          variant={BackgroundVariant.Dots}
          gap={20}
          size={1}
          color="#ced6d3"
        />
        <Panel position="bottom-left">
          <div className="canvas-controls">
            <IconButton
              icon={Plus}
              label={t('zoomIn')}
              onClick={() => void flow.zoomIn()}
            />
            <IconButton
              icon={Minus}
              label={t('zoomOut')}
              onClick={() => void flow.zoomOut()}
            />
            <IconButton
              icon={Focus}
              label={t('fit')}
              onClick={() => {
                const targetId = rootNodeId || scenarioOriginId;
                if (targetId) {
                  centerOnNode(targetId, 300);
                } else {
                  void flow.fitView({ padding: 0.18, maxZoom: 1 });
                }
              }}
            />
            <span className="control-divider" />
            <IconButton
              icon={LayoutGrid}
              label={t('layout')}
              onClick={() => {
                freshLayout.current = true;
                setGeneration((value) => value + 1);
              }}
            />
            <IconButton
              icon={Sparkles}
              label={useAiLabels ? t('aiHumanReadable') : t('aiTechnicalNames')}
              aria-pressed={useAiLabels}
              onClick={toggleAiLabels}
            />
            <IconButton
              icon={locked ? LockKeyhole : UnlockKeyhole}
              label={t(locked ? 'unlock' : 'lock')}
              aria-pressed={locked}
              onClick={() => setLocked(!locked)}
            />
            <IconButton
              icon={MapIcon}
              label={t('minimap')}
              aria-pressed={showMinimap}
              onClick={() => setShowMinimap(!showMinimap)}
            />
            <IconButton
              icon={Save}
              label={t('saveLayout')}
              disabled={save.isPending || arranging}
              onClick={() => {
                layoutRef.current = {
                  ...layoutRef.current,
                  viewport: flow.getViewport(),
                };
                persist(true);
              }}
            />
          </div>
        </Panel>
        {orderedStepSequence.length > 1 && (
          <Panel position="bottom-center">
            <div className="stepper-hud">
              <button
                type="button"
                onClick={() => navigateStep(-1)}
                title="Предыдущий шаг (клавиша ← или ↑)"
                aria-label="Предыдущий шаг"
              >
                <ChevronLeft size={15} />
              </button>
              <div
                className="stepper-info"
                style={{ cursor: 'pointer' }}
                onClick={() => {
                  const targetId = rootNodeId || scenarioOriginId;
                  if (targetId) centerOnNode(targetId, 300);
                }}
                title="Нажмите, чтобы центрировать на начальной точке"
              >
                {currentStepIndex >= 0 ? (
                  <>
                    <span>
                      Шаг <strong>{currentStepIndex + 1}</strong> из{' '}
                      <strong>{orderedStepSequence.length}</strong>
                    </span>
                    <span className="stepper-hint">(← / →)</span>
                  </>
                ) : (
                  <>
                    <span>{orderedStepSequence.length} шагов в сценарии</span>
                    <span className="stepper-hint">(нажмите → для навигации)</span>
                  </>
                )}
              </div>
              <button
                type="button"
                onClick={() => navigateStep(1)}
                title="Следующий шаг (клавиша → или ↓)"
                aria-label="Следующий шаг"
              >
                <ChevronRight size={15} />
              </button>
            </div>
          </Panel>
        )}
        {showMinimap && (
          <MiniMap position="top-right" pannable zoomable nodeColor="#a9d7cf" />
        )}
      </ReactFlow>
      {arranging && (
        <div className="graph-overlay">
          <Loading compact />
        </div>
      )}
      {layoutError && (
        <div className="graph-message">
          <ErrorState
            error={layoutError}
            retry={() => setGeneration((value) => value + 1)}
          />
        </div>
      )}
      {capacityExceeded && (
        <div className="layout-error" role="alert">
          <strong>{t('layoutSaveFailed')}</strong>
          <p>{t('layoutCapacityExceeded')}</p>
        </div>
      )}
      {save.isError && !capacityExceeded && (
        <div className="layout-error">
          <strong>{t('layoutSaveFailed')}</strong>
          <ErrorState compact error={save.error} retry={() => persist(true)} />
        </div>
      )}
    </div>
  );
}
