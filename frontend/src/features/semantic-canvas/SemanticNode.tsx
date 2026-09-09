import { memo } from 'react';
import { Handle, Position, type Node, type NodeProps } from '@xyflow/react';
import {
  AlertTriangle,
  Braces,
  CornerDownLeft,
  Database,
  Diamond,
  FileCode2,
  Globe,
  Layers,
  Pin,
  ShieldCheck,
  Sparkles,
  Workflow,
} from 'lucide-react';
import type { SemanticNode as SemanticEntity } from '../../shared/api/types';
import { useT } from '../../shared/lib/i18n';
import { Badge } from '../../shared/ui';
import { useCanvasStore } from './store';

export type SemanticFlowNode = Node<
  {
    entity: SemanticEntity;
    pinned: boolean;
    compact?: boolean;
    stepIndex?: number;
  },
  'semantic'
>;

export const SemanticNode = memo(
  function SemanticNode({ data, selected }: NodeProps<SemanticFlowNode>) {
    const { entity, pinned, compact, stepIndex } = data;
    const { label, t } = useT();
    const useAiLabels = useCanvasStore((state) => state.useAiLabels);
    const displayLabel =
      useAiLabels && entity.aiLabel
        ? entity.aiLabel
        : (entity.originalLabel ?? entity.label);
    const displaySubtitle =
      useAiLabels && entity.aiSubtitle
        ? entity.aiSubtitle
        : (entity.originalSubtitle ?? entity.subtitle);
    const hasAiEnrichment = Boolean(entity.aiLabel);
    const kind = entity.kind;

    const isEndpoint = /ENDPOINT|UI_ACTION|COMMAND_ENTRY_POINT/.test(kind);
    const isReturn = /RETURN/.test(kind);
    const isSecurity = /AUTH|SECURITY|ROLE|TENANT/.test(kind);
    const isDbWrite = /DATABASE_WRITE|WRITE|INSERT|UPDATE/.test(kind);
    const isDbRead = /DATABASE_READ|READ|SELECT/.test(kind);
    const isDatabase =
      /DATA|DATABASE|TABLE|COLUMN|SQL/.test(kind) || isDbWrite || isDbRead;
    const isRule = /RULE|CONSTRAINT|VALIDATION|DECISION/.test(kind);
    const isException = /EXCEPTION|ERROR|FAULT/.test(kind);
    const isTransaction = /TRANSACTION/.test(kind);
    const isMethod = /METHOD|FUNCTION/.test(kind);

    const tone = isReturn
      ? 'endpoint'
      : isEndpoint
        ? 'endpoint'
        : isSecurity
          ? 'security'
          : isException
            ? 'exception'
            : isTransaction
              ? 'transaction'
              : isDbWrite
                ? 'data-write'
                : isDatabase
                  ? 'data'
                  : isRule
                    ? 'rule'
                    : isMethod
                      ? 'method'
                      : /EXTERNAL|SIDE_EFFECT/.test(kind)
                        ? 'external'
                        : 'technical';

    const Icon = isReturn
      ? CornerDownLeft
      : isEndpoint
        ? Globe
        : isSecurity
          ? ShieldCheck
          : isException
            ? AlertTriangle
            : isTransaction
              ? Layers
              : isDatabase
                ? Database
                : isRule
                  ? Diamond
                  : isMethod || /CLASS/.test(kind)
                    ? FileCode2
                    : /SCENARIO/.test(kind)
                      ? Workflow
                      : Braces;

    // HTTP method extraction for endpoints
    let httpMethod = '';
    if (isEndpoint) {
      if (typeof entity.properties?.httpMethod === 'string') {
        httpMethod = (entity.properties.httpMethod as string).toUpperCase();
      } else {
        const match = entity.label.match(
          /^(GET|POST|PUT|DELETE|PATCH|OPTIONS|HEAD)\b/i,
        );
        if (match?.[1]) httpMethod = match[1].toUpperCase();
      }
    }

    const supported = entity.evidenceCount > 0;

    if (compact)
      return (
        <div
          className={`semantic-node compact ${tone} ${selected ? 'selected' : ''}`}
          title={displayLabel}
        >
          <Handle type="target" position={Position.Left} />
          {stepIndex !== undefined && (
            <span
              style={{
                background:
                  stepIndex === 0
                    ? '#0e7466'
                    : isException
                      ? '#c92a2a'
                      : isReturn
                        ? '#0284c7'
                        : '#2b8a3e',
                color: '#ffffff',
                fontSize: '9px',
                fontWeight: 700,
                padding: '1px 5px',
                borderRadius: '4px',
                marginRight: '4px',
                flexShrink: 0,
              }}
              title={
                stepIndex === 0
                  ? 'Точка входа (Шаг 0)'
                  : isException
                    ? `Ошибка / Исключение (Шаг ${stepIndex})`
                    : isReturn
                      ? 'Возврат ответа в начало'
                      : `Шаг ${stepIndex}`
              }
            >
              {stepIndex === 0 ? '🏁' : isException ? '⚠️' : isReturn ? '↩️' : `Ш${stepIndex}`}
            </span>
          )}
          {httpMethod && (
            <span className={`http-badge ${httpMethod.toLowerCase()}`}>
              {httpMethod}
            </span>
          )}
          <strong>{displayLabel}</strong>
          <span
            className={`confidence-dot ${entity.confidence < 0.7 || !supported ? 'low' : ''}`}
            title={
              hasAiEnrichment
                ? 'Обогащено ИИ (проверено валидатором по коду)'
                : entity.confidence >= 0.7
                  ? 'Статический AST-анализ; ИИ не использовался'
                  : 'Эвристический вывод (требует проверки)'
            }
            style={{ display: 'inline-flex', alignItems: 'center', gap: '3px' }}
          >
            {hasAiEnrichment ? (
              <Sparkles size={10} style={{ color: '#2b8a3e' }} />
            ) : entity.confidence >= 0.7 ? (
              <ShieldCheck size={10} style={{ color: '#0ca678' }} />
            ) : (
              <AlertTriangle size={10} style={{ color: '#f08c00' }} />
            )}
            {Math.round(entity.confidence * 100)}%
          </span>
          <Handle type="source" position={Position.Right} />
        </div>
      );

    return (
      <div className={`semantic-node ${tone} ${selected ? 'selected' : ''}`}>
        <Handle type="target" position={Position.Left} />
        <header>
          <div className="node-kind-group">
            {stepIndex !== undefined && (
              <span
                style={{
                  background:
                    stepIndex === 0
                      ? '#0e7466'
                      : isException
                        ? '#c92a2a'
                        : isReturn
                          ? '#0284c7'
                          : '#2b8a3e',
                  color: '#ffffff',
                  fontSize: '10px',
                  fontWeight: 700,
                  padding: '2px 7px',
                  borderRadius: '5px',
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: '3px',
                  boxShadow: '0 1px 2px rgba(0,0,0,0.12)',
                }}
                title={
                  stepIndex === 0
                    ? 'Точка входа (Шаг 0: HTTP эндпоинт)'
                    : isException
                      ? `Исключение / ветка ошибки (Шаг ${stepIndex})`
                      : isReturn
                        ? 'Возврат ответа в начало (завершение)'
                        : `Шаг ${stepIndex} в цепочке вызовов сценария`
                }
              >
                {stepIndex === 0
                  ? '🏁 Вход'
                  : isException
                    ? '⚠️ Ошибка'
                    : isReturn
                      ? '↩️ Ответ'
                      : `Шаг ${stepIndex}`}
              </span>
            )}
            {httpMethod ? (
              <span className={`http-badge ${httpMethod.toLowerCase()}`}>
                {httpMethod}
              </span>
            ) : isReturn ? (
              <span className="tx-badge" style={{ background: 'rgba(2, 132, 199, 0.15)', color: '#0284c7' }}>
                ↩️ RETURN
              </span>
            ) : isDbWrite ? (
              <span className="db-badge write">WRITE 💾</span>
            ) : isDbRead ? (
              <span className="db-badge read">READ 🔍</span>
            ) : isSecurity ? (
              <span className="sec-badge">🔒 AUTH</span>
            ) : isException ? (
              <span className="err-badge">⚠️ ERROR</span>
            ) : isTransaction ? (
              <span className="tx-badge">🔄 TX</span>
            ) : null}
            <span className="node-kind">
              <Icon size={14} />
              {label(kind)}
            </span>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
            {hasAiEnrichment && useAiLabels && (
              <span
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: '2px',
                  fontSize: '10px',
                  fontWeight: 600,
                  padding: '1px 5px',
                  borderRadius: '4px',
                  background: 'rgba(16, 152, 173, 0.15)',
                  color: 'var(--accent, #1098ad)',
                }}
                title={
                  entity.originalLabel
                    ? `Оригинал: ${entity.originalLabel}`
                    : 'ИИ-формулировка (проверено валидатором)'
                }
              >
                <Sparkles size={10} />
                AI
              </span>
            )}
            {pinned && <Pin size={13} aria-label={t('pin')} />}
          </div>
        </header>
        <strong title={displayLabel}>{displayLabel}</strong>
        <p title={displaySubtitle ?? ''}>{displaySubtitle}</p>
        <footer>
          <span
            className={`confidence-dot ${entity.confidence < 0.7 || !supported ? 'low' : ''}`}
            title={
              hasAiEnrichment
                ? 'Обогащено ИИ (проверено валидатором по коду)'
                : entity.confidence >= 0.7
                  ? 'Статический AST-анализ; ИИ не использовался'
                  : 'Эвристический вывод (требует проверки)'
            }
            style={{ display: 'inline-flex', alignItems: 'center', gap: '3px' }}
          >
            {hasAiEnrichment ? (
              <Sparkles size={11} style={{ color: '#2b8a3e' }} />
            ) : entity.confidence >= 0.7 ? (
              <ShieldCheck size={11} style={{ color: '#0ca678' }} />
            ) : (
              <AlertTriangle size={11} style={{ color: '#f08c00' }} />
            )}
            {Math.round(entity.confidence * 100)}%
          </span>
          <span className="node-evidence" title={t('evidenceCount')}>
            <FileCode2 size={12} />
            {entity.evidenceCount}
          </span>
          <Badge
            tone={
              isException
                ? 'negative'
                : !supported || entity.supportLevel !== 'PROVEN'
                  ? 'warning'
                  : 'positive'
            }
          >
            {supported ? label(entity.supportLevel) : t('unverified')}
          </Badge>
        </footer>
        <Handle type="source" position={Position.Right} />
      </div>
    );
  },
  (previous, next) =>
    previous.data === next.data && previous.selected === next.selected,
);
