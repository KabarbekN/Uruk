import { useState, useMemo } from 'react';
import {
  Layers,
  Search,
  Sparkles,
} from 'lucide-react';
import type { ControllerGroup, ControllerEndpoint } from '../../shared/api/types';
import { humanizeEndpoint } from '../semantic-canvas/endpoint-humanizer';

interface AllEndpointsCatalogProps {
  controllers: ControllerGroup[];
  selectedEndpointId?: string;
  onSelectEndpoint: (endpoint: ControllerEndpoint, controller: ControllerGroup) => void;
  onEnrichEndpoint?: (endpointId: string) => void;
}

export function AllEndpointsCatalog({
  controllers,
  selectedEndpointId,
  onSelectEndpoint,
}: AllEndpointsCatalogProps) {
  const [search, setSearch] = useState('');
  const [methodFilter, setMethodFilter] = useState<string>('ALL');
  const [statusFilter, setStatusFilter] = useState<'ALL' | 'ENRICHED' | 'UNENRICHED'>('ALL');

  // Flatten all endpoints with controller context
  const allItems = useMemo(() => {
    const list: {
      endpoint: ControllerEndpoint;
      controller: ControllerGroup;
      title: string;
      desc: string;
    }[] = [];

    controllers.forEach((ctrl) => {
      ctrl.endpoints.forEach((ep) => {
        const humanized = humanizeEndpoint(ep);
        const title = humanized.title;
        const desc = ep.aiResult?.businessPurpose?.trim() || humanized.description;

        list.push({
          endpoint: ep,
          controller: ctrl,
          title,
          desc,
        });
      });
    });

    return list;
  }, [controllers]);

  // Filter items
  const filteredItems = useMemo(() => {
    const q = search.trim().toLowerCase();
    return allItems.filter(({ endpoint, controller, title, desc }) => {
      const epMethod = (endpoint.method || 'GET').toUpperCase();
      if (methodFilter !== 'ALL' && epMethod !== methodFilter) return false;

      const isEnriched = Boolean(endpoint.hasAi || (endpoint.aiTitle && endpoint.aiTitle.trim().length > 0));
      if (statusFilter === 'ENRICHED' && !isEnriched) return false;
      if (statusFilter === 'UNENRICHED' && isEnriched) return false;

      if (!q) return true;
      return (
        title.toLowerCase().includes(q) ||
        desc.toLowerCase().includes(q) ||
        (endpoint.path && endpoint.path.toLowerCase().includes(q)) ||
        (endpoint.methodName && endpoint.methodName.toLowerCase().includes(q)) ||
        controller.controllerName.toLowerCase().includes(q)
      );
    });
  }, [allItems, search, methodFilter, statusFilter]);

  const methods = ['ALL', 'GET', 'POST', 'PUT', 'DELETE', 'PATCH'];

  return (
    <div className="all-endpoints-catalog" style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
      {/* Search & Filter Toolbar */}
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          flexWrap: 'wrap',
          gap: '0.75rem',
          padding: '0.85rem 1.25rem',
          background: 'var(--surface, #ffffff)',
          borderRadius: '10px',
          border: '1px solid var(--border-subtle, #e2e8f0)',
          boxShadow: '0 1px 3px rgba(0,0,0,0.03)',
        }}
      >
        {/* Search input */}
        <div style={{ position: 'relative', flex: '1 1 260px' }}>
          <Search
            size={16}
            style={{
              position: 'absolute',
              left: '10px',
              top: '50%',
              transform: 'translateY(-50%)',
              color: 'var(--text-muted, #94a3b8)',
            }}
          />
          <input
            type="text"
            placeholder="Поиск по пути, бизнес-названию или методу..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            style={{
              width: '100%',
              padding: '0.45rem 0.65rem 0.45rem 2.2rem',
              borderRadius: '6px',
              border: '1px solid var(--border-subtle, #cbd5e1)',
              fontSize: '13px',
              boxSizing: 'border-box',
            }}
          />
        </div>

        {/* HTTP Method Filters */}
        <div style={{ display: 'flex', gap: '0.25rem', flexWrap: 'wrap' }}>
          {methods.map((m) => (
            <button
              key={m}
              type="button"
              onClick={() => setMethodFilter(m)}
              style={{
                padding: '3px 8px',
                fontSize: '11px',
                fontWeight: 600,
                borderRadius: '5px',
                border: '1px solid',
                borderColor: methodFilter === m ? 'var(--primary, #0e7466)' : 'var(--border-subtle, #e2e8f0)',
                background: methodFilter === m ? 'var(--primary, #0e7466)' : 'transparent',
                color: methodFilter === m ? '#fff' : 'var(--text-muted, #64748b)',
                cursor: 'pointer',
              }}
            >
              {m === 'ALL' ? 'Все методы' : m}
            </button>
          ))}
        </div>

        {/* AI Status Filter */}
        <div style={{ display: 'flex', gap: '0.25rem' }}>
          <button
            type="button"
            onClick={() => setStatusFilter('ALL')}
            style={{
              padding: '3px 8px',
              fontSize: '11px',
              fontWeight: 600,
              borderRadius: '5px',
              border: '1px solid',
              borderColor: statusFilter === 'ALL' ? '#334155' : 'var(--border-subtle, #e2e8f0)',
              background: statusFilter === 'ALL' ? '#334155' : 'transparent',
              color: statusFilter === 'ALL' ? '#fff' : 'var(--text-muted, #64748b)',
              cursor: 'pointer',
            }}
          >
            Все ({allItems.length})
          </button>
          <button
            type="button"
            onClick={() => setStatusFilter('ENRICHED')}
            style={{
              padding: '3px 8px',
              fontSize: '11px',
              fontWeight: 600,
              borderRadius: '5px',
              border: '1px solid',
              borderColor: statusFilter === 'ENRICHED' ? '#0ca678' : 'var(--border-subtle, #e2e8f0)',
              background: statusFilter === 'ENRICHED' ? '#0ca678' : 'transparent',
              color: statusFilter === 'ENRICHED' ? '#fff' : 'var(--text-muted, #64748b)',
              cursor: 'pointer',
            }}
          >
            ✨ С ИИ
          </button>
        </div>
      </div>

      {/* Table / Catalog View */}
      <div
        style={{
          background: 'var(--surface, #ffffff)',
          borderRadius: '10px',
          border: '1px solid var(--border-subtle, #e2e8f0)',
          overflow: 'hidden',
          boxShadow: '0 1px 4px rgba(0,0,0,0.04)',
        }}
      >
        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left', fontSize: '13px' }}>
            <thead>
              <tr
                style={{
                  background: 'var(--surface-subtle, #f8fafc)',
                  borderBottom: '1px solid var(--border-subtle, #e2e8f0)',
                  color: 'var(--text-muted, #64748b)',
                  fontSize: '11px',
                  textTransform: 'uppercase',
                  letterSpacing: '0.05em',
                }}
              >
                <th style={{ padding: '0.75rem 1rem', width: '80px' }}>Метод</th>
                <th style={{ padding: '0.75rem 1rem' }}>URL / Путь API</th>
                <th style={{ padding: '0.75rem 1rem' }}>Бизнес-назначение (ИИ / Аналитика)</th>
                <th style={{ padding: '0.75rem 1rem' }}>Контроллер</th>
                <th style={{ padding: '0.75rem 1rem', width: '110px' }}>Статус ИИ</th>
                <th style={{ padding: '0.75rem 1rem', textAlign: 'center', width: '90px' }}>Шаги</th>
              </tr>
            </thead>
            <tbody>
              {filteredItems.length === 0 ? (
                <tr>
                  <td colSpan={6} style={{ padding: '2.5rem', textAlign: 'center', color: 'var(--text-muted, #94a3b8)' }}>
                    По заданным критериям эндпоинты не найдены.
                  </td>
                </tr>
              ) : (
                filteredItems.map(({ endpoint, controller, title, desc }) => {
                  const isSelected = endpoint.id === selectedEndpointId;
                  const isEnriched = Boolean(endpoint.hasAi || (endpoint.aiTitle && endpoint.aiTitle.trim().length > 0));
                  const method = (endpoint.method || 'GET').toUpperCase();

                  return (
                    <tr
                      key={endpoint.id}
                      onClick={() => onSelectEndpoint(endpoint, controller)}
                      style={{
                        borderBottom: '1px solid var(--border-subtle, #f1f5f9)',
                        cursor: 'pointer',
                        background: isSelected
                          ? 'var(--primary-soft, #e6fcf5)'
                          : 'transparent',
                        transition: 'background 0.15s ease',
                      }}
                      onMouseEnter={(e) => {
                        if (!isSelected) e.currentTarget.style.background = 'var(--surface-hover)';
                      }}
                      onMouseLeave={(e) => {
                        if (!isSelected) e.currentTarget.style.background = 'transparent';
                      }}
                    >
                      {/* Method */}
                      <td style={{ padding: '0.75rem 1rem' }}>
                        <span className={`http-badge ${method.toLowerCase()}`}>
                          {method}
                        </span>
                      </td>

                      {/* Path */}
                      <td style={{ padding: '0.75rem 1rem' }}>
                        <code style={{ fontSize: '12px', color: 'var(--text)', fontWeight: 600 }}>
                          {endpoint.path}
                        </code>
                        {endpoint.methodName && (
                          <div style={{ fontSize: '11px', color: 'var(--muted)', marginTop: '2px' }}>
                            {endpoint.methodName}()
                          </div>
                        )}
                      </td>

                      {/* Business Title & Purpose */}
                      <td style={{ padding: '0.75rem 1rem' }}>
                        <div style={{ fontWeight: 600, color: 'var(--text)' }}>{title}</div>
                        <div
                          style={{
                            fontSize: '11.5px',
                            color: 'var(--muted)',
                            marginTop: '2px',
                            maxWidth: '480px',
                            whiteSpace: 'nowrap',
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                          }}
                        >
                          {desc}
                        </div>
                      </td>

                      {/* Controller */}
                      <td style={{ padding: '0.75rem 1rem', fontSize: '12px', color: 'var(--muted)' }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
                          <Layers size={13} style={{ opacity: 0.6 }} />
                          <strong>{controller.controllerName}</strong>
                        </div>
                      </td>

                      {/* AI Status */}
                      <td style={{ padding: '0.75rem 1rem' }}>
                        {isEnriched ? (
                          <span
                            style={{
                              display: 'inline-flex',
                              alignItems: 'center',
                              gap: '4px',
                              fontSize: '11px',
                              fontWeight: 600,
                              color: 'var(--positive, #0ca678)',
                              background: 'var(--positive-soft, #e6fcf5)',
                              padding: '2px 7px',
                              borderRadius: '4px',
                            }}
                          >
                            <Sparkles size={12} />
                            Готово
                          </span>
                        ) : (
                          <span
                            style={{
                              fontSize: '11px',
                              color: 'var(--muted, #94a3b8)',
                              background: 'var(--surface-hover, #f1f5f9)',
                              padding: '2px 6px',
                              borderRadius: '4px',
                            }}
                          >
                            Базовый
                          </span>
                        )}
                      </td>

                      {/* Steps count */}
                      <td style={{ padding: '0.75rem 1rem', textAlign: 'center' }}>
                        <span
                          style={{
                            padding: '2px 7px',
                            borderRadius: '4px',
                            fontSize: '11px',
                            fontWeight: 600,
                            background: 'var(--surface-hover)',
                            color: 'var(--muted)',
                          }}
                        >
                          {endpoint.stepCount || 1}
                        </span>
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
