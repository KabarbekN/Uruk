import {
  lazy,
  Suspense,
  useEffect,
  useRef,
  useState,
  type CSSProperties,
} from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  AlertTriangle,
  CheckCircle2,
  Copy,
  Expand,
  FileCode2,
  Pin,
  PinOff,
  RefreshCw,
  ShieldAlert,
  ShieldCheck,
  Sparkles,
  Workflow,
  X,
} from 'lucide-react';
import { api } from '../../shared/api/client';
import type {
  Evidence,
  ScenarioMember,
  LlmEnrichment,
  SemanticNodeDetail,
} from '../../shared/api/types';
import { useT } from '../../shared/lib/i18n';
import {
  Badge,
  Button,
  EmptyState,
  ErrorState,
  IconButton,
  JsonView,
  Loading,
  Status,
} from '../../shared/ui';
import { notify } from '../../shared/ui/notifications';
import { useCanvasStore, type Selection } from '../semantic-canvas/store';
import { ReviewForm } from '../review-workflow/ReviewForm';
import { DecisionFlowDiagram } from './DecisionFlowDiagram';
import {
  formatAstCondition,
  explainConditionInRussian,
  humanizeDescription,
  humanizeScenarioStep,
} from './ast-humanizer';

const CodeViewer = lazy(() => import('./CodeViewer'));

function getAnalysisProvenance(nodeData: SemanticNodeDetail) {
  const origins = Array.isArray(nodeData.properties?.origins)
    ? (nodeData.properties.origins as string[])
    : [];
  const analyzers = Array.isArray(nodeData.properties?.analyzers)
    ? (nodeData.properties.analyzers as string[]).join(', ')
    : 'semanticmap/analyzer';
  const hasAi = Boolean(nodeData.enrichment);
  const aiReviewed =
    /CONFIRMED|EDITED/.test(nodeData.reviewStatus) ||
    /VERIFIED|CONFIRMED/.test(nodeData.enrichment?.trustStatus ?? '');
  const confidence = nodeData.confidence ?? 1;
  const isStrictAst =
    origins.some((o) =>
      [
        'STATIC_EXACT',
        'STATIC_TYPED',
        'STATIC_RESOLVED',
        'DATABASE_DERIVED',
      ].includes(o),
    ) ||
    [
      'ENDPOINT',
      'DATABASE_READ',
      'DATABASE_WRITE',
      'TABLE',
      'COLUMN',
      'CLASS',
      'METHOD',
      'TRANSACTION',
    ].includes(nodeData.kind);

  if (hasAi) {
    const claimsCount = nodeData.enrichment?.claims?.length ?? 0;
    const ambiguitiesCount = nodeData.enrichment?.ambiguities?.length ?? 0;
    if (!aiReviewed || ambiguitiesCount > 0 || confidence < 0.7) {
      return {
        tier: 'AI_UNVERIFIED' as const,
        title: 'Формулировка ИИ требует проверки',
        hallucinationRisk: 'Не оценен до проверки человеком',
        riskTone: 'warning' as const,
        methodLabel: 'ИИ-формулировка по структурированным фактам',
        description: `Текст составлен моделью и остается непроверенным. ${claimsCount} утверждений привязаны к фактам кода, ${ambiguitiesCount} зон отмечены как неоднозначные.`,
        engine: `LLM + ${analyzers}`,
        proof: `${claimsCount} ссылок на факты; требуется решение ревьюера`,
      };
    }
    return {
      tier: 'AI_VERIFIED' as const,
      title: 'ИИ-формулировка проверена человеком',
      hallucinationRisk: 'Контролируется доказательствами и ревью',
      riskTone: 'positive' as const,
      methodLabel: 'Гибридный анализ (AST + Валидированный ИИ)',
      description:
        'Бизнес-описание составлено по фактам анализа и принято человеком. Ссылки на доказательства доступны ниже.',
      engine: `AST (${analyzers}) + LLM Guard`,
      proof:
        claimsCount > 0
          ? `${claimsCount} утверждений строго совпали со строками кода`
          : 'Сверено с AST-графом',
    };
  }

  if (isStrictAst && confidence >= 0.7) {
    return {
      tier: 'STRICT_AST' as const,
      title: 'Детерминированный анализ исходного кода',
      hallucinationRisk: 'ИИ не использовался',
      riskTone: 'positive' as const,
      methodLabel: 'Статический синтаксический анализ (100% Код)',
      description:
        'Узел извлечен анализатором из исходного кода. Откройте доказательства, чтобы проверить конкретный фрагмент.',
      engine: analyzers,
      proof: `Подтверждено в кодовой базе (${nodeData.evidenceCount || 1} доказательств)`,
    };
  }

  return {
    tier: 'HEURISTIC' as const,
    title: 'Вероятностный вывод',
    hallucinationRisk: 'Требует проверки (низкая уверенность)',
    riskTone: 'warning' as const,
    methodLabel: 'Эвристика / Недостаточный контекст',
    description:
      'В исходном коде присутствуют нетипизированные вызовы или неполные данные. Проверьте детали вручную.',
    engine: analyzers,
    proof: 'Неполное покрытие исходниками',
  };
}

function SourceEvidence({ evidence }: { evidence: Evidence[] }) {
  const { t } = useT();
  const [selectedId, setSelectedId] = useState(evidence[0]?.id);
  const current =
    evidence.find((item) => item.id === selectedId) ?? evidence[0];
  if (!current)
    return (
      <EmptyState
        icon={ShieldAlert}
        title={t('noEvidence')}
        detail={t('noEvidenceDetail')}
      />
    );
  const copy = () => {
    if (!navigator.clipboard) {
      notify(t('copyFailed'));
      return;
    }
    if (current.snippet !== null && current.sourceAvailable !== false)
      void navigator.clipboard.writeText(current.snippet).then(
        () => notify(t('copied')),
        () => notify(t('copyFailed')),
      );
  };
  const displaySnippet = current.enclosingSnippet || current.snippet;
  const displayStart = current.enclosingStartLine || current.startLine;
  const displayEnd = current.enclosingEndLine || current.endLine;
  const hlStart = current.highlightStartLine || current.startLine;
  const hlEnd = current.highlightEndLine || current.endLine;

  return (
    <>
      <div className="evidence-list">
        {evidence.map((item) => (
          <button
            type="button"
            key={item.id}
            className={`evidence-file ${current.id === item.id ? 'active' : ''}`}
            onClick={() => setSelectedId(item.id)}
          >
            <FileCode2 size={17} />
            <span>
              <strong>{item.filePath}</strong>
              <small>
                {t('lines')} {item.highlightStartLine || item.startLine}-{item.highlightEndLine || item.endLine}
              </small>
            </span>
            {item.verified ? (
              <CheckCircle2 size={15} aria-label={t('verified')} />
            ) : (
              <ShieldAlert size={15} aria-label={t('unverified')} />
            )}
          </button>
        ))}
      </div>
      <div className="source-toolbar">
        <Badge tone={current.verified ? 'positive' : 'warning'}>
          {t(current.verified ? 'verified' : 'unverified')}
        </Badge>
        <span className="mono break-word">
          {current.filePath}:{hlStart}{hlEnd !== hlStart ? `-${hlEnd}` : ''}
        </span>
        <IconButton
          icon={Copy}
          label={t('copy')}
          onClick={copy}
          disabled={
            current.snippet === null || current.sourceAvailable === false
          }
        />
      </div>
      {current.enclosingDeclaration && (
        <div
          style={{
            padding: '6px 10px',
            margin: '6px 0 10px 0',
            background: 'rgba(56, 189, 248, 0.08)',
            border: '1px solid rgba(56, 189, 248, 0.25)',
            borderRadius: '6px',
            fontSize: '12px',
            fontFamily: 'Cascadia Code, Consolas, monospace',
            display: 'flex',
            alignItems: 'center',
            gap: '8px',
            overflow: 'hidden',
          }}
          title={current.enclosingDeclaration}
        >
          <span style={{ color: '#38bdf8', fontWeight: 600, flexShrink: 0 }}>
            Метод:
          </span>
          <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
            {current.enclosingDeclaration}
          </span>
        </div>
      )}
      {current.sourceAvailable === false || displaySnippet === null ? (
        <p className="notice warning">
          {t(
            current.sourceAvailable === false
              ? 'sourceExpired'
              : 'sourceUnavailable',
          )}
        </p>
      ) : (
        <Suspense fallback={<Loading compact />}>
          <CodeViewer
            snippet={displaySnippet}
            filePath={current.filePath}
            startLine={displayStart}
            endLine={displayEnd}
            highlightStartLine={hlStart}
            highlightEndLine={hlEnd}
          />
        </Suspense>
      )}
      <div className="drawer-section">
        <h3>{t('provenance')}</h3>
        <dl className="property-list">
          <dt>{t('analyzer')}</dt>
          <dd>{current.analyzerId}</dd>
          <dt>{t('version')}</dt>
          <dd>{current.analyzerVersion ?? '\u2014'}</dd>
          <dt>{t('origin')}</dt>
          <dd>{current.origin}</dd>
          <dt>{t('digest')}</dt>
          <dd className="mono">{current.imageDigest ?? '\u2014'}</dd>
          <dt>{t('hash')}</dt>
          <dd className="mono">{current.snippetHash ?? '\u2014'}</dd>
        </dl>
      </div>
    </>
  );
}

function ScenarioFlow({
  nodeId,
  label,
  method,
  path,
  members,
  assertions,
  enrichment,
  viewMode = 'business',
  onExplore,
  onEnrichSuccess,
}: {
  nodeId: string;
  label?: string;
  method?: string | null;
  path?: string | null;
  members: ScenarioMember[];
  assertions?: unknown[];
  enrichment?: LlmEnrichment | null;
  viewMode?: 'business' | 'tech';
  onExplore?: (id: string) => void;
  onEnrichSuccess?: () => void;
}) {
  const { t } = useT();
  const [flowDisplay, setFlowDisplay] = useState<'tree' | 'steps'>('tree');
  const [isEnriching, setIsEnriching] = useState(false);
  const [enrichError, setEnrichError] = useState<string | null>(null);
  const enrichmentReviewed = /VERIFIED|CONFIRMED/.test(
    enrichment?.trustStatus ?? '',
  );

  const handleEnrich = async () => {
    setIsEnriching(true);
    setEnrichError(null);
    try {
      await api.enrichNode(nodeId);
      notify('Бизнес-сценарий успешно сформирован с помощью LLM!');
      onEnrichSuccess?.();
    } catch (err: unknown) {
      const msg =
        err instanceof Error
          ? err.message
          : 'Не удалось выполнить анализ нейросетью';
      setEnrichError(msg);
      notify(`Ошибка обогащения: ${msg}`);
    } finally {
      setIsEnriching(false);
    }
  };

  const getTone = (
    kind: string,
  ): 'positive' | 'warning' | 'negative' | 'neutral' => {
    switch (kind) {
      case 'ENDPOINT':
      case 'BUSINESS_SCENARIO':
        return 'positive';
      case 'EXCEPTION':
        return 'negative';
      case 'TRANSACTION':
      case 'SECURITY_RULE':
      case 'AUTHORIZATION_RULE':
      case 'BUSINESS_RULE':
      case 'VALIDATION_RULE':
      case 'DATA_RULE':
      case 'UNKNOWN_RULE':
        return 'warning';
      default:
        return 'neutral';
    }
  };

  const getKindLabel = (kind: string) => {
    switch (kind) {
      case 'ENDPOINT':
        return 'Эндпоинт';
      case 'METHOD':
        return 'Метод';
      case 'EXCEPTION':
        return 'Исключение';
      case 'TRANSACTION':
        return 'Транзакция';
      case 'BUSINESS_RULE':
      case 'DATA_RULE':
      case 'UNKNOWN_RULE':
        return 'Правило';
      case 'VALIDATION_RULE':
        return 'Валидация';
      case 'TEST_CASE':
        return 'Тест';
      case 'DATABASE_READ':
      case 'DATABASE_WRITE':
        return 'База данных';
      default:
        return kind;
    }
  };

  return (
    <div className="drawer-section">
      {/* 1. LLM ENRICHMENT SECTION */}
      {enrichment ? (
        <div
          style={{
            background:
              'linear-gradient(135deg, rgba(14, 116, 102, 0.06) 0%, rgba(59, 130, 246, 0.06) 100%)',
            border: '1px solid rgba(14, 116, 102, 0.25)',
            borderRadius: '10px',
            padding: '1.25rem',
            marginBottom: '1.5rem',
            display: 'flex',
            flexDirection: 'column',
            gap: '0.9rem',
            boxShadow: '0 2px 8px rgba(0, 0, 0, 0.04)',
          }}
        >
          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              flexWrap: 'wrap',
              gap: '0.5rem',
            }}
          >
            <div
              style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}
            >
              <Sparkles
                size={20}
                style={{ color: 'var(--primary, #0e7466)' }}
              />
              <strong
                style={{
                  fontSize: '1.1rem',
                  color: 'var(--text, #26322e)',
                  fontWeight: 700,
                }}
              >
                {enrichment.title || 'Бизнес-сценарий (LLM)'}
              </strong>
            </div>
            <div
              style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}
            >
              <Badge tone={enrichmentReviewed ? 'positive' : 'warning'}>
                {enrichment.category || 'Бизнес-логика'}
              </Badge>
              <Button
                variant="ghost"
                onClick={handleEnrich}
                disabled={isEnriching}
                style={{
                  fontSize: '0.75rem',
                  padding: '0.2rem 0.5rem',
                  gap: '0.25rem',
                }}
              >
                <RefreshCw size={12} className={isEnriching ? 'spin' : ''} />
                {isEnriching ? 'Обновление...' : 'Перегенерировать'}
              </Button>
            </div>
          </div>

          {/* Trust is shown separately from structural validation. */}
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              gap: '8px',
              padding: '7px 10px',
              background: enrichmentReviewed
                ? 'var(--positive-soft)'
                : 'var(--warning-soft)',
              borderRadius: '6px',
              border: `1px solid ${enrichmentReviewed ? 'var(--positive)' : 'var(--warning)'}`,
              fontSize: '11.5px',
              flexWrap: 'wrap',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
              {enrichmentReviewed ? (
                <ShieldCheck
                  size={15}
                  style={{ color: 'var(--positive)', flexShrink: 0 }}
                />
              ) : (
                <AlertTriangle
                  size={15}
                  style={{ color: 'var(--warning)', flexShrink: 0 }}
                />
              )}
              <span>
                <strong>
                  {enrichmentReviewed
                    ? 'Проверено человеком:'
                    : 'Нужна проверка:'}
                </strong>{' '}
                {enrichment.claims?.length || 0} утверждений привязаны к фактам
                кода.
              </span>
            </div>
            <Badge tone={enrichmentReviewed ? 'positive' : 'warning'}>
              {enrichmentReviewed ? 'Принято' : 'Формулировка ИИ · UNVERIFIED'}
            </Badge>
          </div>

          {Boolean(enrichment.ambiguities?.length) && (
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: '8px',
                padding: '7px 10px',
                background: '#fff9db',
                borderRadius: '6px',
                border: '1px solid #ffe066',
                fontSize: '11.5px',
                color: '#854d0e',
              }}
            >
              <AlertTriangle
                size={15}
                style={{ color: '#f08c00', flexShrink: 0 }}
              />
              <span>
                <strong>Внимание:</strong> ИИ отметил зоны с неполным
                контекстом: {enrichment.ambiguities!.join(', ')}. Проверьте их в
                коде.
              </span>
            </div>
          )}

          {/* Structured business narrative from AI */}
          {(enrichment.result?.businessPurpose || enrichment.businessPurpose) ? (
            <div
              style={{
                display: 'flex',
                flexDirection: 'column',
                gap: '0.75rem',
                background: 'var(--surface, #ffffff)',
                padding: '1rem',
                borderRadius: '8px',
                border: '1px solid var(--border, #e1e7e3)',
              }}
            >
              <div>
                <span
                  style={{
                    fontSize: '0.75rem',
                    fontWeight: 700,
                    color: 'var(--primary, #0e7466)',
                    textTransform: 'uppercase',
                    letterSpacing: '0.05em',
                  }}
                >
                  🎯 Бизнес-цель операции
                </span>
                <p
                  style={{
                    margin: '0.3rem 0 0',
                    fontSize: '0.88rem',
                    color: 'var(--text, #26322e)',
                    lineHeight: 1.5,
                  }}
                >
                  {enrichment.result?.businessPurpose ?? enrichment.businessPurpose}
                </p>
              </div>

              {Boolean(
                (enrichment.result?.actors?.length ?? 0) ||
                  (enrichment.actors?.length ?? 0),
              ) && (
                <div>
                  <span
                    style={{
                      fontSize: '0.75rem',
                      fontWeight: 700,
                      color: 'var(--muted, #69756f)',
                      textTransform: 'uppercase',
                      letterSpacing: '0.05em',
                    }}
                  >
                    👥 Кто инициирует / Участники
                  </span>
                  <div
                    style={{
                      display: 'flex',
                      flexWrap: 'wrap',
                      gap: '0.35rem',
                      marginTop: '0.3rem',
                    }}
                  >
                    {(
                      enrichment.result?.actors ??
                      enrichment.actors ??
                      []
                    ).map((actor, idx) => (
                      <Badge key={idx} tone="neutral">
                        {actor}
                      </Badge>
                    ))}
                  </div>
                </div>
              )}

              {Boolean(
                (enrichment.result?.businessSteps?.length ?? 0) ||
                  (enrichment.businessSteps?.length ?? 0),
              ) && (
                <div>
                  <span
                    style={{
                      fontSize: '0.75rem',
                      fontWeight: 700,
                      color: 'var(--muted, #69756f)',
                      textTransform: 'uppercase',
                      letterSpacing: '0.05em',
                    }}
                  >
                    📋 Бизнес-шаги выполнения
                  </span>
                  <ol
                    style={{
                      margin: '0.35rem 0 0 1.25rem',
                      padding: 0,
                      fontSize: '0.85rem',
                      color: 'var(--text, #26322e)',
                      lineHeight: 1.55,
                    }}
                  >
                    {(
                      enrichment.result?.businessSteps ??
                      enrichment.businessSteps ??
                      []
                    ).map((step, idx) => (
                      <li key={idx} style={{ marginBottom: '0.25rem' }}>
                        {step}
                      </li>
                    ))}
                  </ol>
                </div>
              )}

              {Boolean(
                (enrichment.result?.businessRules?.length ?? 0) ||
                  (enrichment.businessRules?.length ?? 0),
              ) && (
                <div>
                  <span
                    style={{
                      fontSize: '0.75rem',
                      fontWeight: 700,
                      color: 'var(--muted, #69756f)',
                      textTransform: 'uppercase',
                      letterSpacing: '0.05em',
                    }}
                  >
                    ⚖️ Проверяемые бизнес-правила
                  </span>
                  <ul
                    style={{
                      margin: '0.35rem 0 0 1.25rem',
                      padding: 0,
                      fontSize: '0.85rem',
                      color: 'var(--text, #26322e)',
                      lineHeight: 1.55,
                    }}
                  >
                    {(
                      enrichment.result?.businessRules ??
                      enrichment.businessRules ??
                      []
                    ).map((rule, idx) => (
                      <li key={idx} style={{ marginBottom: '0.25rem' }}>
                        {rule}
                      </li>
                    ))}
                  </ul>
                </div>
              )}

              {Boolean(
                (enrichment.result?.errorScenarios?.length ?? 0) ||
                  (enrichment.errorScenarios?.length ?? 0),
              ) && (
                <div
                  style={{
                    background: 'rgba(239, 68, 68, 0.05)',
                    padding: '0.65rem 0.85rem',
                    borderRadius: '6px',
                    border: '1px solid rgba(239, 68, 68, 0.2)',
                  }}
                >
                  <span
                    style={{
                      fontSize: '0.75rem',
                      fontWeight: 700,
                      color: '#dc2626',
                      textTransform: 'uppercase',
                      letterSpacing: '0.05em',
                    }}
                  >
                    ⚠️ Сценарии сбоев и реакция системы
                  </span>
                  <ul
                    style={{
                      margin: '0.35rem 0 0 1.25rem',
                      padding: 0,
                      fontSize: '0.83rem',
                      color: 'var(--text, #26322e)',
                      lineHeight: 1.5,
                    }}
                  >
                    {(
                      enrichment.result?.errorScenarios ??
                      enrichment.errorScenarios ??
                      []
                    ).map((err, idx) => (
                      <li key={idx} style={{ marginBottom: '0.25rem' }}>
                        {err}
                      </li>
                    ))}
                  </ul>
                </div>
              )}
            </div>
          ) : enrichment.description ? (
            <div
              style={{
                fontSize: '0.88rem',
                color: 'var(--text, #26322e)',
                lineHeight: 1.6,
                whiteSpace: 'pre-line',
                background: 'var(--surface, #ffffff)',
                padding: '0.85rem 1rem',
                borderRadius: '8px',
                border: '1px solid var(--border, #e1e7e3)',
              }}
            >
              {humanizeDescription(enrichment.description)}
            </div>
          ) : null}

          {Boolean(enrichment.claims?.length) && (
            <div>
              <span
                style={{
                  fontSize: '0.75rem',
                  fontWeight: 700,
                  color: 'var(--muted, #69756f)',
                  textTransform: 'uppercase',
                  letterSpacing: '0.05em',
                }}
              >
                Бизнес-правила и условия выполнения
              </span>
              <div
                style={{
                  display: 'flex',
                  flexDirection: 'column',
                  gap: '0.5rem',
                  marginTop: '0.45rem',
                }}
              >
                {enrichment.claims!.map((claim, idx) => {
                  const formattedValue = formatAstCondition(claim.value);
                  const explanation = explainConditionInRussian(formattedValue);
                  const fieldTitle =
                    claim.field === 'condition' ||
                    claim.field === 'normalizedCondition'
                      ? 'Условие проверки'
                      : claim.field === 'trueOutcomes'
                        ? 'При успехе'
                        : claim.field === 'falseOutcomes'
                          ? 'При ошибке'
                          : claim.field === 'dataWrites'
                            ? 'Запись в БД'
                            : claim.field === 'dataReads'
                              ? 'Чтение из БД'
                              : claim.field;

                  return (
                    <div
                      key={idx}
                      style={{
                        display: 'flex',
                        flexDirection: 'column',
                        gap: '0.3rem',
                        fontSize: '0.85rem',
                        background: 'var(--surface, #ffffff)',
                        padding: '0.65rem 0.85rem',
                        borderRadius: '6px',
                        border: '1px solid var(--border, #e1e7e3)',
                      }}
                    >
                      <div
                        style={{
                          display: 'flex',
                          alignItems: 'center',
                          gap: '0.5rem',
                          justifyContent: 'space-between',
                          flexWrap: 'wrap',
                        }}
                      >
                        <span
                          style={{
                            color: 'var(--primary, #0e7466)',
                            fontWeight: 600,
                          }}
                        >
                          {fieldTitle}:
                        </span>
                        <div className="badge-row">
                          <Badge
                            tone={enrichmentReviewed ? 'positive' : 'warning'}
                          >
                            {enrichmentReviewed
                              ? 'Проверено'
                              : 'Нужна проверка'}
                          </Badge>
                          {explanation && (
                            <Badge
                              tone="neutral"
                              style={{ fontSize: '0.72rem' }}
                            >
                              {explanation}
                            </Badge>
                          )}
                        </div>
                      </div>
                      <span
                        className="mono"
                        style={{
                          color: 'var(--text, #26322e)',
                          wordBreak: 'break-word',
                          background: 'var(--surface-subtle, #f5f7f6)',
                          padding: '0.35rem 0.6rem',
                          borderRadius: '4px',
                          fontSize: '0.8rem',
                          lineHeight: 1.45,
                        }}
                      >
                        {formattedValue}
                      </span>
                    </div>
                  );
                })}
              </div>
            </div>
          )}
        </div>
      ) : (
        <div
          style={{
            background:
              'linear-gradient(135deg, rgba(14, 116, 102, 0.04) 0%, rgba(59, 130, 246, 0.04) 100%)',
            border: '1px dashed rgba(14, 116, 102, 0.3)',
            borderRadius: '10px',
            padding: '1.25rem',
            marginBottom: '1.5rem',
            display: 'flex',
            flexDirection: 'column',
            gap: '0.75rem',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
            <Sparkles size={20} style={{ color: 'var(--primary, #0e7466)' }} />
            <strong
              style={{
                fontSize: '1.05rem',
                color: 'var(--text, #26322e)',
                fontWeight: 700,
              }}
            >
              ИИ-описание бизнес-сценария ещё формируется
            </strong>
          </div>
          <p
            style={{
              margin: 0,
              fontSize: '0.88rem',
              color: 'var(--muted, #69756f)',
              lineHeight: 1.5,
            }}
          >
            После построения технического сценария ИИ-анализ запускается
            автоматически. Если провайдер недоступен, технические находки и
            доказательства всё равно останутся доступны.
          </p>
          {enrichError && (
            <div
              style={{
                color: '#dc2626',
                fontSize: '0.82rem',
                background: '#fee2e2',
                padding: '0.5rem 0.75rem',
                borderRadius: '6px',
                border: '1px solid #fca5a5',
              }}
            >
              {enrichError}
            </div>
          )}
          <div
            style={{
              display: 'flex',
              gap: '0.5rem',
              alignItems: 'center',
              flexWrap: 'wrap',
              marginTop: '0.25rem',
            }}
          >
            <Button
              variant="primary"
              onClick={handleEnrich}
              disabled={isEnriching}
              style={{ gap: '0.4rem', fontSize: '0.85rem' }}
            >
              <Sparkles size={15} />
              {isEnriching
                ? 'Анализ нейросетью...'
                : 'Повторить ИИ-анализ сейчас'}
            </Button>
          </div>
        </div>
      )}

      {/* 2. STEP-BY-STEP FLOW OR DECISION TREE */}
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: '0.75rem',
          flexWrap: 'wrap',
          gap: '0.5rem',
        }}
      >
        <h3
          style={{
            fontSize: '0.95rem',
            margin: 0,
            display: 'flex',
            alignItems: 'center',
            gap: '6px',
          }}
        >
          <span>
            {flowDisplay === 'tree'
              ? '🌳 Дерево решений (Flow эндпоинта)'
              : viewMode === 'business'
                ? 'Бизнес-процесс выполнения (по шагам)'
                : 'Связанные элементы сценария (код и AST)'}
          </span>
          <span
            title={
              viewMode === 'business'
                ? 'Шаги выстроены по цепочке вызовов контроллера, проверок безопасности и запросов к БД.'
                : 'Элементы сгруппированы по глубине связей. Точный порядок выполнения пока не доказан статическим анализом.'
            }
            style={{ cursor: 'help', fontSize: '13px', opacity: 0.7 }}
          >
            ℹ️
          </span>
        </h3>
        <div
          style={{
            display: 'inline-flex',
            background: 'var(--surface-subtle, #f1f5f9)',
            borderRadius: '6px',
            padding: '2px',
            border: '1px solid var(--border-subtle, #cbd5e1)',
          }}
        >
          <button
            type="button"
            onClick={() => setFlowDisplay('tree')}
            style={{
              padding: '3px 9px',
              fontSize: '11px',
              fontWeight: 600,
              borderRadius: '4px',
              border: 'none',
              background:
                flowDisplay === 'tree'
                  ? 'var(--primary, #0e7466)'
                  : 'transparent',
              color:
                flowDisplay === 'tree' ? '#fff' : 'var(--text-muted, #64748b)',
              cursor: 'pointer',
            }}
          >
            🌳 Дерево решений
          </button>
          <button
            type="button"
            onClick={() => setFlowDisplay('steps')}
            style={{
              padding: '3px 9px',
              fontSize: '11px',
              fontWeight: 600,
              borderRadius: '4px',
              border: 'none',
              background:
                flowDisplay === 'steps'
                  ? 'var(--primary, #0e7466)'
                  : 'transparent',
              color:
                flowDisplay === 'steps' ? '#fff' : 'var(--text-muted, #64748b)',
              cursor: 'pointer',
            }}
          >
            📋 Список шагов
          </button>
        </div>
      </div>

      {!members.length ? (
        <EmptyState
          icon={Workflow}
          title={t('noFlowData')}
          detail="Для данного узла не зафиксировано входящих или исходящих шагов сценария."
        />
      ) : flowDisplay === 'tree' ? (
        <DecisionFlowDiagram
          endpointLabel={label || ''}
          method={method}
          path={path}
          members={members}
          enrichment={enrichment}
          onExploreNode={onExplore}
        />
      ) : (
        <>
          <div
            style={{
              display: 'flex',
              flexDirection: 'column',
              gap: '0.65rem',
              marginBottom: '1.5rem',
            }}
          >
            {members.map((member) => {
              if (viewMode === 'business') {
                const step = humanizeScenarioStep(
                  member.kind,
                  member.label,
                  member.subtitle,
                );
                const isError = step.category === 'error';
                const isAuth = step.category === 'auth';
                const isDb = step.category === 'db';
                return (
                  <div
                    key={member.id}
                    style={{
                      display: 'flex',
                      flexDirection: 'column',
                      gap: '0.4rem',
                      padding: '0.85rem 1rem',
                      background: isError
                        ? 'rgba(239, 68, 68, 0.08)'
                        : isAuth
                          ? 'rgba(14, 116, 102, 0.08)'
                          : isDb
                            ? 'rgba(59, 130, 246, 0.08)'
                            : 'rgba(255, 255, 255, 0.04)',
                      borderRadius: '8px',
                      border: isError
                        ? '1px solid rgba(239, 68, 68, 0.3)'
                        : isAuth
                          ? '1px solid rgba(14, 116, 102, 0.3)'
                          : isDb
                            ? '1px solid rgba(59, 130, 246, 0.3)'
                            : '1px solid rgba(255, 255, 255, 0.08)',
                    }}
                  >
                    <div
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'space-between',
                        gap: '0.5rem',
                      }}
                    >
                      <div
                        style={{
                          display: 'flex',
                          alignItems: 'center',
                          gap: '0.5rem',
                        }}
                      >
                        <span
                          style={{
                            fontSize: '0.75rem',
                            opacity: 0.75,
                            fontWeight: 700,
                          }}
                        >
                          Шаг {member.depth}
                        </span>
                        <Badge
                          tone={
                            isError
                              ? 'negative'
                              : isAuth
                                ? 'positive'
                                : isDb
                                  ? 'neutral'
                                  : 'neutral'
                          }
                        >
                          {step.categoryLabel}
                        </Badge>
                      </div>
                      {onExplore && (
                        <Button
                          variant="ghost"
                          onClick={() => onExplore(member.id)}
                          aria-label={t('focusOnGraph')}
                          style={{
                            padding: '0.2rem 0.5rem',
                            fontSize: '0.75rem',
                          }}
                        >
                          <Expand
                            size={12}
                            style={{ marginRight: '0.25rem' }}
                          />
                          {t('focusOnGraph')}
                        </Button>
                      )}
                    </div>
                    <strong
                      style={{
                        fontSize: '0.95rem',
                        color: 'var(--text, #fff)',
                        wordBreak: 'break-word',
                      }}
                    >
                      {step.businessTitle}
                    </strong>
                    {step.businessDetail && (
                      <p
                        style={{
                          margin: 0,
                          fontSize: '0.85rem',
                          color: 'var(--muted, #94a3b8)',
                          lineHeight: 1.45,
                        }}
                      >
                        {step.businessDetail}
                      </p>
                    )}
                  </div>
                );
              }

              return (
                <div
                  key={member.id}
                  style={{
                    display: 'flex',
                    flexDirection: 'column',
                    gap: '0.35rem',
                    padding: '0.75rem',
                    background: 'rgba(255, 255, 255, 0.04)',
                    borderRadius: '8px',
                    border: '1px solid rgba(255, 255, 255, 0.08)',
                  }}
                >
                  <div
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                      gap: '0.5rem',
                    }}
                  >
                    <div
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: '0.5rem',
                      }}
                    >
                      <span
                        style={{
                          fontSize: '0.75rem',
                          opacity: 0.7,
                          fontWeight: 600,
                        }}
                      >
                        {t('step')} {member.depth}
                      </span>
                      <Badge tone={getTone(member.kind)}>
                        {getKindLabel(member.kind)}
                      </Badge>
                    </div>
                    {onExplore && (
                      <Button
                        variant="ghost"
                        onClick={() => onExplore(member.id)}
                        aria-label={t('focusOnGraph')}
                        style={{
                          padding: '0.2rem 0.5rem',
                          fontSize: '0.75rem',
                        }}
                      >
                        <Expand
                          size={12}
                          style={{ marginRight: '0.25rem' }}
                        />
                        {t('focusOnGraph')}
                      </Button>
                    )}
                  </div>
                  <strong
                    style={{ fontSize: '0.9rem', wordBreak: 'break-word' }}
                  >
                    {member.label}
                  </strong>
                  {member.subtitle && (
                    <span
                      className="muted small"
                      style={{ wordBreak: 'break-word' }}
                    >
                      {member.subtitle}
                    </span>
                  )}
                  {member.properties &&
                    Object.keys(member.properties).length > 0 && (
                      <details style={{ marginTop: '0.25rem' }}>
                        <summary
                          className="muted small"
                          style={{ cursor: 'pointer' }}
                        >
                          Свойства
                        </summary>
                        <div style={{ marginTop: '0.25rem' }}>
                          <JsonView value={member.properties} />
                        </div>
                      </details>
                    )}
                </div>
              );
            })}
          </div>
        </>
      )}

      {Boolean(assertions?.length) && (
        <div style={{ marginTop: '1.5rem' }}>
          <h3>{t('assertions')}</h3>
          <JsonView value={assertions} />
        </div>
      )}
    </div>
  );
}

export function EvidenceDrawer({
  selection,
  onClose,
  scope,
  onExplore,
}: {
  selection: Selection;
  onClose: () => void;
  scope?: string;
  onExplore?: (id: string) => void;
}) {
  const { t } = useT();
  const [tab, setTab] = useState<'flow' | 'source' | 'properties' | 'reviews'>(
    'source',
  );
  const [viewMode, setViewMode] = useState<'business' | 'tech'>('business');
  const width = useCanvasStore((state) => state.drawerWidth);
  const setWidth = useCanvasStore((state) => state.setDrawerWidth);
  const pins = useCanvasStore((state) =>
    scope ? state.pins[scope] : undefined,
  );
  const node = useQuery({
    queryKey: ['node', selection.id],
    queryFn: ({ signal }) => api.node(selection.id, signal),
    enabled: selection.kind === 'nodes',
  });
  const evidence = useQuery({
    queryKey: ['evidence', selection.kind, selection.id],
    queryFn: ({ signal }) => api.evidence(selection.kind, selection.id, signal),
  });
  const hasFlow = Boolean(node.data?.scenarioMembers?.length);
  useEffect(() => {
    if (hasFlow) {
      setTab('flow');
    }
  }, [hasFlow, selection.id]);
  const ref = useRef<HTMLElement>(null);
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  useEffect(() => {
    const previous = document.activeElement;
    ref.current
      ?.querySelector<HTMLButtonElement>('.drawer-header button')
      ?.focus();
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !document.querySelector('dialog[open]'))
        closeRef.current();
    };
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('keydown', escape);
      if (
        (previous instanceof HTMLElement || previous instanceof SVGElement) &&
        previous.isConnected
      )
        previous.focus();
    };
  }, []);
  const pinned = node.data ? pins?.includes(node.data.stableKey) : false;
  const resize = (value: number) =>
    setWidth(Math.min(880, Math.max(360, value)));
  return (
    <aside
      ref={ref}
      className="evidence-drawer"
      data-testid="evidence-drawer"
      style={{ '--drawer-width': `${width}px` } as CSSProperties}
      role="dialog"
      aria-label={t('evidence')}
    >
      <div
        className="drawer-resize"
        role="separator"
        aria-label={t('drawerWidth')}
        aria-orientation="vertical"
        aria-valuenow={width}
        aria-valuemin={360}
        aria-valuemax={880}
        tabIndex={0}
        onKeyDown={(event) => {
          if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
            event.preventDefault();
            resize(width + (event.key === 'ArrowLeft' ? 24 : -24));
          }
        }}
        onPointerDown={(event) => {
          event.currentTarget.setPointerCapture(event.pointerId);
        }}
        onPointerMove={(event) => {
          if (event.currentTarget.hasPointerCapture(event.pointerId))
            resize(window.innerWidth - event.clientX);
        }}
        onPointerUp={(event) =>
          event.currentTarget.releasePointerCapture(event.pointerId)
        }
      />
      <header className="drawer-header">
        <div>
          <span className="eyebrow">
            {t(selection.kind === 'nodes' ? 'node' : 'edge')}
          </span>
          <h2>{node.data?.label || selection.label}</h2>
        </div>
        <IconButton icon={X} label={t('close')} onClick={onClose} />
      </header>
      {node.data && (
        <div className="drawer-summary">
          <p>{node.data.subtitle}</p>
          <div className="badge-row">
            <Status
              value={
                node.data.evidenceCount ? node.data.supportLevel : 'UNVERIFIED'
              }
            />
            <Status value={node.data.reviewStatus} />
            <Badge>
              {Math.round(node.data.confidence * 100)}% {t('confidence')}
            </Badge>
          </div>

          {/* Compact Provenance & Hallucination Guard Badge */}
          {(() => {
            const prov = getAnalysisProvenance(node.data);
            return (
              <div
                className="provenance-inline-badge"
                title={`${prov.title}\n${prov.description}\n\n• Риск галлюцинаций: ${prov.hallucinationRisk}\n• Метод анализа: ${prov.methodLabel}\n• Источник фактов: ${prov.proof}`}
                style={{
                  marginTop: '8px',
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: '6px',
                  padding: '4px 10px',
                  borderRadius: '6px',
                  fontSize: '11px',
                  fontWeight: 600,
                  cursor: 'help',
                  width: 'fit-content',
                  background:
                    prov.tier === 'STRICT_AST'
                      ? 'rgba(12, 166, 120, 0.08)'
                      : prov.tier === 'AI_VERIFIED'
                        ? 'rgba(43, 138, 62, 0.08)'
                        : 'rgba(245, 159, 0, 0.1)',
                  color:
                    prov.tier === 'STRICT_AST'
                      ? '#0ca678'
                      : prov.tier === 'AI_VERIFIED'
                        ? '#2b8a3e'
                        : '#e67700',
                  border:
                    prov.tier === 'STRICT_AST'
                      ? '1px solid rgba(12, 166, 120, 0.25)'
                      : prov.tier === 'AI_VERIFIED'
                        ? '1px solid rgba(43, 138, 62, 0.25)'
                        : '1px solid rgba(245, 159, 0, 0.3)',
                }}
              >
                {prov.tier === 'STRICT_AST' ? (
                  <ShieldCheck size={14} style={{ color: '#0ca678', flexShrink: 0 }} />
                ) : prov.tier === 'AI_VERIFIED' ? (
                  <Sparkles size={14} style={{ color: '#2b8a3e', flexShrink: 0 }} />
                ) : (
                  <AlertTriangle size={14} style={{ color: '#f08c00', flexShrink: 0 }} />
                )}
                <span>{prov.title}</span>
                <span
                  style={{
                    opacity: 0.8,
                    fontSize: '10.5px',
                    marginLeft: '2px',
                    background: 'rgba(0,0,0,0.04)',
                    padding: '1px 5px',
                    borderRadius: '4px',
                  }}
                >
                  ℹ️ {prov.hallucinationRisk}
                </span>
              </div>
            );
          })()}

          <div className="drawer-node-actions">
            {onExplore && (
              <Button variant="ghost" onClick={() => onExplore(node.data!.id)}>
                <Expand size={14} />
                {t('expand')}
              </Button>
            )}
            {scope && (
              <Button
                variant="ghost"
                onClick={() =>
                  useCanvasStore
                    .getState()
                    .togglePin(scope, node.data!.stableKey)
                }
              >
                <span>{pinned ? <PinOff size={14} /> : <Pin size={14} />}</span>
                {t(pinned ? 'unpin' : 'pin')}
              </Button>
            )}
          </div>
        </div>
      )}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '0 1rem',
          borderBottom: '1px solid var(--border)',
          background: 'var(--surface-subtle)',
          flexWrap: 'wrap',
          gap: '0.5rem',
        }}
      >
        <div
          className="tabs drawer-tabs"
          role="tablist"
          aria-label={t('evidence')}
          style={{ borderBottom: 'none', margin: 0 }}
        >
          {(['flow', 'source', 'properties', 'reviews'] as const)
            .filter((item) => {
              if (item === 'flow') return hasFlow;
              return selection.kind === 'nodes' || item === 'source';
            })
            .map((item) => (
              <button
                key={item}
                type="button"
                role="tab"
                aria-selected={tab === item}
                aria-controls={`drawer-${item}`}
                onClick={() => setTab(item)}
              >
                {t(item)}
              </button>
            ))}
        </div>
        <div
          style={{
            display: 'inline-flex',
            background: 'var(--surface, #1e293b)',
            borderRadius: '6px',
            padding: '2px',
            border: '1px solid var(--border, #334155)',
            margin: '4px 0',
          }}
        >
          <button
            type="button"
            onClick={() => setViewMode('business')}
            style={{
              padding: '3px 9px',
              fontSize: '11px',
              fontWeight: 600,
              borderRadius: '4px',
              border: 'none',
              background:
                viewMode === 'business'
                  ? 'var(--primary, #0e7466)'
                  : 'transparent',
              color: viewMode === 'business' ? '#fff' : 'var(--muted, #94a3b8)',
              cursor: 'pointer',
            }}
          >
            👔 Бизнес-вид
          </button>
          <button
            type="button"
            onClick={() => setViewMode('tech')}
            style={{
              padding: '3px 9px',
              fontSize: '11px',
              fontWeight: 600,
              borderRadius: '4px',
              border: 'none',
              background:
                viewMode === 'tech'
                  ? 'var(--primary, #0e7466)'
                  : 'transparent',
              color: viewMode === 'tech' ? '#fff' : 'var(--muted, #94a3b8)',
              cursor: 'pointer',
            }}
          >
            🛠 Технический вид
          </button>
        </div>
      </div>
      <div className="drawer-content" role="tabpanel" id={`drawer-${tab}`}>
        {node.isError && selection.kind === 'nodes' && (
          <ErrorState
            compact
            error={node.error}
            retry={() => void node.refetch()}
          />
        )}
        {tab === 'flow' && node.data && (
          <ScenarioFlow
            nodeId={node.data.id}
            label={node.data.label}
            method={node.data.properties?.httpMethod as string}
            path={node.data.properties?.path as string}
            members={node.data.scenarioMembers ?? []}
            assertions={node.data.assertions}
            enrichment={node.data.enrichment}
            viewMode={viewMode}
            onExplore={onExplore}
            onEnrichSuccess={() => void node.refetch()}
          />
        )}
        {tab === 'source' &&
          (evidence.isPending ? (
            <Loading />
          ) : evidence.isError ? (
            <ErrorState
              error={evidence.error}
              retry={() => void evidence.refetch()}
            />
          ) : (
            <SourceEvidence key={selection.id} evidence={evidence.data} />
          ))}
        {tab === 'properties' &&
          (node.isPending ? (
            <Loading />
          ) : (
            node.data && (
              <>
                <div className="drawer-section">
                  <h3>{t('properties')}</h3>
                  <dl className="property-list">
                    <dt>{t('stableKey')}</dt>
                    <dd className="mono">{node.data.stableKey}</dd>
                    <dt>{t('kind')}</dt>
                    <dd>{node.data.kind}</dd>
                  </dl>
                  <JsonView value={node.data.properties} />
                  {Boolean(node.data.facts?.length) && (
                    <JsonView value={node.data.facts} />
                  )}
                </div>
                <div className="drawer-section">
                  <h3>{t('assertions')}</h3>
                  {node.data.assertions?.length ? (
                    <JsonView value={node.data.assertions} />
                  ) : (
                    <p className="muted">{t('noAssertions')}</p>
                  )}
                </div>
                <div className="drawer-section">
                  <h3>{t('enrichments')}</h3>
                  {node.data.enrichments?.length ? (
                    <JsonView value={node.data.enrichments} />
                  ) : (
                    <p className="muted">{t('noEnrichments')}</p>
                  )}
                </div>
              </>
            )
          ))}
        {tab === 'reviews' &&
          (node.isPending ? (
            <Loading />
          ) : (
            node.data && (
              <div className="drawer-section">
                <ReviewForm key={node.data.id} node={node.data} />
                <h3>{t('reviews')}</h3>
                {node.data.reviews?.length ? (
                  <JsonView value={node.data.reviews} />
                ) : (
                  <p className="muted">{t('noReviews')}</p>
                )}
              </div>
            )
          ))}
      </div>
    </aside>
  );
}
