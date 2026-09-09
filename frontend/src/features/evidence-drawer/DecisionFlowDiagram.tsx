import { useState } from 'react';
import {
  CheckCircle2,
  Database,
  ExternalLink,
  GitBranch,
  ShieldAlert,
  ShieldCheck,
} from 'lucide-react';
import type { LlmEnrichment, ScenarioMember } from '../../shared/api/types';
import { humanizeScenarioStep } from './ast-humanizer';

interface DecisionFlowDiagramProps {
  endpointLabel: string;
  method?: string | null;
  path?: string | null;
  members: ScenarioMember[];
  enrichment?: LlmEnrichment | null;
  onExploreNode?: (id: string) => void;
}

export function DecisionFlowDiagram({
  endpointLabel,
  method,
  path,
  members,
  enrichment,
  onExploreNode,
}: DecisionFlowDiagramProps) {
  const [activeBranch, setActiveBranch] = useState<'all' | 'success' | 'errors'>('all');

  const httpMethod = (method || 'GET').toUpperCase();
  const urlPath = path || endpointLabel;

  // Categorize members into logical phases of execution
  const authMembers = members.filter(
    (m) =>
      m.kind === 'AUTHORIZATION_RULE' ||
      m.kind === 'SECURITY_RULE' ||
      m.label.includes('Principal') ||
      m.label.includes('Permission') ||
      m.label.includes('Role') ||
      m.label.includes('Security'),
  );

  const validationMembers = members.filter(
    (m) =>
      m.kind === 'VALIDATION_RULE' ||
      m.label.includes('Valid') ||
      m.label.includes('NotNull') ||
      m.label.includes('Size') ||
      m.label.includes('Pattern'),
  );

  const conditionMembers = members.filter(
    (m) =>
      m.kind === 'CONDITION' ||
      m.kind === 'BUSINESS_RULE' ||
      m.label.startsWith('if ') ||
      m.label.includes('==') ||
      m.label.includes('!=') ||
      m.label.includes('>') ||
      m.label.includes('<'),
  );

  const errorMembers = members.filter(
    (m) =>
      m.kind === 'EXCEPTION' ||
      m.label.startsWith('throw ') ||
      m.label.includes('Exception'),
  );

  const sideEffectMembers = members.filter(
    (m) =>
      m.kind === 'DATABASE_READ' ||
      m.kind === 'DATABASE_WRITE' ||
      m.kind === 'EXTERNAL_CALL' ||
      m.kind === 'EVENT_PUBLICATION' ||
      m.label.includes('Repository') ||
      m.label.includes('Client') ||
      m.label.includes('save') ||
      m.label.includes('find'),
  );

  const aiRules = enrichment?.result?.businessRules || [];
  const aiErrors = enrichment?.result?.errorScenarios || [];
  const aiActors = enrichment?.result?.actors || [];

  return (
    <div className="decision-flow-diagram" style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
      {/* Branch selector tabs */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          flexWrap: 'wrap',
          gap: '0.5rem',
          padding: '0.4rem 0.6rem',
          background: 'var(--surface-subtle, #f8fafc)',
          borderRadius: '8px',
          border: '1px solid var(--border-subtle, #e2e8f0)',
        }}
      >
        <div style={{ display: 'flex', gap: '0.25rem' }}>
          <button
            type="button"
            onClick={() => setActiveBranch('all')}
            style={{
              padding: '4px 10px',
              fontSize: '11px',
              fontWeight: 600,
              borderRadius: '6px',
              border: 'none',
              background: activeBranch === 'all' ? 'var(--primary, #0e7466)' : 'transparent',
              color: activeBranch === 'all' ? '#fff' : 'var(--text-muted, #64748b)',
              cursor: 'pointer',
            }}
          >
            🌳 Полное дерево ({members.length} узлов)
          </button>
          <button
            type="button"
            onClick={() => setActiveBranch('success')}
            style={{
              padding: '4px 10px',
              fontSize: '11px',
              fontWeight: 600,
              borderRadius: '6px',
              border: 'none',
              background: activeBranch === 'success' ? '#0ca678' : 'transparent',
              color: activeBranch === 'success' ? '#fff' : 'var(--text-muted, #64748b)',
              cursor: 'pointer',
            }}
          >
            🟢 Ветка успеха (Happy Path)
          </button>
          <button
            type="button"
            onClick={() => setActiveBranch('errors')}
            style={{
              padding: '4px 10px',
              fontSize: '11px',
              fontWeight: 600,
              borderRadius: '6px',
              border: 'none',
              background: activeBranch === 'errors' ? '#fa5252' : 'transparent',
              color: activeBranch === 'errors' ? '#fff' : 'var(--text-muted, #64748b)',
              cursor: 'pointer',
            }}
          >
            🔴 Сценарии ошибок / исключений ({errorMembers.length + aiErrors.length})
          </button>
        </div>
      </div>

      {/* Decision Tree Timeline */}
      <div
        style={{
          position: 'relative',
          paddingLeft: '1.25rem',
          display: 'flex',
          flexDirection: 'column',
          gap: '1.25rem',
        }}
      >
        {/* Continuous vertical tree line */}
        <div
          style={{
            position: 'absolute',
            left: '11px',
            top: '20px',
            bottom: '20px',
            width: '2px',
            background: 'var(--border-subtle, #cbd5e1)',
            zIndex: 0,
          }}
        />

        {/* STEP 1: API Entry Point */}
        <div style={{ position: 'relative', zIndex: 1 }}>
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '0.6rem',
              marginBottom: '0.4rem',
            }}
          >
            <div
              style={{
                width: '24px',
                height: '24px',
                borderRadius: '50%',
                background: 'var(--primary, #0e7466)',
                color: '#fff',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: '11px',
                fontWeight: 700,
                marginLeft: '-11px',
                boxShadow: '0 0 0 4px var(--surface, #fff)',
              }}
            >
              1
            </div>
            <span style={{ fontSize: '12px', fontWeight: 700, color: 'var(--text, #1e293b)' }}>
              Входная точка API (HTTP Запрос)
            </span>
          </div>

          <div
            style={{
              background: 'var(--surface, #ffffff)',
              border: '1px solid var(--border-subtle, #e2e8f0)',
              borderRadius: '8px',
              padding: '0.75rem 1rem',
              boxShadow: '0 1px 3px rgba(0,0,0,0.05)',
              display: 'flex',
              flexDirection: 'column',
              gap: '0.35rem',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', flexWrap: 'wrap' }}>
              <span className={`http-badge ${httpMethod.toLowerCase()}`}>
                {httpMethod}
              </span>
              <code style={{ fontSize: '12px', fontWeight: 600 }}>{urlPath}</code>
            </div>
            {aiActors.length > 0 && (
              <div style={{ fontSize: '11.5px', color: 'var(--text-muted, #64748b)' }}>
                👥 <strong>Инициаторы (акторы):</strong> {aiActors.join(', ')}
              </div>
            )}
          </div>
        </div>

        {/* STEP 2: Access & Authorization (Security Guard) */}
        <div style={{ position: 'relative', zIndex: 1 }}>
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '0.6rem',
              marginBottom: '0.4rem',
            }}
          >
            <div
              style={{
                width: '24px',
                height: '24px',
                borderRadius: '50%',
                background: '#0ca678',
                color: '#fff',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: '11px',
                fontWeight: 700,
                marginLeft: '-11px',
                boxShadow: '0 0 0 4px var(--surface, #fff)',
              }}
            >
              2
            </div>
            <span style={{ fontSize: '12px', fontWeight: 700, color: 'var(--text, #1e293b)' }}>
              Контроль доступа и Роли
            </span>
          </div>

          <div
            style={{
              background: 'var(--surface, #ffffff)',
              border: '1px solid var(--border)',
              borderRadius: '8px',
              padding: '0.75rem 1rem',
              boxShadow: '0 1px 3px rgba(0,0,0,0.05)',
              display: 'flex',
              flexDirection: 'column',
              gap: '0.4rem',
            }}
          >
            {authMembers.length > 0 ? (
              authMembers.map((m) => {
                const step = humanizeScenarioStep(m.kind, m.label, m.subtitle);
                return (
                  <div key={m.id} style={{ display: 'flex', alignItems: 'flex-start', gap: '0.5rem' }}>
                    <ShieldCheck size={16} style={{ color: '#0ca678', marginTop: '2px', flexShrink: 0 }} />
                    <div style={{ fontSize: '12px' }}>
                      <strong>{step.businessTitle}</strong>
                      <div style={{ fontSize: '11px', color: 'var(--muted, #64748b)' }}>{step.businessDetail}</div>
                    </div>
                  </div>
                );
              })
            ) : (
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '11.5px', color: 'var(--positive, #2b8a3e)' }}>
                <CheckCircle2 size={15} />
                <span>Проверка сессии пользователя и прав доступа</span>
              </div>
            )}

            {/* Split outcomes */}
            <div
              style={{
                display: 'flex',
                gap: '0.75rem',
                marginTop: '0.25rem',
                paddingTop: '0.4rem',
                borderTop: '1px dashed var(--border)',
                fontSize: '11px',
              }}
            >
              <span style={{ color: 'var(--positive, #0ca678)', display: 'flex', alignItems: 'center', gap: '3px' }}>
                🟢 Доступ разрешён ➔ Следующий шаг
              </span>
              {activeBranch !== 'success' && (
                <span style={{ color: 'var(--negative, #e03131)', display: 'flex', alignItems: 'center', gap: '3px' }}>
                  🔴 Нет прав ➔ HTTP 403 Forbidden
                </span>
              )}
            </div>
          </div>
        </div>

        {/* STEP 3: Validation & Parameter Checks */}
        {(validationMembers.length > 0 || aiRules.length > 0) && (
          <div style={{ position: 'relative', zIndex: 1 }}>
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: '0.6rem',
                marginBottom: '0.4rem',
              }}
            >
              <div
                style={{
                  width: '24px',
                  height: '24px',
                  borderRadius: '50%',
                  background: '#339af0',
                  color: '#fff',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  fontSize: '11px',
                  fontWeight: 700,
                  marginLeft: '-11px',
                  boxShadow: '0 0 0 4px var(--surface, #fff)',
                }}
              >
                3
              </div>
              <span style={{ fontSize: '12px', fontWeight: 700, color: 'var(--text, #1e293b)' }}>
                Валидация входных данных и Инварианты
              </span>
            </div>

            <div
              style={{
                background: 'var(--surface, #ffffff)',
                border: '1px solid var(--border)',
                borderRadius: '8px',
                padding: '0.75rem 1rem',
                boxShadow: '0 1px 3px rgba(0,0,0,0.05)',
                display: 'flex',
                flexDirection: 'column',
                gap: '0.4rem',
              }}
            >
              {validationMembers.map((m) => {
                const step = humanizeScenarioStep(m.kind, m.label, m.subtitle);
                return (
                  <div key={m.id} style={{ display: 'flex', alignItems: 'flex-start', gap: '0.5rem' }}>
                    <GitBranch size={15} style={{ color: '#1971c2', marginTop: '2px', flexShrink: 0 }} />
                    <div style={{ fontSize: '12px' }}>
                      <strong>{step.businessTitle}</strong>
                      <div style={{ fontSize: '11px', color: 'var(--muted, #64748b)' }}>{step.businessDetail}</div>
                    </div>
                  </div>
                );
              })}

              {aiRules.slice(0, 3).map((rule, idx) => (
                <div key={idx} style={{ display: 'flex', alignItems: 'flex-start', gap: '0.5rem', fontSize: '11.5px' }}>
                  <span style={{ color: '#1971c2' }}>⚖️</span>
                  <span>{rule}</span>
                </div>
              ))}

              <div
                style={{
                  display: 'flex',
                  gap: '0.75rem',
                  marginTop: '0.25rem',
                  paddingTop: '0.4rem',
                  borderTop: '1px dashed var(--border)',
                  fontSize: '11px',
                }}
              >
                <span style={{ color: 'var(--positive, #0ca678)' }}>🟢 Валидно ➔ Переход к исполнению</span>
                {activeBranch !== 'success' && (
                  <span style={{ color: 'var(--negative, #e03131)' }}>🔴 Невалидно ➔ HTTP 400 Bad Request</span>
                )}
              </div>
            </div>
          </div>
        )}

        {/* STEP 4: Decision Points (If-Else Branches) */}
        {(conditionMembers.length > 0 || (activeBranch === 'errors' && errorMembers.length > 0)) && (
          <div style={{ position: 'relative', zIndex: 1 }}>
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: '0.6rem',
                marginBottom: '0.4rem',
              }}
            >
              <div
                style={{
                  width: '24px',
                  height: '24px',
                  borderRadius: '50%',
                  background: '#f08c00',
                  color: '#fff',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  fontSize: '11px',
                  fontWeight: 700,
                  marginLeft: '-11px',
                  boxShadow: '0 0 0 4px var(--surface, #fff)',
                }}
              >
                4
              </div>
              <span style={{ fontSize: '12px', fontWeight: 700, color: 'var(--text, #1e293b)' }}>
                Развилки логики и условия (Decision Points)
              </span>
            </div>

            <div
              style={{
                background: 'var(--surface, #ffffff)',
                border: '1px solid var(--border)',
                borderRadius: '8px',
                padding: '0.75rem 1rem',
                boxShadow: '0 1px 3px rgba(0,0,0,0.05)',
                display: 'flex',
                flexDirection: 'column',
                gap: '0.5rem',
              }}
            >
              {conditionMembers.map((m) => {
                const step = humanizeScenarioStep(m.kind, m.label, m.subtitle);
                return (
                  <div
                    key={m.id}
                    style={{
                      background: 'var(--warning-soft, #fff9db)',
                      padding: '0.5rem 0.75rem',
                      borderRadius: '6px',
                      border: '1px solid var(--warning, #fcc419)',
                    }}
                  >
                    <div style={{ fontSize: '12px', fontWeight: 700, color: 'var(--warning, #e67700)' }}>
                      {step.businessTitle}
                    </div>
                    <div style={{ fontSize: '11px', color: 'var(--muted, #555)', marginTop: '2px' }}>
                      {step.businessDetail}
                    </div>
                  </div>
                );
              })}

              {/* Error scenarios */}
              {activeBranch !== 'success' && (errorMembers.length > 0 || aiErrors.length > 0) && (
                <div
                  style={{
                    marginTop: '0.35rem',
                    padding: '0.5rem 0.75rem',
                    background: 'var(--negative-soft, #fff5f5)',
                    borderRadius: '6px',
                    border: '1px solid var(--negative, #ffc9c9)',
                    display: 'flex',
                    flexDirection: 'column',
                    gap: '0.35rem',
                  }}
                >
                  <div style={{ fontSize: '11.5px', fontWeight: 700, color: 'var(--negative, #e03131)', display: 'flex', alignItems: 'center', gap: '4px' }}>
                    <ShieldAlert size={14} />
                    <span>Возможные ветки сбоев (Исключения):</span>
                  </div>
                  {errorMembers.map((em) => {
                    const exStep = humanizeScenarioStep(em.kind, em.label, em.subtitle);
                    return (
                      <div key={em.id} style={{ fontSize: '11px', color: 'var(--negative, #c92a2a)', paddingLeft: '0.5rem' }}>
                        • <strong>{exStep.businessTitle}</strong> — {exStep.businessDetail}
                      </div>
                    );
                  })}
                  {aiErrors.map((err, idx) => (
                    <div key={idx} style={{ fontSize: '11px', color: 'var(--negative, #c92a2a)', paddingLeft: '0.5rem' }}>
                      • {err}
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        )}

        {/* STEP 5: Business Operations & Storage */}
        {activeBranch !== 'errors' && sideEffectMembers.length > 0 && (
          <div style={{ position: 'relative', zIndex: 1 }}>
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: '0.6rem',
                marginBottom: '0.4rem',
              }}
            >
              <div
                style={{
                  width: '24px',
                  height: '24px',
                  borderRadius: '50%',
                  background: '#7950f2',
                  color: '#fff',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  fontSize: '11px',
                  fontWeight: 700,
                  marginLeft: '-11px',
                  boxShadow: '0 0 0 4px var(--surface, #fff)',
                }}
              >
                5
              </div>
              <span style={{ fontSize: '12px', fontWeight: 700, color: 'var(--text, #1e293b)' }}>
                Выполнение бизнес-действий (БД, Сеть, События)
              </span>
            </div>

            <div
              style={{
                background: 'var(--surface, #ffffff)',
                border: '1px solid var(--border)',
                borderRadius: '8px',
                padding: '0.75rem 1rem',
                boxShadow: '0 1px 3px rgba(0,0,0,0.05)',
                display: 'flex',
                flexDirection: 'column',
                gap: '0.5rem',
              }}
            >
              {sideEffectMembers.map((m) => {
                const step = humanizeScenarioStep(m.kind, m.label, m.subtitle);
                const isWrite = m.kind === 'DATABASE_WRITE' || m.label.includes('save');
                return (
                  <div
                    key={m.id}
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                      padding: '0.45rem 0.65rem',
                      background: isWrite ? 'var(--primary-soft, #edf2ff)' : 'var(--surface-hover, #f8f9fa)',
                      borderRadius: '6px',
                      border: '1px solid var(--border)',
                    }}
                  >
                    <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                      <Database size={15} style={{ color: isWrite ? 'var(--primary, #364fc7)' : 'var(--muted, #495057)' }} />
                      <div style={{ fontSize: '12px' }}>
                        <strong>{step.businessTitle}</strong>
                        <div style={{ fontSize: '11px', color: 'var(--muted, #64748b)' }}>
                          {step.businessDetail}
                        </div>
                      </div>
                    </div>
                    {onExploreNode && (
                      <button
                        type="button"
                        onClick={() => onExploreNode(m.id)}
                        style={{
                          background: 'none',
                          border: 'none',
                          color: 'var(--primary, #0e7466)',
                          cursor: 'pointer',
                          padding: '2px',
                        }}
                        title="Показать на графе"
                      >
                        <ExternalLink size={13} />
                      </button>
                    )}
                  </div>
                );
              })}
            </div>
          </div>
        )}

        {/* STEP 6: Result & HTTP Response */}
        {activeBranch !== 'errors' && (
          <div style={{ position: 'relative', zIndex: 1 }}>
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: '0.6rem',
                marginBottom: '0.4rem',
              }}
            >
              <div
                style={{
                  width: '24px',
                  height: '24px',
                  borderRadius: '50%',
                  background: '#2b8a3e',
                  color: '#fff',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  fontSize: '11px',
                  fontWeight: 700,
                  marginLeft: '-11px',
                  boxShadow: '0 0 0 4px var(--surface, #fff)',
                }}
              >
                ✓
              </div>
              <span style={{ fontSize: '12px', fontWeight: 700, color: 'var(--text, #1e293b)' }}>
                Успешный результат (HTTP 200 OK)
              </span>
            </div>

            <div
              style={{
                background: 'var(--positive-soft, #ebfbee)',
                border: '1px solid var(--positive, #b2f2bb)',
                borderRadius: '8px',
                padding: '0.75rem 1rem',
                display: 'flex',
                alignItems: 'center',
                gap: '0.6rem',
                fontSize: '12px',
                color: 'var(--positive, #2b8a3e)',
              }}
            >
              <CheckCircle2 size={18} />
              <div>
                <strong>Формирование ответа клиенту</strong>
                <div style={{ fontSize: '11px', color: 'var(--muted, #2f9e44)' }}>
                  Все проверки пройдены, транзакция зафиксирована, клиенту возвращен результат операции.
                </div>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
