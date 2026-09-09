import { useState, useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Layers,
  ChevronDown,
  ChevronRight,
  Sparkles,
  ShieldCheck,
  ArrowRight,
  Braces,
  Workflow,
} from 'lucide-react';
import { api } from '../../shared/api/client';
import type {
  ControllerGroup,
  ControllerEndpoint,
  CanvasView,
} from '../../shared/api/types';
import {
  Badge,
  Button,
  Loading,
  ErrorState,
  SearchInput,
} from '../../shared/ui';
import {
  getControllerMetadata,
  type ControllerMeta,
} from './controller-metadata';
import { humanizeEndpoint } from './endpoint-humanizer';

interface DisplayController extends ControllerGroup {
  meta: ControllerMeta;
  matchingEndpoints?: ControllerEndpoint[];
}

interface ControllerExplorerProps {
  runId: string;
  view?: CanvasView;
  selectedEndpointId?: string | null;
  onSelectEndpoint: (
    endpoint: ControllerEndpoint,
    controller: ControllerGroup,
  ) => void;
  onOpenModelManager?: () => void;
}

export function ControllerExplorer({
  runId,
  view = 'BUSINESS',
  selectedEndpointId,
  onSelectEndpoint,
  onOpenModelManager,
}: ControllerExplorerProps) {
  const [search, setSearch] = useState('');
  const [expandedControllers, setExpandedControllers] = useState<
    Record<string, boolean>
  >({});
  const [activeTab, setActiveTab] = useState<'business' | 'developer'>(
    view === 'DEVELOPER' ? 'developer' : 'business',
  );

  const {
    data: controllers,
    isLoading,
    isError,
    error,
    refetch,
  } = useQuery({
    queryKey: ['controllers', runId],
    queryFn: ({ signal }) => api.getControllers(runId, signal),
  });

  const toggleExpand = (key: string) => {
    setExpandedControllers((prev) => ({
      ...prev,
      [key]: !prev[key],
    }));
  };

  const expandAll = () => {
    if (!controllers) return;
    const all: Record<string, boolean> = {};
    controllers.forEach((c) => {
      all[c.controllerKey] = true;
    });
    setExpandedControllers(all);
  };

  const collapseAll = () => {
    setExpandedControllers({});
  };

  const controllersWithMeta = useMemo<DisplayController[]>(() => {
    if (!controllers) return [];
    return controllers.map((c) => ({
      ...c,
      meta: getControllerMetadata(c.controllerName),
    }));
  }, [controllers]);

  const filteredControllers = useMemo<DisplayController[]>(() => {
    if (!controllersWithMeta.length) return [];
    if (!search.trim()) return controllersWithMeta;
    const query = search.toLowerCase().trim();

    return controllersWithMeta.flatMap((c) => {
      const matchesController =
        c.controllerName.toLowerCase().includes(query) ||
        c.meta.businessTitle.toLowerCase().includes(query) ||
        c.meta.description.toLowerCase().includes(query) ||
        c.meta.domain.toLowerCase().includes(query) ||
        c.packageName.toLowerCase().includes(query) ||
        c.filePath.toLowerCase().includes(query);

      const matchingEndpoints = c.endpoints.filter(
        (ep: ControllerEndpoint) =>
          ep.path?.toLowerCase().includes(query) ||
          ep.method?.toLowerCase().includes(query) ||
          ep.label?.toLowerCase().includes(query),
      );

      if (matchesController || matchingEndpoints.length > 0) {
        return [
          {
            ...c,
            matchingEndpoints:
              matchingEndpoints.length > 0 ? matchingEndpoints : c.endpoints,
          },
        ];
      }
      return [];
    });
  }, [controllersWithMeta, search]);

  const stats = useMemo(() => {
    if (!controllers)
      return { controllersCount: 0, endpointsCount: 0, aiEnrichedCount: 0 };
    let totalEndpoints = 0;
    let aiEnriched = 0;
    controllers.forEach((c) => {
      totalEndpoints += c.endpointCount;
      c.endpoints.forEach((ep) => {
        if (ep.hasAi) aiEnriched++;
      });
    });
    return {
      controllersCount: controllers.length,
      endpointsCount: totalEndpoints,
      aiEnrichedCount: aiEnriched,
    };
  }, [controllers]);

  if (isLoading) return <Loading />;
  if (isError) return <ErrorState error={error} retry={() => void refetch()} />;

  const isBusiness = activeTab === 'business';

  return (
    <div
      className="controller-explorer"
      style={{
        display: 'flex',
        flexDirection: 'column',
        height: '100%',
        overflowY: 'auto',
        padding: '1.25rem 1.75rem',
        boxSizing: 'border-box',
        gap: '1rem',
        background: 'var(--surface-subtle, #f5f7f6)',
      }}
    >
      {/* Top Header & Overview */}
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          flexWrap: 'wrap',
          gap: '1rem',
          padding: '1rem 1.25rem',
          background: 'var(--surface, #ffffff)',
          borderRadius: '10px',
          border: '1px solid var(--border, #e1e7e3)',
          boxShadow: '0 1px 3px rgba(0, 0, 0, 0.03)',
        }}
      >
        <div>
          <h2
            style={{
              margin: 0,
              fontSize: '1.35rem',
              fontWeight: 700,
              color: 'var(--text, #26322e)',
              display: 'flex',
              alignItems: 'center',
              gap: '0.6rem',
            }}
          >
            <Layers size={24} style={{ color: 'var(--primary, #0e7466)' }} />
            Каталог сервисов и API-контроллеров
          </h2>
          <div
            className="muted small"
            style={{
              display: 'flex',
              gap: '1rem',
              marginTop: '0.4rem',
              color: 'var(--muted, #69756f)',
              fontSize: '0.85rem',
            }}
          >
            <span>
              Контроллеров:{' '}
              <strong style={{ color: 'var(--text, #26322e)' }}>
                {stats.controllersCount}
              </strong>
            </span>
            <span>•</span>
            <span>
              Эндпоинтов:{' '}
              <strong style={{ color: 'var(--text, #26322e)' }}>
                {stats.endpointsCount}
              </strong>
            </span>
            <span>•</span>
            <span
              style={{
                color: stats.aiEnrichedCount > 0 ? '#10b981' : undefined,
                fontWeight: 600,
              }}
            >
              С LLM-анализом: {stats.aiEnrichedCount}
            </span>
          </div>
        </div>

        {/* View Switcher & Controls */}
        <div
          style={{
            display: 'flex',
            gap: '0.6rem',
            alignItems: 'center',
            flexWrap: 'wrap',
          }}
        >
          <div
            style={{
              display: 'flex',
              background: 'var(--surface-subtle, #f5f7f6)',
              padding: '0.2rem',
              borderRadius: '8px',
              border: '1px solid var(--border, #e1e7e3)',
            }}
          >
            <Button
              variant={isBusiness ? 'primary' : 'ghost'}
              onClick={() => setActiveTab('business')}
              style={{
                fontSize: '0.8rem',
                padding: '0.3rem 0.75rem',
                gap: '0.35rem',
                fontWeight: isBusiness ? 600 : 400,
              }}
            >
              <Workflow size={14} />
              Бизнес-вид
            </Button>
            <Button
              variant={!isBusiness ? 'primary' : 'ghost'}
              onClick={() => setActiveTab('developer')}
              style={{
                fontSize: '0.8rem',
                padding: '0.3rem 0.75rem',
                gap: '0.35rem',
                fontWeight: !isBusiness ? 600 : 400,
              }}
            >
              <Braces size={14} />
              Разработка
            </Button>
          </div>

          {onOpenModelManager && (
            <Button
              variant="ghost"
              onClick={onOpenModelManager}
              style={{
                gap: '0.4rem',
                fontSize: '0.82rem',
                border: '1px solid var(--border, #e1e7e3)',
              }}
            >
              <Sparkles size={14} style={{ color: '#8b5cf6' }} />
              Настройка ИИ
            </Button>
          )}
          <Button
            variant="ghost"
            onClick={expandAll}
            style={{ fontSize: '0.8rem' }}
          >
            Развернуть все
          </Button>
          <Button
            variant="ghost"
            onClick={collapseAll}
            style={{ fontSize: '0.8rem' }}
          >
            Свернуть все
          </Button>
        </div>
      </div>

      {/* Filter toolbar */}
      <div style={{ display: 'flex', gap: '1rem', alignItems: 'center' }}>
        <div style={{ flex: 1, maxWidth: '520px' }}>
          <SearchInput
            label={
              isBusiness
                ? 'Поиск по бизнес-названию, назначению или эндпоинтам...'
                : 'Поиск по классу, пакету, пути или эндпоинтам...'
            }
            value={search}
            onChange={setSearch}
          />
        </div>
        {search && (
          <span
            className="muted small"
            style={{ color: 'var(--muted, #69756f)' }}
          >
            Найдено:{' '}
            <strong style={{ color: 'var(--text, #26322e)' }}>
              {filteredControllers.length}
            </strong>{' '}
            контроллеров
          </span>
        )}
      </div>

      {/* Controllers list */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '0.85rem' }}>
        {filteredControllers.length === 0 ? (
          <div
            style={{
              textAlign: 'center',
              padding: '3rem',
              background: 'var(--surface, #ffffff)',
              borderRadius: '10px',
              border: '1px solid var(--border, #e1e7e3)',
              color: 'var(--muted, #69756f)',
            }}
          >
            Контроллеры по запросу «{search}» не найдены.
          </div>
        ) : (
          filteredControllers.map((controller) => {
            const isExpanded =
              expandedControllers[controller.controllerKey] ?? Boolean(search);
            const endpoints =
              controller.matchingEndpoints ?? controller.endpoints;

            const methodCounts: Record<string, number> = {};
            let enrichedInController = 0;
            controller.endpoints.forEach((ep: ControllerEndpoint) => {
              const m = ep.method?.toUpperCase() || 'OTHER';
              methodCounts[m] = (methodCounts[m] || 0) + 1;
              if (ep.hasAi) enrichedInController++;
            });

            const meta = controller.meta;

            return (
              <div
                key={controller.controllerKey}
                style={{
                  borderRadius: '10px',
                  background: 'var(--surface, #ffffff)',
                  border: isExpanded
                    ? '1px solid var(--primary, #0e7466)'
                    : '1px solid var(--border, #e1e7e3)',
                  boxShadow: isExpanded
                    ? '0 3px 12px rgba(14, 116, 102, 0.08)'
                    : '0 1px 3px rgba(0, 0, 0, 0.03)',
                  overflow: 'hidden',
                  transition: 'all 0.2s ease',
                }}
              >
                {/* Controller Card Header */}
                <div
                  onClick={() => toggleExpand(controller.controllerKey)}
                  style={{
                    display: 'flex',
                    alignItems: 'flex-start',
                    justifyContent: 'space-between',
                    padding: '1rem 1.25rem',
                    cursor: 'pointer',
                    userSelect: 'none',
                    background: isExpanded
                      ? 'var(--surface-hover, #f0f5f3)'
                      : 'var(--surface, #ffffff)',
                    transition: 'background 0.15s ease',
                    gap: '1rem',
                  }}
                >
                  <div
                    style={{
                      display: 'flex',
                      alignItems: 'flex-start',
                      gap: '0.85rem',
                      flex: 1,
                    }}
                  >
                    <div style={{ marginTop: '0.2rem' }}>
                      {isExpanded ? (
                        <ChevronDown
                          size={20}
                          style={{ color: 'var(--primary, #0e7466)' }}
                        />
                      ) : (
                        <ChevronRight
                          size={20}
                          style={{ color: 'var(--muted, #69756f)' }}
                        />
                      )}
                    </div>

                    <div
                      style={{
                        display: 'flex',
                        flexDirection: 'column',
                        gap: '0.35rem',
                        flex: 1,
                      }}
                    >
                      {/* Title row */}
                      <div
                        style={{
                          display: 'flex',
                          alignItems: 'center',
                          gap: '0.6rem',
                          flexWrap: 'wrap',
                        }}
                      >
                        {isBusiness ? (
                          <>
                            <strong
                              style={{
                                fontSize: '1.12rem',
                                color: 'var(--text, #26322e)',
                                fontWeight: 700,
                              }}
                            >
                              {meta.businessTitle}
                            </strong>
                            <Badge
                              tone="neutral"
                              style={{ fontSize: '0.72rem' }}
                            >
                              {meta.domain}
                            </Badge>
                            <Badge
                              tone={meta.securityTone}
                              style={{ fontSize: '0.72rem' }}
                            >
                              {meta.securityLabel}
                            </Badge>
                            <Badge
                              tone="warning"
                              style={{ fontSize: '0.72rem' }}
                            >
                              Справочная классификация
                            </Badge>
                          </>
                        ) : (
                          <>
                            <strong
                              className="mono"
                              style={{
                                fontSize: '1.08rem',
                                color: 'var(--text, #26322e)',
                                fontWeight: 700,
                              }}
                            >
                              {controller.controllerName}
                            </strong>
                            <Badge
                              tone="neutral"
                              style={{ fontSize: '0.72rem' }}
                            >
                              {meta.businessTitle}
                            </Badge>
                            <Badge
                              tone={meta.securityTone}
                              style={{ fontSize: '0.72rem' }}
                            >
                              {meta.securityLabel}
                            </Badge>
                          </>
                        )}
                      </div>

                      {/* Subtitle / Description */}
                      {isBusiness ? (
                        <div
                          style={{
                            display: 'flex',
                            flexDirection: 'column',
                            gap: '0.25rem',
                          }}
                        >
                          <p
                            style={{
                              margin: 0,
                              fontSize: '0.88rem',
                              color: 'var(--text, #26322e)',
                              opacity: 0.85,
                              lineHeight: 1.45,
                            }}
                          >
                            {meta.description}
                          </p>
                          <span
                            className="mono muted small"
                            style={{
                              fontSize: '0.78rem',
                              color: 'var(--muted, #69756f)',
                            }}
                          >
                            {controller.controllerName} •{' '}
                            {controller.packageName}
                          </span>
                        </div>
                      ) : (
                        <div
                          style={{
                            display: 'flex',
                            flexDirection: 'column',
                            gap: '0.2rem',
                          }}
                        >
                          <span
                            className="mono muted small"
                            style={{
                              fontSize: '0.8rem',
                              color: 'var(--muted, #69756f)',
                            }}
                          >
                            {controller.packageName}
                          </span>
                          {controller.filePath && (
                            <span
                              className="mono muted small"
                              style={{ fontSize: '0.75rem', opacity: 0.7 }}
                            >
                              {controller.filePath}
                            </span>
                          )}
                        </div>
                      )}
                    </div>
                  </div>

                  {/* Right badges & count */}
                  <div
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      gap: '0.5rem',
                      flexWrap: 'wrap',
                      justifyContent: 'flex-end',
                    }}
                  >
                    {Object.entries(methodCounts).map(([method, count]) => (
                      <span
                        key={method}
                        style={{
                          fontSize: '0.74rem',
                          padding: '0.15rem 0.45rem',
                          borderRadius: '4px',
                          fontWeight: 700,
                          background:
                            method === 'GET'
                              ? '#e0f2fe'
                              : method === 'POST'
                                ? '#dcfce7'
                                : method === 'DELETE'
                                  ? '#fee2e2'
                                  : '#fef3c7',
                          color:
                            method === 'GET'
                              ? '#0369a1'
                              : method === 'POST'
                                ? '#15803d'
                                : method === 'DELETE'
                                  ? '#b91c1c'
                                  : '#b45309',
                        }}
                      >
                        {method} {count}
                      </span>
                    ))}

                    {enrichedInController > 0 && (
                      <Badge
                        tone="positive"
                        style={{ gap: '0.25rem', fontSize: '0.72rem' }}
                      >
                        <Sparkles size={11} />
                        ИИ: {enrichedInController}/{controller.endpointCount}
                      </Badge>
                    )}

                    <Badge tone="neutral" style={{ fontWeight: 600 }}>
                      {controller.endpointCount} API
                    </Badge>
                  </div>
                </div>

                {/* Endpoints sub-list */}
                {isExpanded && (
                  <div
                    style={{
                      borderTop: '1px solid var(--border, #e1e7e3)',
                      background: 'var(--surface-subtle, #f5f7f6)',
                      padding: '0.75rem 1rem 1rem',
                      display: 'flex',
                      flexDirection: 'column',
                      gap: '0.45rem',
                    }}
                  >
                    <span
                      style={{
                        fontSize: '0.75rem',
                        fontWeight: 700,
                        color: 'var(--muted, #69756f)',
                        textTransform: 'uppercase',
                        letterSpacing: '0.04em',
                        marginBottom: '0.2rem',
                      }}
                    >
                      Список API-эндпоинтов ({endpoints.length}):
                    </span>

                    {endpoints.map((ep) => {
                      const isSelected = ep.id === selectedEndpointId;
                      const info = humanizeEndpoint(ep);
                      return (
                        <div
                          key={ep.id}
                          onClick={() => onSelectEndpoint(ep, controller)}
                          style={{
                            display: 'flex',
                            flexDirection: 'column',
                            gap: '0.45rem',
                            padding: '0.75rem 1rem',
                            borderRadius: '8px',
                            background: isSelected
                              ? 'var(--primary-soft, #e5f3ef)'
                              : 'var(--surface, #ffffff)',
                            border: isSelected
                              ? '1.5px solid var(--primary, #0e7466)'
                              : '1px solid var(--border, #e1e7e3)',
                            cursor: 'pointer',
                            transition: 'all 0.15s ease',
                            boxShadow: '0 1px 3px rgba(0, 0, 0, 0.02)',
                          }}
                          onMouseEnter={(e) => {
                            if (!isSelected) {
                              e.currentTarget.style.background =
                                'var(--surface-hover, #f0f5f3)';
                            }
                          }}
                          onMouseLeave={(e) => {
                            if (!isSelected) {
                              e.currentTarget.style.background =
                                'var(--surface, #ffffff)';
                            }
                          }}
                        >
                          {/* Top line: Human Title + Status + Action Button */}
                          <div
                            style={{
                              display: 'flex',
                              alignItems: 'center',
                              justifyContent: 'space-between',
                              gap: '0.75rem',
                              flexWrap: 'wrap',
                            }}
                          >
                            <div
                              style={{
                                display: 'flex',
                                alignItems: 'center',
                                gap: '0.6rem',
                                flex: 1,
                                minWidth: '240px',
                              }}
                            >
                              <strong
                                style={{
                                  fontSize: '0.95rem',
                                  color: 'var(--text, #1e2925)',
                                  fontWeight: 700,
                                }}
                              >
                                {info.title}
                              </strong>
                              {ep.hasAi ? (
                                <span
                                  style={{
                                    display: 'inline-flex',
                                    alignItems: 'center',
                                    gap: '3px',
                                    fontSize: '10.5px',
                                    fontWeight: 700,
                                    padding: '2px 7px',
                                    borderRadius: '4px',
                                    background: 'var(--warning-soft)',
                                    color: 'var(--warning)',
                                    border:
                                      '1px solid color-mix(in srgb, var(--warning) 45%, transparent)',
                                  }}
                                  title="Формулировка создана нейросетью и ожидает проверки человеком"
                                >
                                  <Sparkles size={11} />✨ ИИ-сценарий · нужна
                                  проверка
                                </span>
                              ) : (
                                <span
                                  style={{
                                    display: 'inline-flex',
                                    alignItems: 'center',
                                    gap: '3px',
                                    fontSize: '10.5px',
                                    fontWeight: 700,
                                    padding: '2px 7px',
                                    borderRadius: '4px',
                                    background: 'rgba(12, 166, 120, 0.1)',
                                    color: '#0ca678',
                                    border:
                                      '1px solid rgba(12, 166, 120, 0.25)',
                                  }}
                                  title="Статический AST-анализ кода; ИИ не использовался"
                                >
                                  <ShieldCheck size={11} />
                                  🛡️ 100% Код (AST)
                                </span>
                              )}
                            </div>

                            <div
                              style={{
                                display: 'flex',
                                alignItems: 'center',
                                gap: '0.6rem',
                              }}
                            >
                              <Button
                                variant={isSelected ? 'primary' : 'secondary'}
                                onClick={(e) => {
                                  e.stopPropagation();
                                  onSelectEndpoint(ep, controller);
                                }}
                                style={{
                                  padding: '0.35rem 0.8rem',
                                  fontSize: '0.8rem',
                                  fontWeight: 600,
                                  gap: '0.35rem',
                                }}
                              >
                                <span>Открыть логику</span>
                                <ArrowRight size={13} />
                              </Button>
                            </div>
                          </div>

                          {/* Bottom line: Route badge, path link, and method description */}
                          <div
                            style={{
                              display: 'flex',
                              alignItems: 'center',
                              gap: '0.75rem',
                              flexWrap: 'wrap',
                              fontSize: '0.82rem',
                            }}
                          >
                            <div
                              style={{
                                display: 'flex',
                                alignItems: 'center',
                                gap: '0.45rem',
                                background: 'var(--surface-subtle, #f5f7f6)',
                                padding: '2px 8px',
                                borderRadius: '5px',
                                border: '1px solid var(--border, #e1e7e3)',
                              }}
                            >
                              <span
                                className={`http-badge ${info.method.toLowerCase()}`}
                              >
                                {info.method}
                              </span>
                              <span
                                className="mono"
                                style={{
                                  fontSize: '0.85rem',
                                  color: 'var(--text, #26322e)',
                                  fontWeight: 600,
                                }}
                              >
                                {info.path}
                              </span>
                            </div>

                            {info.methodName && (
                              <span
                                className="muted"
                                style={{ fontSize: '0.78rem' }}
                              >
                                метод:{' '}
                                <code
                                  style={{
                                    color: 'var(--primary, #0e7466)',
                                    fontWeight: 600,
                                  }}
                                >
                                  {info.methodName}()
                                </code>
                              </span>
                            )}

                            {info.description &&
                              !info.description.includes('Реализовано') && (
                                <span
                                  className="muted"
                                  style={{
                                    fontSize: '0.78rem',
                                    color: 'var(--muted, #69756f)',
                                  }}
                                >
                                  • {info.description}
                                </span>
                              )}
                          </div>
                        </div>
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
  );
}
