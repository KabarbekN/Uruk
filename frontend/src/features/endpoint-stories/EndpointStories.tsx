import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  AlertTriangle,
  ArrowRight,
  Braces,
  DatabaseZap,
  Layers,
  LoaderCircle,
  Search,
  Sparkles,
  Square,
  Table,
  Workflow,
} from 'lucide-react';
import { api } from '../../shared/api/client';
import type {
  ControllerEndpoint,
  ControllerGroup,
} from '../../shared/api/types';
import { Badge, Button, EmptyState, ErrorState, Loading } from '../../shared/ui';
import { notify } from '../../shared/ui/notifications';
import { humanizeEndpoint } from '../semantic-canvas/endpoint-humanizer';
import { AllEndpointsCatalog } from './AllEndpointsCatalog';
import { ModelManagerModal } from '../project-management/ModelManagerModal';
import './endpoint-stories.css';

interface EndpointStoriesProps {
  runId: string;
  active: boolean;
  selectedEndpointId?: string | null;
  onSelectEndpoint: (
    endpoint: ControllerEndpoint,
    controller: ControllerGroup,
  ) => void;
}

const statusText = {
  DISCOVERED: 'Обнаружен',
  STATIC_READY: 'Техническая история готова',
  ANALYZING: 'ИИ анализирует',
  READY: 'История готова',
} as const;

function StoryStatus({ endpoint }: { endpoint: ControllerEndpoint }) {
  const status = endpoint.storyStatus ?? 'DISCOVERED';
  if (status === 'READY')
    return (
      <Badge tone="positive">
        <Sparkles size={12} /> {statusText[status]}
      </Badge>
    );
  if (status === 'ANALYZING')
    return (
      <Badge tone="warning">
        <LoaderCircle size={12} className="spin" /> {statusText[status]}
      </Badge>
    );
  return (
    <Badge>
      <Workflow size={12} /> {statusText[status]}
    </Badge>
  );
}

export function EndpointStories({
  runId,
  active,
  selectedEndpointId,
  onSelectEndpoint,
}: EndpointStoriesProps) {
  const [search, setSearch] = useState('');
  const [debouncedQuery, setDebouncedQuery] = useState('');
  const [semanticMode, setSemanticMode] = useState(true);
  const [isEnrichingAll, setIsEnrichingAll] = useState(false);
  const [enrichingControllerKey, setEnrichingControllerKey] = useState<string | null>(null);
  const [enrichingNodeId, setEnrichingNodeId] = useState<string | null>(null);
  const [catalogTab, setCatalogTab] = useState<'controllers' | 'table'>('controllers');
  const [showModelManager, setShowModelManager] = useState(false);
  const [isCancelling, setIsCancelling] = useState(false);

  const handleCancelEnrichment = async () => {
    setIsCancelling(true);
    try {
      await api.cancelEnrichment(runId);
      notify('Анализ ИИ успешно остановлен!');
      void progressQuery.refetch();
      void controllers.refetch();
    } catch {
      notify('Не удалось остановить анализ');
    } finally {
      setIsCancelling(false);
      setIsEnrichingAll(false);
    }
  };

  // Debounce search query for semantic AI matching
  useEffect(() => {
    const handler = setTimeout(() => {
      setDebouncedQuery(search.trim());
    }, 250);
    return () => clearTimeout(handler);
  }, [search]);

  // Enrichment Progress query
  const progressQuery = useQuery({
    queryKey: ['enrichmentProgress', runId],
    queryFn: ({ signal }) => api.getEnrichmentProgress(runId, signal),
    refetchInterval: (query) => {
      const data = query.state.data;
      if (isEnrichingAll || (data && data.processing > 0)) {
        return 1500;
      }
      return active ? 3000 : 10000;
    },
  });

  // Turn off isEnrichingAll when backend finishes processing
  useEffect(() => {
    if (isEnrichingAll && progressQuery.data && progressQuery.data.processing === 0) {
      setIsEnrichingAll(false);
      notify('Обогащение бизнес-историй ИИ завершено!');
    }
  }, [isEnrichingAll, progressQuery.data]);

  // Semantic Search query
  const semanticQuery = useQuery({
    queryKey: ['semanticSearch', runId, debouncedQuery],
    queryFn: ({ signal }) => api.semanticSearch(runId, debouncedQuery, signal),
    enabled: Boolean(semanticMode && debouncedQuery.length >= 2),
    staleTime: 30000,
  });

  // Controllers query
  const controllers = useQuery({
    queryKey: ['controllers', runId],
    queryFn: ({ signal }) => api.getControllers(runId, signal),
    refetchInterval:
      active || isEnrichingAll || (progressQuery.data && progressQuery.data.processing > 0)
        ? 2000
        : false,
  });

  // Map of semantic search matches: endpointId -> { score, reason }
  const semanticMatchesMap = useMemo(() => {
    if (!semanticMode || !debouncedQuery || !semanticQuery.data) {
      return new Map<string, { score: number; reason: string }>();
    }
    const map = new Map<string, { score: number; reason: string }>();
    for (const item of semanticQuery.data) {
      map.set(item.endpointId, { score: item.score, reason: item.reason });
    }
    return map;
  }, [semanticMode, debouncedQuery, semanticQuery.data]);

  // Filter and sort groups
  const groups = useMemo(() => {
    const rawControllers = controllers.data ?? [];
    if (!debouncedQuery) return rawControllers;

    if (semanticMode && semanticMatchesMap.size > 0) {
      return rawControllers.flatMap((controller) => {
        const matchingEndpoints = controller.endpoints
          .filter((endpoint) => semanticMatchesMap.has(endpoint.id))
          .sort((a, b) => {
            const scoreA = semanticMatchesMap.get(a.id)?.score ?? 0;
            const scoreB = semanticMatchesMap.get(b.id)?.score ?? 0;
            return scoreB - scoreA;
          });
        return matchingEndpoints.length
          ? [{ ...controller, endpoints: matchingEndpoints }]
          : [];
      });
    }

    const query = debouncedQuery.toLocaleLowerCase();
    return rawControllers.flatMap((controller) => {
      const endpoints = controller.endpoints.filter((endpoint) => {
        const title = endpoint.aiResult?.title || endpoint.aiTitle || '';
        const desc = endpoint.aiResult?.businessPurpose || endpoint.aiDescription || '';
        return [
          endpoint.method,
          endpoint.path,
          endpoint.label,
          title,
          desc,
          controller.controllerName,
          controller.packageName,
        ]
          .filter(Boolean)
          .some((value) => String(value).toLocaleLowerCase().includes(query));
      });
      return endpoints.length ? [{ ...controller, endpoints }] : [];
    });
  }, [controllers.data, debouncedQuery, semanticMode, semanticMatchesMap]);

  const totals = useMemo(() => {
    const endpoints = (controllers.data ?? []).flatMap(
      (controller) => controller.endpoints,
    );
    return {
      all: endpoints.length,
      ready:
        progressQuery.data?.ready ??
        endpoints.filter((endpoint) => endpoint.storyStatus === 'READY').length,
      processing:
        progressQuery.data?.processing ??
        endpoints.filter((endpoint) => endpoint.storyStatus === 'ANALYZING').length,
    };
  }, [controllers.data, progressQuery.data]);

  const handleEnrichAll = async () => {
    setIsEnrichingAll(true);
    try {
      await api.enrichAll(runId);
      notify('Запущен ИИ-анализ для всех эндпоинтов проекта');
      void progressQuery.refetch();
      void controllers.refetch();
    } catch (err: unknown) {
      const msg =
        err instanceof Error ? err.message : 'Не удалось запустить обогащение';
      notify(`Ошибка: ${msg}`);
      setIsEnrichingAll(false);
    }
  };

  const handleEnrichController = async (controllerKey: string) => {
    setEnrichingControllerKey(controllerKey);
    try {
      await api.enrichController(runId, controllerKey);
      notify('Запущен анализ эндпоинтов контроллера');
      void controllers.refetch();
      void progressQuery.refetch();
    } catch (err: unknown) {
      const msg =
        err instanceof Error ? err.message : 'Ошибка при анализе контроллера';
      notify(`Ошибка: ${msg}`);
    } finally {
      setEnrichingControllerKey(null);
    }
  };

  const handleEnrichNode = async (nodeId: string) => {
    setEnrichingNodeId(nodeId);
    try {
      await api.enrichNode(nodeId);
      notify('Бизнес-история успешно сгенерирована!');
      void controllers.refetch();
      void progressQuery.refetch();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Ошибка генерации';
      notify(`Ошибка: ${msg}`);
    } finally {
      setEnrichingNodeId(null);
    }
  };

  if (controllers.isPending) return <Loading />;
  if (controllers.isError)
    return (
      <ErrorState
        error={controllers.error}
        retry={() => void controllers.refetch()}
      />
    );

  const isEnrichingActive =
    isEnrichingAll || (progressQuery.data && progressQuery.data.processing > 0);

  return (
    <section className="endpoint-stories" aria-label="Endpoint Stories">
      <header className="endpoint-stories-header">
        <div>
          <span className="eyebrow">Живая карта поведения</span>
          <h2>Endpoint Stories</h2>
          <p>
            Каждый endpoint превращается в читаемый бизнес-сценарий для
            аналитиков и продакт-менеджеров.
          </p>
        </div>
        <div className="endpoint-stories-header-actions">
          <dl className="endpoint-stories-stats">
            <div>
              <dt>Найдено</dt>
              <dd>{totals.all}</dd>
            </div>
            <div>
              <dt>ИИ готов</dt>
              <dd>{totals.ready}</dd>
            </div>
            <div>
              <dt>В работе</dt>
              <dd>{totals.processing}</dd>
            </div>
          </dl>
          <Button
            variant="primary"
            onClick={handleEnrichAll}
            disabled={Boolean(isEnrichingActive)}
            style={{ gap: '0.45rem', fontSize: '0.85rem' }}
          >
            {isEnrichingActive ? (
              <LoaderCircle size={15} className="spin" />
            ) : (
              <Sparkles size={15} />
            )}
            {isEnrichingActive
              ? 'ИИ формирует истории...'
              : '⚡ Обогатить все эндпоинты ИИ'}
          </Button>
        </div>
      </header>

      {/* Progress container */}
      {progressQuery.data && (
        <div className="endpoint-stories-progress-container">
          <div className="endpoint-stories-progress-info">
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
              <Sparkles
                size={16}
                style={{ color: 'var(--primary, #0e7466)' }}
              />
              <strong>
                Статус бизнес-описаний:{' '}
                <span style={{ color: 'var(--primary, #0e7466)' }}>
                  {progressQuery.data.ready} из {progressQuery.data.total}
                </span>{' '}
                готово ({progressQuery.data.percentage}%)
              </strong>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
              {isEnrichingActive && (
                <button
                  type="button"
                  onClick={handleCancelEnrichment}
                  disabled={isCancelling}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: '4px',
                    padding: '3px 8px',
                    fontSize: '11px',
                    fontWeight: 600,
                    borderRadius: '5px',
                    border: '1px solid #ffc9c9',
                    background: '#fff5f5',
                    color: '#e03131',
                    cursor: 'pointer',
                  }}
                  title="Немедленно остановить анализ ИИ"
                >
                  <Square size={11} fill="#e03131" />
                  <span>{isCancelling ? 'Остановка...' : 'Остановить анализ'}</span>
                </button>
              )}
              {progressQuery.data.activeModel && (
                <button
                  type="button"
                  onClick={() => setShowModelManager(true)}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: '5px',
                    padding: '3px 8px',
                    fontSize: '11px',
                    fontWeight: 600,
                    borderRadius: '5px',
                    border: '1px solid var(--border-subtle, #cbd5e1)',
                    background: 'var(--surface, #ffffff)',
                    color: 'var(--text, #1e293b)',
                    cursor: 'pointer',
                  }}
                  title="Выбрать другую LLM модель"
                >
                  <span>🤖 Модель: {progressQuery.data.activeModel}</span>
                  <span style={{ fontSize: '10.5px', color: 'var(--primary, #0e7466)', textDecoration: 'underline' }}>
                    Сменить
                  </span>
                </button>
              )}
            </div>
          </div>
          <div className="endpoint-stories-progress-track">
            <div
              className="endpoint-stories-progress-fill"
              style={{
                width: `${Math.max(4, progressQuery.data.percentage)}%`,
              }}
            />
          </div>
        </div>
      )}

      {/* Catalog View Mode Switcher */}
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          flexWrap: 'wrap',
          gap: '0.5rem',
          margin: '0.5rem 0',
        }}
      >
        <div
          style={{
            display: 'inline-flex',
            background: 'var(--surface-subtle, #f1f5f9)',
            borderRadius: '8px',
            padding: '3px',
            border: '1px solid var(--border-subtle, #e2e8f0)',
          }}
        >
          <button
            type="button"
            onClick={() => setCatalogTab('controllers')}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '6px',
              padding: '5px 12px',
              fontSize: '12px',
              fontWeight: 600,
              borderRadius: '6px',
              border: 'none',
              background:
                catalogTab === 'controllers'
                  ? 'var(--primary, #0e7466)'
                  : 'transparent',
              color:
                catalogTab === 'controllers'
                  ? '#fff'
                  : 'var(--text-muted, #64748b)',
              cursor: 'pointer',
            }}
          >
            <Layers size={14} />
            <span>По контроллерам</span>
          </button>
          <button
            type="button"
            onClick={() => setCatalogTab('table')}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '6px',
              padding: '5px 12px',
              fontSize: '12px',
              fontWeight: 600,
              borderRadius: '6px',
              border: 'none',
              background:
                catalogTab === 'table'
                  ? 'var(--primary, #0e7466)'
                  : 'transparent',
              color:
                catalogTab === 'table' ? '#fff' : 'var(--text-muted, #64748b)',
              cursor: 'pointer',
            }}
          >
            <Table size={14} />
            <span>📋 Сводный реестр эндпоинтов ({totals.all})</span>
          </button>
        </div>
      </div>

      {catalogTab === 'table' ? (
        <AllEndpointsCatalog
          controllers={controllers.data}
          selectedEndpointId={selectedEndpointId ?? undefined}
          onSelectEndpoint={onSelectEndpoint}
          onEnrichEndpoint={(id) => handleEnrichNode(id)}
        />
      ) : (
        <>
          {/* Compact Search Bar with Semantic Search toggle */}
          <div className="endpoint-stories-search-row">
            <label className="endpoint-stories-search">
              <Search size={16} />
              <input
                value={search}
                onChange={(event) => setSearch(event.target.value)}
                placeholder="Поиск по смыслу или URL (например «где видеопоток», «удаление»)..."
              />
              {search && (
                <button
                  type="button"
                  onClick={() => setSearch('')}
                  style={{
                    background: 'transparent',
                    border: 'none',
                    color: 'var(--muted)',
                    cursor: 'pointer',
                    fontSize: '14px',
                    padding: '0 4px',
                  }}
                >
                  ✕
                </button>
              )}
            </label>
            <button
              type="button"
              onClick={() => setSemanticMode(!semanticMode)}
              className={`semantic-toggle-btn ${semanticMode ? 'active' : ''}`}
              title="Семантический поиск по смыслу на естественном языке с помощью ИИ"
            >
              <Sparkles size={14} />
              <span>ИИ-поиск</span>
              <span className="semantic-toggle-indicator">
                {semanticMode ? 'ВКЛ' : 'ВЫКЛ'}
              </span>
            </button>
          </div>

          {semanticMode && debouncedQuery.length >= 2 && (
            <div className="semantic-search-feedback">
              {semanticQuery.isFetching ? (
                <span style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                  <LoaderCircle size={14} className="spin" /> ИИ анализирует смысл
                  запроса «{debouncedQuery}»...
                </span>
              ) : semanticMatchesMap.size > 0 ? (
                <span style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                  <Sparkles
                    size={14}
                    style={{ color: 'var(--primary, #0e7466)' }}
                  />
                  Найдено {semanticMatchesMap.size} эндпоинт(ов), релевантных смыслу
                  запроса
                </span>
              ) : (
                <span>По смыслу ничего не найдено, проверяем точный текст</span>
              )}
            </div>
          )}

      {!groups.length ? (
        <EmptyState
          icon={Workflow}
          title={
            active
              ? 'Endpoint’ы ещё обнаруживаются'
              : 'Endpoint Stories не найдены'
          }
          detail={
            active
              ? 'Оставьте страницу открытой — найденные сценарии появятся автоматически.'
              : 'Проверьте запрос или фильтры.'
          }
        />
      ) : (
        <div className="endpoint-story-groups">
          {groups.map((controller) => (
            <section
              className="endpoint-story-group"
              key={controller.controllerKey}
            >
              <header>
                <div>
                  <span className="eyebrow">Контроллер</span>
                  <h3>{controller.controllerName}</h3>
                </div>
                <div
                  style={{ display: 'flex', alignItems: 'center', gap: '8px' }}
                >
                  <Badge>{controller.endpoints.length} API</Badge>
                  <Button
                    variant="ghost"
                    onClick={() =>
                      handleEnrichController(controller.controllerKey)
                    }
                    disabled={
                      enrichingControllerKey === controller.controllerKey
                    }
                    style={{
                      fontSize: '12px',
                      padding: '4px 8px',
                      gap: '4px',
                    }}
                  >
                    <Sparkles
                      size={12}
                      className={
                        enrichingControllerKey === controller.controllerKey
                          ? 'spin'
                          : ''
                      }
                    />
                    {enrichingControllerKey === controller.controllerKey
                      ? 'Анализ...'
                      : 'Обогатить контроллер'}
                  </Button>
                </div>
              </header>
              <div className="endpoint-story-grid">
                {controller.endpoints.map((endpoint) => {
                  const fallback = humanizeEndpoint(endpoint);
                  const title =
                    endpoint.aiResult?.title?.trim() ||
                    endpoint.aiTitle?.trim() ||
                    fallback.title;
                  const description =
                    endpoint.aiResult?.businessPurpose?.trim() ||
                    endpoint.aiDescription?.trim() ||
                    fallback.description;
                  const selected = selectedEndpointId === endpoint.id;
                  const semanticMatch = semanticMatchesMap.get(endpoint.id);

                  return (
                    <button
                      type="button"
                      key={endpoint.id}
                      className={`endpoint-story-card ${selected ? 'selected' : ''}`}
                      onClick={() => onSelectEndpoint(endpoint, controller)}
                    >
                      <div className="endpoint-story-card-top">
                        <span
                          className={`http-method ${(endpoint.method || 'GET').toLowerCase()}`}
                        >
                          {endpoint.method || 'GET'}
                        </span>
                        <StoryStatus endpoint={endpoint} />
                      </div>
                      <code>{endpoint.path}</code>

                      {semanticMatch && (
                        <div className="endpoint-story-semantic-badge">
                          <Sparkles size={12} />
                          <strong>{semanticMatch.score}% совпадение:</strong>{' '}
                          <span>{semanticMatch.reason}</span>
                        </div>
                      )}

                      <h4>{title}</h4>
                      <p>{description}</p>

                      <div className="endpoint-story-metrics">
                        <span>
                          <Braces size={13} /> {endpoint.stepCount ?? 0} находок
                        </span>
                        <span>
                          <DatabaseZap size={13} />{' '}
                          {endpoint.sideEffectCount ?? 0} эффектов
                        </span>
                        {Boolean(endpoint.unresolvedCount) && (
                          <span className="endpoint-story-warning">
                            <AlertTriangle size={13} />{' '}
                            {endpoint.unresolvedCount} не разрешено
                          </span>
                        )}
                      </div>

                      {endpoint.storyStatus !== 'READY' && (
                        <div
                          style={{
                            display: 'flex',
                            justifyContent: 'flex-start',
                            marginTop: '10px',
                          }}
                        >
                          <button
                            type="button"
                            className="endpoint-story-enrich-btn"
                            onClick={(e) => {
                              e.stopPropagation();
                              handleEnrichNode(endpoint.id);
                            }}
                            disabled={enrichingNodeId === endpoint.id}
                          >
                            <Sparkles
                              size={12}
                              className={
                                enrichingNodeId === endpoint.id ? 'spin' : ''
                              }
                            />
                            {enrichingNodeId === endpoint.id
                              ? 'Анализ...'
                              : '✨ Сгенерировать ИИ'}
                          </button>
                        </div>
                      )}

                      {!endpoint.pathOrderKnown && endpoint.scenarioId && (
                        <small>
                          Найдены связи; точный порядок выполнения пока не
                          доказан
                        </small>
                      )}
                      <span className="endpoint-story-open">
                        Открыть бизнес-сценарий и код <ArrowRight size={14} />
                      </span>
                    </button>
                  );
                })}
              </div>
            </section>
          ))}
        </div>
      )}
    </>
  )}
  {showModelManager && (
    <ModelManagerModal
      onClose={() => {
        setShowModelManager(false);
        void progressQuery.refetch();
      }}
    />
  )}
</section>
);
}
