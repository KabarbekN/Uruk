import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ReactFlowProvider } from '@xyflow/react';
import { useShallow } from 'zustand/react/shallow';
import { useParams, useSearchParams } from 'react-router-dom';
import {
  Braces,
  Database,
  Gauge,
  GitFork,
  List,
  Network,
  RefreshCw,
  ShieldCheck,
  SlidersHorizontal,
  Workflow,
  X,
} from 'lucide-react';
import { api } from '../shared/api/client';
import type { CanvasView } from '../shared/api/types';
import { useRun } from '../shared/api/queries';
import { useDebounced } from '../shared/hooks/useDebounced';
import { useT, type TranslationKey } from '../shared/lib/i18n';
import {
  Badge,
  Button,
  EmptyState,
  ErrorState,
  Field,
  IconButton,
  Loading,
  SearchInput,
} from '../shared/ui';
import { AnalysisProgress } from '../features/analysis-progress/AnalysisProgress';
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
import { NodeList } from '../features/semantic-canvas/NodeList';
import { EvidenceDrawer } from '../features/evidence-drawer/EvidenceDrawer';

const views = [
  { value: 'BUSINESS', label: 'business', icon: Workflow },
  { value: 'DEVELOPER', label: 'developer', icon: Braces },
  { value: 'DATA_OWNERSHIP', label: 'dataOwnership', icon: Database },
  { value: 'SECURITY', label: 'security', icon: ShieldCheck },
  { value: 'DATA_PIPELINE', label: 'pipeline', icon: GitFork },
  { value: 'CONFIDENCE', label: 'coverage', icon: Gauge },
] satisfies {
  value: CanvasView;
  label: TranslationKey;
  icon: typeof Workflow;
}[];
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

export default function CanvasPage() {
  const { analysisRunId = '' } = useParams();
  const [params] = useSearchParams();
  const { t, label } = useT();
  const run = useRun(analysisRunId);
  const state = useCanvasStore(
    useShallow((state) => ({
      view: state.view,
      depth: state.depth,
      detail: state.detail,
      minConfidence: state.minConfidence,
      search: state.search,
      rootNodeId: state.rootNodeId,
      filters: state.filters,
      selected: state.selected,
      select: state.select,
      setView: state.setView,
      setDepth: state.setDepth,
      setDetail: state.setDetail,
      setConfidence: state.setConfidence,
      setSearch: state.setSearch,
      setRoot: state.setRoot,
      setFilters: state.setFilters,
    })),
  );
  const { select, setRoot, setSearch } = state;
  const [showFilters, setShowFilters] = useState(false);
  const [list, setList] = useState(false);
  const search = useDebounced(state.search);
  useEffect(() => {
    select(null);
    setRoot(undefined);
    setSearch(params.get('search') ?? '');
  }, [analysisRunId, params, select, setRoot, setSearch]);
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
  const savedLayout = useQuery({
    queryKey: ['layout', analysisRunId, state.view],
    queryFn: ({ signal }) => api.layout(analysisRunId, state.view, signal),
    enabled: Boolean(run.data),
    staleTime: Infinity,
  });
  const visible = useMemo(() => {
    if (!projection.data) return null;
    const nodes = projection.data.nodes.filter((node) =>
      matchesFilters(node, state.filters),
    );
    const ids = new Set(nodes.map((node) => node.id));
    return {
      ...projection.data,
      nodes,
      edges: projection.data.edges.filter(
        (edge) => ids.has(edge.source) && ids.has(edge.target),
      ),
    };
  }, [projection.data, state.filters]);
  const filterCount = Object.values(state.filters).filter(Boolean).length;
  if (run.isPending) return <Loading />;
  if (run.isError)
    return <ErrorState error={run.error} retry={() => void run.refetch()} />;
  return (
    <div className="canvas-page" data-testid="canvas">
      <AnalysisProgress run={run.data} />
      <div className="view-tabs" role="tablist" aria-label={t('canvas')}>
        {views.map((view) => (
          <button
            type="button"
            role="tab"
            aria-selected={state.view === view.value}
            key={view.value}
            onClick={() => state.setView(view.value)}
          >
            <view.icon size={15} />
            <span>{t(view.label)}</span>
          </button>
        ))}
      </div>
      <div className="map-toolbar">
        <SearchInput
          label={t('searchMap')}
          value={state.search}
          onChange={state.setSearch}
        />
        <label className="inline-field">
          <span>{t('depth')}</span>
          <input
            type="number"
            min={1}
            max={5}
            value={state.depth}
            onChange={(event) => {
              const value = Number(event.target.value);
              if (Number.isInteger(value) && value >= 1 && value <= 5)
                state.setDepth(value);
            }}
          />
        </label>
        <label className="inline-field detail-field">
          <span>{t('detailLevel')}</span>
          <select
            aria-label={t('detailLevel')}
            value={state.detail}
            onChange={(event) => state.setDetail(Number(event.target.value))}
          >
            {(
              ['contexts', 'scenarios', 'rulesEffects', 'codeSql'] as const
            ).map((key, index) => (
              <option value={index} key={key}>
                {t(key)}
              </option>
            ))}
          </select>
        </label>
        <Button
          aria-expanded={showFilters}
          aria-pressed={filterCount > 0}
          onClick={() => setShowFilters(!showFilters)}
        >
          <SlidersHorizontal size={15} />
          {t('filters')}
          {filterCount > 0 && <Badge>{filterCount}</Badge>}
        </Button>
        <div className="toolbar-spacer" />
        <div className="segmented">
          <IconButton
            icon={Network}
            label={t('graphView')}
            aria-pressed={!list}
            onClick={() => setList(false)}
          />
          <IconButton
            icon={List}
            label={t('listView')}
            aria-pressed={list}
            onClick={() => setList(true)}
          />
        </div>
        <IconButton
          icon={RefreshCw}
          label={t('refresh')}
          disabled={projection.isFetching}
          onClick={() => void projection.refetch()}
        />
      </div>
      {showFilters && (
        <div className="filter-panel">
          <div className="filter-range">
            <Field
              label={`${t('minConfidence')}: ${Math.round(state.minConfidence * 100)}%`}
            >
              <input
                type="range"
                min={0}
                max={1}
                step={0.05}
                value={state.minConfidence}
                onChange={(event) =>
                  state.setConfidence(Number(event.target.value))
                }
              />
            </Field>
            <span className="muted small">{t('projectionFilters')}</span>
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
          </div>
          <div className="filter-checks">
            {filterFlags.map((flag) => (
              <label className="checkbox-label" key={flag}>
                <input
                  type="checkbox"
                  checked={Boolean(state.filters[flag])}
                  onChange={(event) =>
                    state.setFilters({
                      ...state.filters,
                      [flag]: event.target.checked,
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
                    onChange={(event) =>
                      state.setFilters({
                        ...state.filters,
                        [key]: event.target.value,
                      })
                    }
                  >
                    <option value="">{t('all')}</option>
                    {values.map((value) => (
                      <option key={value} value={value}>
                        {label(value)}
                      </option>
                    ))}
                  </select>
                </Field>
              );
            })}
          </div>
        </div>
      )}
      {state.rootNodeId && (
        <div className="root-indicator">
          <span>
            {t('focusRoot')}: <code>{state.rootNodeId}</code>
          </span>
          <Button variant="ghost" onClick={() => state.setRoot(undefined)}>
            <X size={14} />
            {t('resetRoot')}
          </Button>
        </div>
      )}
      <div className="canvas-body">
        <div className="canvas-main">
          {projection.isPending ? (
            <Loading />
          ) : projection.isError ? (
            <ErrorState
              error={projection.error}
              retry={() => void projection.refetch()}
            />
          ) : visible && !visible.nodes.length ? (
            <EmptyState
              icon={Network}
              title={t('emptyMap')}
              detail={t('emptyMapDetail')}
            />
          ) : list && visible ? (
            <NodeList
              nodes={visible.nodes}
              selectedId={state.selected?.id}
              onSelect={(node) =>
                state.select({ kind: 'nodes', id: node.id, label: node.label })
              }
            />
          ) : savedLayout.isPending ? (
            <Loading />
          ) : savedLayout.isError ? (
            <ErrorState
              error={savedLayout.error}
              retry={() => void savedLayout.refetch()}
            />
          ) : (
            visible && (
              <ReactFlowProvider key={`${analysisRunId}:${state.view}`}>
                <GraphSurface
                  runId={analysisRunId}
                  view={state.view}
                  projection={visible}
                  savedLayout={savedLayout.data ?? null}
                />
              </ReactFlowProvider>
            )
          )}
          <footer className="map-status">
            <span>
              <strong>{visible?.nodes.length ?? '\u2014'}</strong> {t('nodes')}
              <span className="status-divider">/</span>
              <strong>{visible?.edges.length ?? '\u2014'}</strong>{' '}
              {t('relationships')}
            </span>
            {projection.data?.truncated && (
              <span className="warning-text">{t('truncated')}</span>
            )}
            <span className="muted">
              {t('total')}: {projection.data?.totalNodes ?? '\u2014'}
            </span>
          </footer>
        </div>
        {state.selected && (
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
        )}
      </div>
    </div>
  );
}
