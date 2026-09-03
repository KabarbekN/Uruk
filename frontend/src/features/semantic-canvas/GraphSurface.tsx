import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
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
  Focus,
  LayoutGrid,
  LockKeyhole,
  Map as MapIcon,
  Minus,
  Plus,
  Save,
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
  projection,
  savedLayout,
}: {
  runId: string;
  view: CanvasView;
  projection: CanvasProjection;
  savedLayout: CanvasLayout | null;
}) {
  const { t, label } = useT();
  const scope = `${runId}:${view}`;
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
  const layoutRef = useRef<CanvasLayout>(
    useCanvasStore.getState().layouts[scope] ??
      savedLayout ?? { positions: {}, viewport: { x: 0, y: 0, zoom: 1 } },
  );
  const defaultViewport = useRef(
    Object.keys(layoutRef.current.positions).length > 0
      ? layoutRef.current.viewport
      : { x: 0, y: 0, zoom: projection.nodes.length >= 500 ? 0.1 : 1 },
  ).current;
  const freshLayout = useRef(false);
  const initialViewport = useRef(
    Object.keys(layoutRef.current.positions).length > 0,
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
      const shouldFit = freshLayout.current || !initialViewport.current;
      const surface = surfaceRef.current;
      let viewport = layoutRef.current.viewport;
      // Fit known ELK dimensions before mounting nodes to avoid a second costly render.
      if (shouldFit && surface) {
        const bounds = getNodesBounds(groups.length ? groups : mapped);
        viewport = getViewportForBounds(
          bounds,
          surface.clientWidth,
          surface.clientHeight,
          0.1,
          1,
          0.18,
        );
        void flowRef.current.setViewport(viewport);
      } else if (!shouldFit) {
        void flowRef.current.setViewport(layoutRef.current.viewport);
      }
      cancelInstallation = installGraph(
        [...groups, ...mapped],
        graph.edges,
        (batch) => setNodes((current) => [...current, ...batch]),
        (batch) => setInstalledEdges((current) => [...current, ...batch]),
        () => {
          if (cancelled) return;
          // Publish only complete geometry. Cancelled partial DOM never enters persistence.
          layoutRef.current = { positions, viewport };
          completedNodes.current = graph.nodes;
          geometryReady.current = true;
          setCapacityExceeded(!snapshotLayout());
          initialViewport.current = true;
          freshLayout.current = false;
          setArranging(false);
          if (pending.current) setCapacityExceeded(!flush());
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
  const displayNodes = useMemo(
    () =>
      presentation.nodes(
        nodes,
        entitiesById,
        pins,
        compact,
        selectedNodeId,
        label,
      ),
    [presentation, nodes, entitiesById, pins, compact, selectedNodeId, label],
  );
  const edges = useMemo(
    () =>
      presentation.edges(installedEdges, edgesById, compact, selectedEdgeId),
    [presentation, installedEdges, edgesById, compact, selectedEdgeId],
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
      select({ kind: 'edges', id: edge.id, label: String(edge.label ?? '') });
    },
    [select],
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
    <div className="graph-surface" ref={surfaceRef}>
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
              onClick={() => void flow.fitView({ padding: 0.18, maxZoom: 1 })}
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
