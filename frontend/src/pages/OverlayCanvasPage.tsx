import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ReactFlowProvider } from '@xyflow/react';
import { useShallow } from 'zustand/react/shallow';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import {
  ArrowLeft,
  BookOpenText,
  Braces,
  ChevronDown,
  ChevronRight,
  Database,
  Gauge,
  GitFork,
  LayoutDashboard,
  PanelLeft,
  RefreshCw,
  Search,
  Settings,
  ShieldCheck,
  SlidersHorizontal,
  Sparkles,
  Square,
  Table,
  Target,
  Workflow,
  X,
} from 'lucide-react';
import { api } from '../shared/api/client';
import type {
  CanvasView,
  ControllerEndpoint,
} from '../shared/api/types';
import { useRun } from '../shared/api/queries';
import { useDebounced } from '../shared/hooks/useDebounced';
import { useT } from '../shared/lib/i18n';
import {
  Badge,
  Button,
  ErrorState,
  Field,
  IconButton,
  Loading,
} from '../shared/ui';
import { notify } from '../shared/ui/notifications';
import {
  useCanvasStore,
  type FilterFlag,
} from '../features/semantic-canvas/store';
import {
  matchesFilters,
  property,
  sourceLayer,
} from '../features/semantic-canvas/filters';
import { GraphSurface } from '../features/semantic-canvas/GraphSurface';
import { EvidenceDrawer } from '../features/evidence-drawer/EvidenceDrawer';
import { AllEndpointsCatalog } from '../features/endpoint-stories/AllEndpointsCatalog';
import { AnalysisOverview } from '../features/semantic-canvas/AnalysisOverview';
import { ModelManagerModal } from '../features/project-management/ModelManagerModal';
import { humanizeEndpoint } from '../features/semantic-canvas/endpoint-humanizer';
import './overlay-canvas.css';

const viewItems = [
  { value: 'BUSINESS' as CanvasView, icon: Workflow },
  { value: 'DEVELOPER' as CanvasView, icon: Braces },
  { value: 'DATA_OWNERSHIP' as CanvasView, icon: Database },
  { value: 'SECURITY' as CanvasView, icon: ShieldCheck },
  { value: 'DATA_PIPELINE' as CanvasView, icon: GitFork },
  { value: 'CONFIDENCE' as CanvasView, icon: Gauge },
];

const filterFlags: FilterFlag[] = [
  'onlyUnreviewed',
  'lowConfidence',
  'unresolved',
  'withoutTests',
  'authRules',
  'businessRules',
  'dbWrites',
  'externalCalls',
  'llmEnriched',
  'onlyChanged',
];

export default function OverlayCanvasPage() {
  const { analysisRunId = '' } = useParams();
  const [params] = useSearchParams();
  const { t, label, locale } = useT();
  const run = useRun(analysisRunId);

  const detailLabels =
    locale === 'ru'
      ? ['Контексты', 'Сценарии', 'Правила и эффекты', 'SQL и код']
      : ['Contexts', 'Scenarios', 'Rules & Effects', 'SQL & Code'];

  const state = useCanvasStore(
    useShallow((s) => ({
      view: s.view,
      depth: s.depth,
      detail: s.detail,
      minConfidence: s.minConfidence,
      search: s.search,
      rootNodeId: s.rootNodeId,
      filters: s.filters,
      selected: s.selected,
      useAiLabels: s.useAiLabels,
      select: s.select,
      setView: s.setView,
      setDepth: s.setDepth,
      setDetail: s.setDetail,
      setConfidence: s.setConfidence,
      setSearch: s.setSearch,
      setRoot: s.setRoot,
      setFilters: s.setFilters,
      toggleAiLabels: s.toggleAiLabels,
    })),
  );

  const { select, setRoot, setSearch } = state;

  // Floating Popovers & Dock Visibility State
  const [isViewMenuOpen, setIsViewMenuOpen] = useState(false);
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const [isLeftDockOpen, setIsLeftDockOpen] = useState(false);
  const [isLeftDockMinimized, setIsLeftDockMinimized] = useState(false);
  const [leftDockTab, setLeftDockTab] = useState<'controllers' | 'table'>('controllers');
  const [leftFilterSearch, setLeftFilterSearch] = useState('');
  const [leftMethodFilter, setLeftMethodFilter] = useState<string>('ALL');
  const [expandedControllers, setExpandedControllers] = useState<Record<string, boolean>>({});

  const [showFilters, setShowFilters] = useState(false);
  const [showOverview, setShowOverview] = useState(false);
  const [showModelManager, setShowModelManager] = useState(false);
  const [isCancelling, setIsCancelling] = useState(false);
  const [enrichingNodeId, setEnrichingNodeId] = useState<string | null>(null);
  const [isEnrichingPipeline, setIsEnrichingPipeline] = useState(false);

  const search = useDebounced(state.search);
  const hasAutoFocused = useRef(false);

  useEffect(() => {
    select(null);
    setRoot(undefined);
    setSearch(params.get('search') ?? '');
    hasAutoFocused.current = false;
  }, [analysisRunId, params, select, setRoot, setSearch]);

  // Canvas Projection Query
  const projection = useQuery({
    queryKey: [
      'canvas',
      analysisRunId,
      state.view,
      state.depth,
      state.detail,
      state.filters.onlyChanged,
      state.minConfidence,
      state.rootNodeId,
      search,
    ],
    queryFn: ({ signal }) =>
      api.canvas(
        analysisRunId,
        {
          view: state.view,
          depth: state.depth,
          levelOfDetail: state.detail,
          onlyChanged: Boolean(state.filters.onlyChanged),
          minConfidence: state.minConfidence,
          rootNodeId: state.rootNodeId,
          search,
        },
        signal,
      ),
    enabled: Boolean(run.data),
  });

  // Saved Layout Query
  const savedLayout = useQuery({
    queryKey: ['layout', analysisRunId, state.view],
    queryFn: ({ signal }) => api.layout(analysisRunId, state.view, signal),
    enabled: Boolean(run.data),
    staleTime: Infinity,
  });

  // Controllers Query
  const controllersQuery = useQuery({
    queryKey: ['controllers', analysisRunId],
    queryFn: ({ signal }) => api.getControllers(analysisRunId, signal),
  });

  // Enrichment Progress Query for Status HUD
  const progressQuery = useQuery({
    queryKey: ['enrichmentProgress', analysisRunId],
    queryFn: ({ signal }) => api.getEnrichmentProgress(analysisRunId, signal),
    refetchInterval: 2500,
  });

  // Expand first 3 controllers by default when data arrives
  useEffect(() => {
    if (controllersQuery.data && controllersQuery.data.length > 0) {
      const initial: Record<string, boolean> = {};
      controllersQuery.data.slice(0, 3).forEach((c) => {
        initial[c.controllerKey] = true;
      });
      setExpandedControllers((prev) => (Object.keys(prev).length === 0 ? initial : prev));
    }
  }, [controllersQuery.data]);

  // Auto-focus first endpoint on initial load to present a clean, readable horizontal map
  useEffect(() => {
    if (hasAutoFocused.current) return;
    if (!controllersQuery.data || controllersQuery.data.length === 0) return;
    if (state.rootNodeId) {
      hasAutoFocused.current = true;
      return;
    }

    for (const ctrl of controllersQuery.data) {
      if (ctrl.endpoints && ctrl.endpoints.length > 0) {
        const firstEp = ctrl.endpoints[0];
        if (!firstEp) continue;
        hasAutoFocused.current = true;
        state.setRoot(firstEp.id);
        state.select({
          kind: 'nodes',
          id: firstEp.id,
          label: firstEp.label,
        });
        state.setDepth(2);
        break;
      }
    }
  }, [controllersQuery.data, state.rootNodeId, state.setRoot, state.select, state.setDepth]);

  const toggleControllerExpand = (key: string) => {
    setExpandedControllers((prev) => ({
      ...prev,
      [key]: !prev[key],
    }));
  };

  const handleCancelEnrichment = async () => {
    setIsCancelling(true);
    try {
      await api.cancelEnrichment(analysisRunId);
      notify('Анализ ИИ успешно остановлен!');
      void progressQuery.refetch();
      void controllersQuery.refetch();
    } catch {
      notify('Не удалось остановить анализ');
    } finally {
      setIsCancelling(false);
    }
  };

  const handleEnrichSingleNode = async (nodeId: string, e: React.MouseEvent) => {
    e.stopPropagation();
    setEnrichingNodeId(nodeId);
    try {
      await api.enrichNode(nodeId);
      notify('Бизнес-история успешно сгенерирована!');
      void controllersQuery.refetch();
      void progressQuery.refetch();
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Ошибка генерации';
      notify(`Ошибка: ${msg}`);
    } finally {
      setEnrichingNodeId(null);
    }
  };

  const handleEnrichPipeline = async () => {
    if (!state.rootNodeId) return;
    setIsEnrichingPipeline(true);
    try {
      notify('Запущен ИИ-анализ логики сценария...');
      await api.enrichPipeline(analysisRunId, state.rootNodeId);
      notify('Бизнес-описание сценария и шагов логики успешно сформировано!');
      await projection.refetch();
      await controllersQuery.refetch();
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Не удалось выполнить запрос';
      notify(`Ошибка генерации: ${msg}`);
    } finally {
      setIsEnrichingPipeline(false);
    }
  };

  const visible = useMemo(() => {
    if (!projection.data) return null;
    let nodes = projection.data.nodes.filter((node) =>
      matchesFilters(node, state.filters),
    );
    if (state.rootNodeId) {
      nodes = nodes.filter(
        (node) => node.kind !== 'ENDPOINT' || node.id === state.rootNodeId,
      );
    }
    const ids = new Set(nodes.map((node) => node.id));
    return {
      ...projection.data,
      nodes,
      edges: projection.data.edges.filter(
        (edge) => ids.has(edge.source) && ids.has(edge.target),
      ),
    };
  }, [projection.data, state.filters, state.rootNodeId]);

  const graphProjection = useMemo(() => {
    if (!visible || visible.edges.length === 0) return visible;
    const connectedIds = new Set(
      visible.edges.flatMap((edge) => [edge.source, edge.target]),
    );
    const isolated = visible.nodes.length - connectedIds.size;
    if (visible.nodes.length < 80 || isolated <= visible.nodes.length / 3)
      return visible;
    return {
      ...visible,
      nodes: visible.nodes.filter((node) => connectedIds.has(node.id)),
    };
  }, [visible]);

  const isolatedHidden =
    visible && graphProjection
      ? visible.nodes.length - graphProjection.nodes.length
      : 0;

  const filterCount = Object.values(state.filters).filter(Boolean).length;

  const currentView = viewItems.find((v) => v.value === state.view) || viewItems[0]!;

  const totalEndpointsCount = useMemo(() => {
    if (!controllersQuery.data) return 0;
    return controllersQuery.data.reduce((acc, c) => acc + c.endpoints.length, 0);
  }, [controllersQuery.data]);

  // Filtered controllers and endpoints for left dock
  const filteredControllers = useMemo(() => {
    if (!controllersQuery.data) return [];
    const q = leftFilterSearch.trim().toLowerCase();
    const method = leftMethodFilter.toUpperCase();

    return controllersQuery.data
      .map((ctrl) => {
        const filteredEps = ctrl.endpoints.filter((ep) => {
          const epMethod = (ep.method || 'GET').toUpperCase();
          if (method !== 'ALL' && epMethod !== method) return false;
          if (!q) return true;
          const fallback = humanizeEndpoint(ep);
          const title = ep.aiResult?.title || ep.aiTitle || fallback.title;
          const path = ep.path || '';
          return (
            title.toLowerCase().includes(q) ||
            path.toLowerCase().includes(q) ||
            ctrl.controllerName.toLowerCase().includes(q)
          );
        });
        return {
          ...ctrl,
          endpoints: filteredEps,
        };
      })
      .filter((ctrl) => ctrl.endpoints.length > 0);
  }, [controllersQuery.data, leftFilterSearch, leftMethodFilter]);

  const handleSelectEndpointFromDock = (endpoint: ControllerEndpoint) => {
    // 1. Select the node for Evidence Drawer
    state.select({
      kind: 'nodes',
      id: endpoint.id,
      label: endpoint.label,
    });
    // 2. Set root node on canvas to calculate step call paths (Шаг 1, 2, 3...)
    if (state.rootNodeId === endpoint.id) {
      window.dispatchEvent(new CustomEvent('center-root-node'));
    } else {
      state.setRoot(endpoint.id);
    }
    if (state.depth > 3) {
      state.setDepth(2);
    }
    // 3. Immediately close the left dock
    setIsLeftDockOpen(false);
  };

  const rootNodeLabel = useMemo(() => {
    if (!state.rootNodeId) return null;
    if (projection.data) {
      const found = projection.data.nodes.find((n) => n.id === state.rootNodeId);
      if (found) return found.label;
    }
    if (controllersQuery.data) {
      for (const c of controllersQuery.data) {
        const ep = c.endpoints.find((e) => e.id === state.rootNodeId);
        if (ep) return ep.path || ep.label;
      }
    }
    return null;
  }, [state.rootNodeId, projection.data, controllersQuery.data]);

  if (run.isPending) return <Loading />;
  if (run.isError)
    return <ErrorState error={run.error} retry={() => void run.refetch()} />;

  return (
    <div
      className="overlay-canvas-page"
      data-testid="overlay-canvas"
      onClick={() => {
        if (isViewMenuOpen) setIsViewMenuOpen(false);
        if (isSettingsOpen) setIsSettingsOpen(false);
      }}
    >
      {/* 1. FULL BACKGROUND CANVAS (Always occupies 100% of width & height) */}
      <div className="overlay-canvas-background">
        {projection.isPending ? (
          <Loading />
        ) : projection.isError ? (
          <ErrorState
            error={projection.error}
            retry={() => void projection.refetch()}
          />
        ) : savedLayout.isPending ? (
          <Loading />
        ) : savedLayout.isError ? (
          <ErrorState
            error={savedLayout.error}
            retry={() => void savedLayout.refetch()}
          />
        ) : (
          graphProjection && (
            <ReactFlowProvider
              key={`${analysisRunId}:${state.view}:${state.rootNodeId ?? 'all'}`}
            >
              <GraphSurface
                runId={analysisRunId}
                view={state.view}
                rootNodeId={state.rootNodeId}
                projection={graphProjection}
                savedLayout={savedLayout.data ?? null}
              />
            </ReactFlowProvider>
          )
        )}
      </div>

      {/* 2. FLOATING OVERLAY LAYER (pointer-events: none, interactive cards: pointer-events: auto) */}
      <div className="overlay-layer">
        {/* 2.1. UNIFIED TOP NAVIGATION BAR */}
        <header className="overlay-glass-card overlay-top-nav">
          {/* Left Section: Back, View Dropdown, Graph Settings */}
          <div className="overlay-nav-section">
            <button
              type="button"
              className="overlay-btn ghost"
              onClick={() => window.dispatchEvent(new CustomEvent('toggle-app-sidebar'))}
              title={locale === 'ru' ? 'Скрыть/показать боковое меню' : 'Toggle navigation sidebar'}
            >
              <PanelLeft size={14} />
              <span>{locale === 'ru' ? 'Панель' : 'Menu'}</span>
            </button>

            {run.data?.projectId && (
              <Link
                to={`/projects/${run.data.projectId}`}
                className="overlay-btn ghost"
                title={locale === 'ru' ? 'К обзору проекта' : 'Back to project'}
              >
                <ArrowLeft size={14} />
                <span>{locale === 'ru' ? 'Проект' : 'Project'}</span>
              </Link>
            )}

            <div className="overlay-nav-divider" />

            {/* View Selector Dropdown */}
            <div style={{ position: 'relative' }}>
              <button
                type="button"
                className="overlay-btn pill"
                onClick={(e) => {
                  e.stopPropagation();
                  setIsViewMenuOpen(!isViewMenuOpen);
                  setIsSettingsOpen(false);
                }}
                title={locale === 'ru' ? 'Переключить проекцию графа' : 'Switch graph projection'}
              >
                <currentView.icon size={14} style={{ color: 'var(--primary)' }} />
                <span>{label(currentView.value)}</span>
                <ChevronDown size={12} />
              </button>

              {isViewMenuOpen && (
                <div
                  className="overlay-glass-card overlay-dropdown-menu"
                  onClick={(e) => e.stopPropagation()}
                >
                  {viewItems.map((v) => (
                    <button
                      type="button"
                      key={v.value}
                      className={`overlay-dropdown-item ${state.view === v.value ? 'active' : ''}`}
                      onClick={() => {
                        state.setView(v.value);
                        setIsViewMenuOpen(false);
                      }}
                    >
                      <v.icon size={14} />
                      <span>{label(v.value)}</span>
                    </button>
                  ))}
                </div>
              )}
            </div>

            {/* Graph Settings Popover (Depth & Detail) */}
            <div style={{ position: 'relative' }}>
              <button
                type="button"
                className={`overlay-btn ${isSettingsOpen ? 'active' : 'pill'}`}
                onClick={(e) => {
                  e.stopPropagation();
                  setIsSettingsOpen(!isSettingsOpen);
                  setIsViewMenuOpen(false);
                }}
                title={locale === 'ru' ? 'Настройки связей и уровня детализации' : 'Graph links and detail settings'}
              >
                <Settings size={13} />
                <span>
                  {locale === 'ru' ? 'Связи' : 'Links'}: <strong>{locale === 'ru' ? 'Гл.' : 'Depth'} {state.depth}</strong>
                </span>
                <ChevronDown size={11} />
              </button>

              {isSettingsOpen && (
                <div
                  className="overlay-glass-card overlay-settings-popover"
                  onClick={(e) => e.stopPropagation()}
                >
                  <div
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                      marginBottom: '10px',
                    }}
                  >
                    <strong style={{ fontSize: '12px' }}>
                      {locale === 'ru' ? 'Параметры проекции' : 'Projection Settings'}
                    </strong>
                    <button
                      type="button"
                      className="btn btn-ghost small"
                      onClick={() => setIsSettingsOpen(false)}
                    >
                      <X size={12} />
                    </button>
                  </div>

                  <div className="overlay-popover-field">
                    <span>{locale === 'ru' ? `Глубина связей (${state.depth}):` : `Link depth (${state.depth}):`}</span>
                    <input
                      type="range"
                      min={1}
                      max={5}
                      step={1}
                      value={state.depth}
                      onChange={(e) => state.setDepth(Number(e.target.value))}
                    />
                    <span style={{ fontSize: '10.5px', color: 'var(--text-muted, #64748b)', fontWeight: 400 }}>
                      {locale === 'ru' ? '1 = только прямые вызовы, 2-3 = цепочка до БД' : '1 = direct calls only, 2-3 = chain to database'}
                    </span>
                  </div>

                  <div className="overlay-popover-field">
                    <span>{locale === 'ru' ? 'Уровень детализации:' : 'Level of detail:'}</span>
                    <select
                      value={state.detail}
                      onChange={(e) => state.setDetail(Number(e.target.value))}
                    >
                      {detailLabels.map((lbl, idx) => (
                        <option key={lbl} value={idx}>
                          {lbl}
                        </option>
                      ))}
                    </select>
                  </div>
                </div>
              )}
            </div>
          </div>

          {/* Center Section: Spotlight Search Input & Active Endpoint Focus Pill */}
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flex: 1, maxWidth: '680px', minWidth: '220px' }}>
            {state.rootNodeId && (
              <>
                <div
                  className="overlay-btn pill active"
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: '6px',
                    maxWidth: '220px',
                    padding: '4px 8px',
                    background: 'rgba(14, 116, 102, 0.15)',
                    borderColor: 'var(--primary)',
                    flexShrink: 0,
                    cursor: 'pointer',
                  }}
                  onClick={() => window.dispatchEvent(new CustomEvent('center-root-node'))}
                  title={locale === 'ru' ? `Активен фокус на эндпоинте: ${rootNodeLabel ?? state.rootNodeId}. Нажмите чтобы центрировать на начальной точке, или ✕ чтобы вернуться к общему графу.` : `Active focus on endpoint: ${rootNodeLabel ?? state.rootNodeId}. Click to center on start point, or ✕ to return to full graph.`}
                >
                  <Target size={13} style={{ color: 'var(--primary)', flexShrink: 0 }} />
                  <span
                    style={{
                      fontSize: '11px',
                      fontWeight: 600,
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {rootNodeLabel ?? (locale === 'ru' ? 'Эндпоинт' : 'Endpoint')}
                  </span>
                  <button
                    type="button"
                    onClick={(e) => {
                      e.stopPropagation();
                      state.setRoot(undefined);
                      state.select(null);
                    }}
                    style={{
                      border: 'none',
                      background: 'transparent',
                      cursor: 'pointer',
                      padding: '0 2px',
                      display: 'flex',
                      alignItems: 'center',
                      color: 'var(--text-muted)',
                    }}
                    title={locale === 'ru' ? 'Сбросить фокус на весь граф' : 'Reset focus to whole graph'}
                  >
                    <X size={11} />
                  </button>
                </div>

                <button
                  type="button"
                  className={`overlay-btn pill ${isEnrichingPipeline ? 'disabled' : ''}`}
                  style={{
                    background: 'linear-gradient(135deg, rgba(14, 116, 102, 0.2), rgba(99, 102, 241, 0.25))',
                    borderColor: 'rgba(99, 102, 241, 0.5)',
                    color: '#a5b4fc',
                    fontWeight: 600,
                    fontSize: '11px',
                    display: 'flex',
                    alignItems: 'center',
                    gap: '5px',
                    padding: '4px 10px',
                    flexShrink: 0,
                    cursor: isEnrichingPipeline ? 'not-allowed' : 'pointer',
                  }}
                  onClick={handleEnrichPipeline}
                  disabled={isEnrichingPipeline}
                  title={locale === 'ru' ? 'Сформировать детальное бизнес-описание сценария и шагов логики через ИИ' : 'Generate business description of scenario and steps via AI'}
                >
                  <Sparkles size={13} className={isEnrichingPipeline ? 'spinning' : ''} style={{ color: '#818cf8' }} />
                  <span>
                    {isEnrichingPipeline
                      ? (locale === 'ru' ? 'ИИ думает...' : 'AI thinking...')
                      : (locale === 'ru' ? '✨ Бизнес-описание (ИИ)' : '✨ Business Story (AI)')}
                  </span>
                </button>
              </>
            )}

            <div className="overlay-center-search" style={{ flex: 1 }}>
              <Search size={14} className="search-icon" />
              <input
                value={state.search}
                onChange={(e) => state.setSearch(e.target.value)}
                placeholder={locale === 'ru' ? 'Поиск узлов по коду, URL или методу...' : 'Search nodes by code, URL or method...'}
              />
              {state.search && (
                <button
                  type="button"
                  className="clear-icon"
                  onClick={() => state.setSearch('')}
                >
                  ✕
                </button>
              )}
            </div>
          </div>

          {/* Right Section: AI Status, Toggle Docks, Refresh */}
          <div className="overlay-nav-section">
            {/* AI Status Badge */}
            {progressQuery.data && (
              <div
                className="overlay-btn pill"
                style={{ cursor: 'pointer', padding: '4px 8px' }}
                onClick={() => setShowModelManager(true)}
                title={locale === 'ru' ? `Активная модель: ${progressQuery.data.activeModel || 'Локальная'}. Нажмите для выбора модели` : `Active model: ${progressQuery.data.activeModel || 'Local'}. Click to configure`}
              >
                <Sparkles size={13} style={{ color: 'var(--primary)' }} />
                <span>
                  {locale === 'ru' ? 'ИИ' : 'AI'}: <strong>{progressQuery.data.ready}</strong>/{progressQuery.data.total}
                </span>
                {progressQuery.data.processing > 0 && (
                  <button
                    type="button"
                    onClick={(e) => {
                      e.stopPropagation();
                      void handleCancelEnrichment();
                    }}
                    disabled={isCancelling}
                    className="overlay-btn-stop"
                    title={locale === 'ru' ? 'Остановить анализ ИИ' : 'Stop AI analysis'}
                  >
                    <Square size={8} fill="currentColor" />
                    <span>{isCancelling ? '...' : (locale === 'ru' ? 'Стоп' : 'Stop')}</span>
                  </button>
                )}
              </div>
            )}

            {/* Toggle Left Dock */}
            <button
              type="button"
              className={`overlay-btn ${isLeftDockOpen && !isLeftDockMinimized ? 'active' : 'pill'}`}
              onClick={() => {
                if (isLeftDockOpen && isLeftDockMinimized) {
                  setIsLeftDockMinimized(false);
                } else {
                  setIsLeftDockOpen(!isLeftDockOpen);
                  setIsLeftDockMinimized(false);
                }
              }}
              title={locale === 'ru' ? 'Показать/скрыть панель эндпоинтов' : 'Toggle endpoints catalog'}
            >
              <BookOpenText size={14} />
              <span>{locale === 'ru' ? 'Эндпоинты' : 'Endpoints'} ({totalEndpointsCount})</span>
            </button>

            {/* Toggle Overview */}
            <button
              type="button"
              className={`overlay-btn ${showOverview ? 'active' : 'pill'}`}
              onClick={() => setShowOverview(!showOverview)}
              title={locale === 'ru' ? 'Обзор обнаруженных компонентов' : 'Components overview'}
            >
              <LayoutDashboard size={14} />
              <span>{locale === 'ru' ? 'Обзор' : 'Overview'}</span>
            </button>

            {/* Toggle Filters */}
            <button
              type="button"
              className={`overlay-btn ${showFilters ? 'active' : 'pill'}`}
              onClick={() => setShowFilters(!showFilters)}
              title={locale === 'ru' ? 'Расширенные фильтры графа' : 'Graph filters'}
            >
              <SlidersHorizontal size={14} />
              <span>{locale === 'ru' ? 'Фильтры' : 'Filters'}</span>
              {filterCount > 0 && <Badge>{filterCount}</Badge>}
            </button>

            {/* Refresh */}
            <IconButton
              icon={RefreshCw}
              label={t('refresh')}
              disabled={projection.isFetching}
              onClick={() => void projection.refetch()}
            />
          </div>
        </header>

        {/* 2.2. LEFT EXPLORER DOCK (Structured CAD-style panel) */}
        {isLeftDockOpen && !isLeftDockMinimized && (
          <div className="overlay-glass-card overlay-left-dock">
            {/* Dock Header */}
            <div className="overlay-left-dock-header">
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <BookOpenText size={15} style={{ color: 'var(--primary, #0e7466)' }} />
                <strong style={{ fontSize: '12.5px' }}>
                  {locale === 'ru' ? 'Эндпоинты' : 'Endpoints'} ({totalEndpointsCount})
                </strong>
              </div>

              {/* Segmented Switcher: Groups vs Table */}
              <div className="overlay-segmented-tabs">
                <button
                  type="button"
                  className={`overlay-segmented-tab ${leftDockTab === 'controllers' ? 'active' : ''}`}
                  onClick={() => setLeftDockTab('controllers')}
                  title={locale === 'ru' ? 'Группировка по контроллерам' : 'Grouped by controllers'}
                >
                  {locale === 'ru' ? '📁 Группы' : '📁 Groups'}
                </button>
                <button
                  type="button"
                  className={`overlay-segmented-tab ${leftDockTab === 'table' ? 'active' : ''}`}
                  onClick={() => setLeftDockTab('table')}
                  title={locale === 'ru' ? 'Сводная таблица всех эндпоинтов' : 'All endpoints table'}
                >
                  <Table size={12} /> {locale === 'ru' ? 'Реестр' : 'Registry'}
                </button>
              </div>

              <div style={{ display: 'flex', alignItems: 'center', gap: '2px' }}>
                <button
                  type="button"
                  className="btn btn-ghost small"
                  onClick={() => setIsLeftDockMinimized(true)}
                  title={locale === 'ru' ? 'Свернуть в кнопку' : 'Minimize to button'}
                >
                  <ChevronRight size={14} style={{ transform: 'rotate(180deg)' }} />
                </button>
                <button
                  type="button"
                  className="btn btn-ghost small"
                  onClick={() => setIsLeftDockOpen(false)}
                  title={locale === 'ru' ? 'Закрыть панель' : 'Close panel'}
                >
                  <X size={14} />
                </button>
              </div>
            </div>

            {/* Dock Filter Bar (Only for controllers mode) */}
            {leftDockTab === 'controllers' && (
              <div className="overlay-left-dock-filterbar">
                <input
                  className="overlay-filter-input"
                  value={leftFilterSearch}
                  onChange={(e) => setLeftFilterSearch(e.target.value)}
                  placeholder={locale === 'ru' ? 'Фильтр по названию или URL...' : 'Filter by title or URL...'}
                />
                <div className="overlay-method-pills">
                  {['ALL', 'GET', 'POST', 'PUT', 'DELETE'].map((m) => (
                    <button
                      type="button"
                      key={m}
                      className={`overlay-method-pill ${m.toLowerCase()} ${leftMethodFilter === m ? 'active' : ''}`}
                      onClick={() => setLeftMethodFilter(m)}
                    >
                      {m === 'ALL' ? (locale === 'ru' ? 'ВСЕ' : 'ALL') : m}
                    </button>
                  ))}
                </div>
              </div>
            )}

            {/* Dock Body: Controllers Accordion or Table Catalog */}
            <div className="overlay-left-dock-body">
              {leftDockTab === 'table' ? (
                <AllEndpointsCatalog
                  controllers={controllersQuery.data ?? []}
                  selectedEndpointId={
                    state.selected?.kind === 'nodes' ? state.selected.id : undefined
                  }
                  onSelectEndpoint={(ep) => handleSelectEndpointFromDock(ep)}
                />
              ) : controllersQuery.isPending ? (
                <Loading />
              ) : filteredControllers.length === 0 ? (
                <div style={{ padding: '24px', textAlign: 'center', color: 'var(--text-muted, #94a3b8)', fontSize: '12px' }}>
                  Ничего не найдено по заданным фильтрам.
                </div>
              ) : (
                filteredControllers.map((ctrl) => {
                  const isExpanded = expandedControllers[ctrl.controllerKey] ?? false;

                  return (
                    <div className="overlay-controller-group" key={ctrl.controllerKey}>
                      <button
                        type="button"
                        className="overlay-controller-header"
                        onClick={() => toggleControllerExpand(ctrl.controllerKey)}
                      >
                        <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                          <ChevronRight
                            size={13}
                            style={{
                              transform: isExpanded ? 'rotate(90deg)' : 'none',
                              transition: 'transform 0.15s ease',
                            }}
                          />
                          <span>{ctrl.controllerName}</span>
                        </div>
                        <Badge tone="neutral">{ctrl.endpoints.length}</Badge>
                      </button>

                      {isExpanded && (
                        <div>
                          {ctrl.endpoints.map((ep) => {
                            const isSelected =
                              state.selected?.kind === 'nodes' &&
                              state.selected.id === ep.id;
                            const fallback = humanizeEndpoint(ep);
                            const title =
                              ep.aiResult?.title?.trim() ||
                              ep.aiTitle?.trim() ||
                              fallback.title;
                            const isEnriched = Boolean(
                              ep.hasAi || (ep.aiTitle && ep.aiTitle.trim().length > 0)
                            );
                            const method = (ep.method || 'GET').toUpperCase();

                            return (
                              <button
                                type="button"
                                key={ep.id}
                                className={`overlay-endpoint-card ${isSelected ? 'selected' : ''}`}
                                onClick={() => handleSelectEndpointFromDock(ep)}
                              >
                                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '6px' }}>
                                  <span className={`http-badge ${method.toLowerCase()}`}>
                                    {method}
                                  </span>
                                  {isEnriched ? (
                                    <span className="overlay-status-pill-ready">
                                      <Sparkles size={10} /> Готово
                                    </span>
                                  ) : (
                                    <span className="overlay-status-pill-basic">
                                      Базовый
                                    </span>
                                  )}
                                </div>

                                <div className="overlay-endpoint-title" title={title}>
                                  {title}
                                </div>

                                <div className="overlay-endpoint-path" title={ep.path ?? ''}>
                                  {ep.path}
                                </div>

                                <div className="overlay-endpoint-footer">
                                  <span>{ep.stepCount || 1} шагов</span>
                                  <span>•</span>
                                  <span>{ep.sideEffectCount || 0} эффектов</span>
                                  {!isEnriched && (
                                    <button
                                      type="button"
                                      onClick={(e) => void handleEnrichSingleNode(ep.id, e)}
                                      disabled={enrichingNodeId === ep.id}
                                      className="overlay-btn-enrich-single"
                                      title="Запустить анализ ИИ для этого эндпоинта"
                                    >
                                      <Sparkles size={9} className={enrichingNodeId === ep.id ? 'spin' : ''} />
                                      <span>{enrichingNodeId === ep.id ? '...' : 'ИИ'}</span>
                                    </button>
                                  )}
                                </div>
                              </button>
                            );
                          })}
                        </div>
                      )}
                    </div>
                  );
                })
              )}
            </div>
          </div>
        )}

        {/* 2.3. MINIMIZED LEFT PILL (When left dock is collapsed) */}
        {isLeftDockOpen && isLeftDockMinimized && (
          <button
            type="button"
            className="overlay-glass-card overlay-dock-pill"
            onClick={() => setIsLeftDockMinimized(false)}
            title="Развернуть панель эндпоинтов"
          >
            <BookOpenText size={14} style={{ color: 'var(--primary, #0e7466)' }} />
            <span>Эндпоинты ({totalEndpointsCount})</span>
            <ChevronRight size={13} />
          </button>
        )}

        {/* 2.4. RIGHT INSPECTOR DOCK (Evidence Drawer & Decision Tree) */}
        {state.selected && (
          <div className="overlay-glass-card overlay-right-dock">
            <header className="overlay-right-dock-header">
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <Sparkles size={15} style={{ color: 'var(--primary, #0e7466)' }} />
                <strong style={{ fontSize: '12.5px' }}>
                  Инспектор и Дерево решений
                </strong>
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                <button
                  type="button"
                  className="btn btn-secondary small"
                  onClick={() => {
                    if (state.selected) {
                      if (state.rootNodeId === state.selected.id) {
                        window.dispatchEvent(new CustomEvent('center-root-node'));
                      } else {
                        state.setRoot(state.selected.id);
                        state.setDepth(Math.min(5, state.depth + 1));
                      }
                    }
                  }}
                  style={{ display: 'inline-flex', alignItems: 'center', gap: '4px', fontSize: '11px', padding: '3px 8px' }}
                  title="Сфокусировать холст на узле и центрировать"
                >
                  <Target size={12} />
                  <span>Центр</span>
                </button>
                <button
                  type="button"
                  className="btn btn-ghost small"
                  onClick={() => state.select(null)}
                  title="Закрыть инспектор"
                >
                  <X size={14} />
                </button>
              </div>
            </header>
            <div className="overlay-right-dock-body">
              <EvidenceDrawer
                key={`${state.selected.kind}:${state.selected.id}`}
                selection={state.selected}
                onClose={() => state.select(null)}
                scope={`${analysisRunId}:${state.view}`}
                onExplore={(id) => {
                  state.setRoot(id);
                  state.setDepth(Math.min(5, state.depth + 1));
                  state.select(null);
                }}
              />
            </div>
          </div>
        )}

        {/* 2.5. FLOATING EXTENDED FILTERS POPOVER */}
        {showFilters && (
          <div className="overlay-glass-card overlay-filter-popover" onClick={(e) => e.stopPropagation()}>
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                marginBottom: '12px',
              }}
            >
              <strong style={{ fontSize: '13px' }}>Фильтры графа</strong>
              <div style={{ display: 'flex', gap: '8px' }}>
                <Button
                  variant="ghost"
                  onClick={() => {
                    state.setFilters({});
                    state.setConfidence(0);
                    state.setSearch('');
                  }}
                >
                  {t('clearFilters')}
                </Button>
                <IconButton
                  icon={X}
                  label="Закрыть"
                  onClick={() => setShowFilters(false)}
                />
              </div>
            </div>

            <div className="filter-range" style={{ marginBottom: '14px' }}>
              <Field
                label={`${t('minConfidence')}: ${Math.round(state.minConfidence * 100)}%`}
              >
                <input
                  type="range"
                  min={0}
                  max={1}
                  step={0.05}
                  value={state.minConfidence}
                  onChange={(e) => state.setConfidence(Number(e.target.value))}
                />
              </Field>
            </div>

            <div className="filter-checks" style={{ marginBottom: '14px' }}>
              {filterFlags.map((flag) => (
                <label className="checkbox-label" key={flag}>
                  <input
                    type="checkbox"
                    checked={Boolean(state.filters[flag])}
                    onChange={(e) =>
                      state.setFilters({
                        ...state.filters,
                        [flag]: e.target.checked,
                      })
                    }
                  />
                  {t(flag)}
                </label>
              ))}
            </div>

            <div className="filter-selects">
              {(
                ['sourceLayer', 'analyzer', 'component', 'framework'] as const
              ).map((key) => {
                const values = [
                  ...new Set(
                    projection.data?.nodes
                      .map((node) =>
                        key === 'sourceLayer'
                          ? sourceLayer(node)
                          : property(node, key),
                      )
                      .filter(Boolean) ?? [],
                  ),
                ].sort();
                return (
                  <Field key={key} label={t(key)}>
                    <select
                      value={state.filters[key] ?? ''}
                      onChange={(e) =>
                        state.setFilters({
                          ...state.filters,
                          [key]: e.target.value,
                        })
                      }
                    >
                      <option value="">{t('all')}</option>
                      {values.map((val) => (
                        <option key={val} value={val}>
                          {label(val)}
                        </option>
                      ))}
                    </select>
                  </Field>
                );
              })}
            </div>
          </div>
        )}

        {/* 2.6. FLOATING OVERVIEW MODAL */}
        {showOverview && visible && (
          <div className="overlay-glass-card overlay-overview-modal" onClick={(e) => e.stopPropagation()}>
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                marginBottom: '16px',
              }}
            >
              <strong style={{ fontSize: '15px' }}>
                Что обнаружено в кодовой базе
              </strong>
              <IconButton
                icon={X}
                label="Закрыть"
                onClick={() => setShowOverview(false)}
              />
            </div>
            <AnalysisOverview
              projection={visible}
              onSelect={(node) => {
                state.select({ kind: 'nodes', id: node.id, label: node.label });
                setShowOverview(false);
              }}
              onShowControllers={() => {
                setShowOverview(false);
                setIsLeftDockOpen(true);
                setIsLeftDockMinimized(false);
                setLeftDockTab('controllers');
              }}
              onShowGraph={() => setShowOverview(false)}
            />
          </div>
        )}

        {/* 2.7. FOCUS ROOT INDICATOR */}
        {state.rootNodeId && (
          <div
            className="overlay-glass-card"
            style={{
              position: 'absolute',
              bottom: '16px',
              left: isLeftDockOpen && !isLeftDockMinimized ? '446px' : '16px',
              display: 'flex',
              alignItems: 'center',
              gap: '10px',
              padding: '5px 12px',
              zIndex: 20,
              fontSize: '11.5px',
              transition: 'left 0.2s ease',
            }}
          >
            <span>
              Фокус: <code>{state.rootNodeId}</code>
            </span>
            <button
              type="button"
              className="btn btn-ghost small"
              onClick={() => state.setRoot(undefined)}
              style={{ padding: '1px 5px', fontSize: '11px' }}
            >
              <X size={11} />
              <span>Сбросить</span>
            </button>
          </div>
        )}

        {/* 2.8. BOTTOM-RIGHT CANVAS STATS HUD */}
        <div className="overlay-glass-card overlay-bottom-hud">
          <span>
            <strong>{graphProjection?.nodes.length ?? 0}</strong> узлов •{' '}
            <strong>{visible?.edges.length ?? 0}</strong> связей
          </span>
          {isolatedHidden > 0 && (
            <span className="muted">({isolatedHidden} изолир.)</span>
          )}
          <button
            type="button"
            className="btn btn-ghost small"
            onClick={() => state.toggleAiLabels()}
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: '4px',
              padding: '2px 6px',
              color: state.useAiLabels ? 'var(--primary, #0e7466)' : 'inherit',
              fontWeight: state.useAiLabels ? 700 : 400,
            }}
            title="Переключить показ бизнес-названий ИИ на узлах"
          >
            <Sparkles size={12} />
            <span>AI: {state.useAiLabels ? 'ВКЛ' : 'ВЫКЛ'}</span>
          </button>
        </div>
      </div>

      {/* Model Manager Modal */}
      {showModelManager && (
        <ModelManagerModal
          onClose={() => {
            setShowModelManager(false);
            void progressQuery.refetch();
          }}
        />
      )}
    </div>
  );
}
