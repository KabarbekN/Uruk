import { useMemo, useState, type FormEvent } from 'react';
import {
  AlertTriangle,
  ArrowRight,
  Bot,
  CheckCircle2,
  Database,
  GitBranch,
  Network,
  Search,
  ShieldCheck,
  Sparkles,
  Workflow,
} from 'lucide-react';
import type { CanvasProjection, SemanticNode } from '../../shared/api/types';
import { Badge, Button } from '../../shared/ui';
import { useT } from '../../shared/lib/i18n';

type InsightFilter = 'all' | 'rules' | 'data' | 'risks' | 'ai';

function isRule(node: SemanticNode) {
  return /RULE|CONDITION|VALIDATION|AUTHORIZATION/.test(node.kind);
}

function isDataEffect(node: SemanticNode) {
  return (
    /DATABASE_(WRITE|READ)|TABLE|COLUMN|TRANSACTION/.test(node.kind) ||
    node.badges.some((badge) => /DB_WRITE|DATA/.test(badge))
  );
}

function isRisk(node: SemanticNode) {
  return (
    node.confidence < 0.7 ||
    (isAi(node) && /UNREVIEWED|STALE|NEEDS_REVIEW/.test(node.reviewStatus)) ||
    node.badges.some((badge) => /UNRESOLVED|CONFLICT|STALE/.test(badge))
  );
}

function isAi(node: SemanticNode) {
  return Boolean(node.aiLabel || node.badges.includes('AI_ENRICHED'));
}

function trust(node: SemanticNode) {
  if (/CONFIRMED|EDITED/.test(node.reviewStatus)) {
    return { label: 'Проверено человеком', tone: 'positive' as const };
  }
  if (isAi(node)) {
    return {
      label: 'Формулировка ИИ · нужна проверка',
      tone: 'warning' as const,
    };
  }
  if (node.evidenceCount > 0 && /PROVEN|SUPPORTED/.test(node.supportLevel)) {
    return { label: 'Подтверждено кодом', tone: 'positive' as const };
  }
  if (node.evidenceCount > 0) {
    return { label: 'Выведено из фактов', tone: 'info' as const };
  }
  return { label: 'Недостаточно доказательств', tone: 'warning' as const };
}

function score(node: SemanticNode) {
  let value = 0;
  if (node.kind === 'ENDPOINT') value += 6;
  if (isRule(node)) value += 5;
  if (isDataEffect(node)) value += 4;
  if (isRisk(node)) value += 3;
  if (isAi(node)) value += 2;
  value += Math.min(2, node.evidenceCount);
  return value;
}

export function AnalysisOverview({
  projection,
  onSelect,
  onShowControllers,
  onShowGraph,
}: {
  projection: CanvasProjection;
  onSelect: (node: SemanticNode) => void;
  onShowControllers: () => void;
  onShowGraph: () => void;
}) {
  const { t } = useT();
  const [question, setQuestion] = useState('');
  const [submittedQuestion, setSubmittedQuestion] = useState('');
  const [filter, setFilter] = useState<InsightFilter>('all');
  const [showAll, setShowAll] = useState(false);
  const nodes = projection.nodes;
  const metrics = useMemo(() => {
    const rules = nodes.filter(isRule).length;
    const dataEffects = nodes.filter(isDataEffect).length;
    const risks = nodes.filter(isRisk).length;
    const ai = nodes.filter(isAi).length;
    const grounded = nodes.filter((node) => node.evidenceCount > 0).length;
    return {
      rules,
      dataEffects,
      risks,
      ai,
      grounded,
      coverage: nodes.length ? Math.round((grounded / nodes.length) * 100) : 0,
    };
  }, [nodes]);

  const results = useMemo(() => {
    const terms = submittedQuestion
      .toLocaleLowerCase('ru')
      .split(/\s+/)
      .filter((term) => term.length > 2);
    return [...nodes]
      .filter((node) => {
        if (filter === 'rules' && !isRule(node)) return false;
        if (filter === 'data' && !isDataEffect(node)) return false;
        if (filter === 'risks' && !isRisk(node)) return false;
        if (filter === 'ai' && !isAi(node)) return false;
        if (!terms.length) return true;
        const haystack = [
          node.label,
          node.subtitle,
          node.aiLabel,
          node.aiSubtitle,
          node.kind,
          node.stableKey,
          JSON.stringify(node.properties),
        ]
          .filter(Boolean)
          .join(' ')
          .toLocaleLowerCase('ru');
        return terms.some((term) => haystack.includes(term));
      })
      .sort((a, b) => score(b) - score(a));
  }, [filter, nodes, submittedQuestion]);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    setSubmittedQuestion(question.trim());
    setShowAll(false);
  };

  const filters: Array<{
    id: InsightFilter;
    label: string;
    value: number;
    icon: typeof Workflow;
  }> = [
    {
      id: 'all',
      label: 'В текущей проекции',
      value: nodes.length,
      icon: Workflow,
    },
    { id: 'rules', label: 'Правила', value: metrics.rules, icon: ShieldCheck },
    {
      id: 'data',
      label: 'Работа с данными',
      value: metrics.dataEffects,
      icon: Database,
    },
    {
      id: 'risks',
      label: 'Нужна проверка',
      value: metrics.risks,
      icon: AlertTriangle,
    },
    { id: 'ai', label: 'Объяснения ИИ', value: metrics.ai, icon: Sparkles },
  ];

  return (
    <div className="analysis-overview">
      <header className="overview-hero">
        <div>
          <span className="eyebrow">Результат семантического анализа</span>
          <h1>Что обнаружено</h1>
          <p>
            Начните с важных сценариев и правил. Любое утверждение можно
            раскрыть до доказательства и строки кода.
          </p>
        </div>
        <div
          className="overview-coverage"
          aria-label={`Покрытие ${metrics.coverage}%`}
        >
          <strong>{metrics.coverage}%</strong>
          <span>узлов с доказательствами</span>
          <small>
            {metrics.grounded} из {nodes.length} в текущей проекции
          </small>
        </div>
      </header>

      {projection.truncated && (
        <div className="notice warning overview-notice">
          <AlertTriangle size={16} />
          <span>
            Показана приоритетная выборка из {projection.totalNodes} узлов. Для
            полного исследования используйте каталог, поиск или граф.
          </span>
        </div>
      )}

      <div className="insight-metrics" role="list" aria-label="Сводка анализа">
        {filters.map((item) => (
          <button
            type="button"
            role="listitem"
            key={item.id}
            className={filter === item.id ? 'active' : ''}
            onClick={() => {
              setFilter(item.id);
              setShowAll(false);
            }}
          >
            <item.icon size={18} />
            <span>{item.label}</span>
            <strong>{item.value}</strong>
          </button>
        ))}
      </div>

      <section className="question-panel">
        <div className="question-heading">
          <Bot size={22} />
          <div>
            <h2>Найдите ответ в проекте</h2>
            <p>
              Поиск идет по фактам текущего анализа и показывает уровень
              доверия.
            </p>
          </div>
        </div>
        <form className="question-form" onSubmit={submit}>
          <Search size={18} aria-hidden="true" />
          <input
            value={question}
            onChange={(event) => setQuestion(event.target.value)}
            placeholder="Например: кто может отменить поездку или что меняет статус"
            aria-label="Вопрос о бизнес-логике проекта"
          />
          <Button type="submit" variant="primary">
            Найти
            <ArrowRight size={15} />
          </Button>
        </form>
        <div className="question-suggestions">
          {[
            'авторизация',
            'изменение статуса',
            'запись данных',
            'внешний вызов',
          ].map((suggestion) => (
            <button
              type="button"
              key={suggestion}
              onClick={() => {
                setQuestion(suggestion);
                setSubmittedQuestion(suggestion);
                setFilter('all');
                setShowAll(false);
              }}
            >
              {suggestion}
            </button>
          ))}
        </div>
      </section>

      <section className="insights-section">
        <div className="insights-heading">
          <div>
            <span className="eyebrow">
              {submittedQuestion
                ? `Результаты по запросу «${submittedQuestion}»`
                : 'Приоритет для чтения'}
            </span>
            <h2>
              {submittedQuestion
                ? 'Найденные факты и сценарии'
                : 'Ключевые элементы анализа'}
            </h2>
          </div>
          <div className="heading-actions">
            <Button onClick={onShowControllers}>
              <GitBranch size={15} />
              Каталог API
            </Button>
            <Button onClick={onShowGraph}>
              <Network size={15} />
              Исследовать зависимости
            </Button>
          </div>
        </div>

        {!results.length ? (
          <div className="overview-empty">
            <Search size={24} />
            <strong>Совпадений в текущей проекции нет</strong>
            <span>
              Попробуйте более короткую формулировку или откройте каталог API.
            </span>
          </div>
        ) : (
          <div className="insight-grid">
            {results.slice(0, showAll ? 40 : 8).map((node) => {
              const nodeTrust = trust(node);
              return (
                <button
                  type="button"
                  className="insight-card"
                  key={node.id}
                  onClick={() => onSelect(node)}
                >
                  <div className="insight-card-top">
                    <Badge>{node.kind.replaceAll('_', ' ')}</Badge>
                    <Badge tone={nodeTrust.tone}>{nodeTrust.label}</Badge>
                  </div>
                  <strong>{node.label}</strong>
                  <p>
                    {node.subtitle ||
                      node.aiSubtitle ||
                      'Откройте карточку, чтобы увидеть логику и доказательства.'}
                  </p>
                  <footer>
                    <span>
                      <CheckCircle2 size={13} /> {node.evidenceCount}{' '}
                      доказательств
                    </span>
                    <span>{Math.round(node.confidence * 100)}%</span>
                    <ArrowRight size={15} />
                  </footer>
                </button>
              );
            })}
          </div>
        )}
        {results.length > 8 && (
          <Button
            className="overview-more"
            onClick={() => setShowAll(!showAll)}
          >
            {showAll
              ? 'Показать главное'
              : `Показать еще ${Math.min(32, results.length - 8)}`}
          </Button>
        )}
      </section>
      <p className="overview-footnote">
        {t('total')}: {projection.totalNodes}. Процент уверенности отражает
        качество извлечения, а маркер доверия — происхождение и проверку
        формулировки.
      </p>
    </div>
  );
}
