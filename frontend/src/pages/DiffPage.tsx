import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { ArrowRight, GitCompareArrows } from 'lucide-react';
import { api } from '../shared/api/client';
import { useRun, useRuns } from '../shared/api/queries';
import type { SemanticChange } from '../shared/api/types';
import { useT } from '../shared/lib/i18n';
import {
  Badge,
  EmptyState,
  ErrorState,
  Field,
  JsonView,
  Loading,
  PageHeader,
  SearchInput,
  Status,
} from '../shared/ui';

function sourceKey(snapshot: unknown, fallback: string): string | null {
  if (snapshot === null || snapshot === undefined) return null;
  if (typeof snapshot !== 'object' || Array.isArray(snapshot)) return fallback;
  const value = snapshot as Record<string, unknown>;
  const stableKey =
    typeof value.stableKey === 'string' && value.stableKey
      ? value.stableKey
      : fallback;
  const properties = value.properties;
  if (
    stableKey.startsWith('edge:') &&
    typeof properties === 'object' &&
    properties !== null &&
    !Array.isArray(properties)
  ) {
    const source = (properties as Record<string, unknown>).sourceKey;
    if (typeof source === 'string' && source) return source;
  }
  return stableKey;
}

function ChangeDetails({
  change,
  from,
  to,
}: {
  change: SemanticChange;
  from: string;
  to: string;
}) {
  const { t } = useT();
  return (
    <div className="diff-sides">
      {(
        [
          {
            key: 'before',
            value: change.before,
            ids: change.beforeEvidenceIds,
            run: from,
          },
          {
            key: 'after',
            value: change.after,
            ids: change.afterEvidenceIds,
            run: to,
          },
        ] as const
      ).map((side) => {
        const searchKey = sourceKey(side.value, change.subjectStableKey);
        return (
          <section key={side.key} className={`diff-side ${side.key}`}>
            <header>
              <h3>{t(side.key)}</h3>
              {searchKey && (
                <Link
                  className="text-link"
                  to={`/analyses/${side.run}/canvas?search=${encodeURIComponent(searchKey)}`}
                >
                  {t(side.key === 'before' ? 'openBefore' : 'openAfter')}
                  <ArrowRight size={13} />
                </Link>
              )}
            </header>
            <JsonView value={side.value} label={t(side.key)} />
            <h4>{t('evidenceReferences')}</h4>
            {side.ids.length ? (
              <ul className="reference-list">
                {side.ids.map((id) => (
                  <li className="mono" key={id}>
                    {id}
                  </li>
                ))}
              </ul>
            ) : (
              <span className="muted">{t('noEvidence')}</span>
            )}
          </section>
        );
      })}
    </div>
  );
}

export default function DiffPage() {
  const { analysisRunId = '' } = useParams();
  return <RunDiff key={analysisRunId} analysisRunId={analysisRunId} />;
}

function RunDiff({ analysisRunId }: { analysisRunId: string }) {
  const { t, date, label } = useT();
  const run = useRun(analysisRunId);
  const runs = useRuns(run.data?.projectId);
  const [fromChoice, setFrom] = useState<string | null>(null);
  const [toChoice, setTo] = useState('');
  const [search, setSearch] = useState('');
  const completedRuns = (runs.data ?? []).filter(
    (item) =>
      item.status === 'SUCCEEDED' || item.status === 'PARTIALLY_SUCCEEDED',
  );
  const to = toChoice || analysisRunId;
  const target =
    runs.data?.find((item) => item.id === to) ??
    (run.data?.id === to ? run.data : undefined);
  const from =
    fromChoice ??
    ([...completedRuns]
      .filter(
        (item) =>
          target &&
          item.id !== to &&
          Date.parse(item.createdAt) < Date.parse(target.createdAt),
      )
      .sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))[0]
      ?.id ||
      '');
  const query = useQuery({
    queryKey: ['diff', run.data?.projectId, from, to],
    queryFn: ({ signal }) => api.diff(run.data!.projectId, from, to, signal),
    enabled: Boolean(
      run.data &&
      from !== to &&
      completedRuns.some((item) => item.id === from) &&
      completedRuns.some((item) => item.id === to),
    ),
  });
  const changes =
    query.data?.filter((change) =>
      `${change.subjectStableKey} ${change.changeType} ${change.impactType}`
        .toLowerCase()
        .includes(search.toLowerCase()),
    ) ?? [];
  if (run.isPending) return <Loading />;
  if (run.isError)
    return <ErrorState error={run.error} retry={() => void run.refetch()} />;
  if (runs.isError)
    return <ErrorState error={runs.error} retry={() => void runs.refetch()} />;
  if (runs.isPending) return <Loading />;
  return (
    <div className="page">
      <PageHeader title={t('diff')} eyebrow={t('compare')} />
      <div className="compare-bar">
        <Field label={t('fromRun')}>
          <select
            value={from}
            onChange={(event) => setFrom(event.target.value)}
          >
            <option value="">{t('noBaseline')}</option>
            {completedRuns.map((item) => (
              <option key={item.id} value={item.id}>
                {item.revision?.slice(0, 12) || item.id.slice(0, 8)} /{' '}
                {date(item.createdAt)}
              </option>
            ))}
          </select>
        </Field>
        <ArrowRight size={18} />
        <Field label={t('toRun')}>
          <select value={to} onChange={(event) => setTo(event.target.value)}>
            {!completedRuns.some((item) => item.id === to) && (
              <option value={to} disabled>
                {t('chooseRuns')}
              </option>
            )}
            {completedRuns.map((item) => (
              <option key={item.id} value={item.id}>
                {item.revision?.slice(0, 12) || item.id.slice(0, 8)} /{' '}
                {date(item.createdAt)}
              </option>
            ))}
          </select>
        </Field>
      </div>
      {completedRuns.length < 2 ? (
        <EmptyState icon={GitCompareArrows} title={t('twoRuns')} />
      ) : !from ||
        from === to ||
        !completedRuns.some((item) => item.id === to) ? (
        <EmptyState icon={GitCompareArrows} title={t('chooseRuns')} />
      ) : query.isPending ? (
        <Loading />
      ) : query.isError ? (
        <ErrorState error={query.error} retry={() => void query.refetch()} />
      ) : (
        <>
          <div className="section-toolbar">
            <div className="section-title">
              <strong>{t('changes')}</strong>
              <Badge>{query.data.length}</Badge>
            </div>
            <SearchInput
              label={t('search')}
              value={search}
              onChange={setSearch}
            />
          </div>
          {!changes.length ? (
            <EmptyState
              icon={GitCompareArrows}
              title={t(search ? 'noMatches' : 'noChanges')}
            />
          ) : (
            <div className="change-list">
              {changes.map((change) => (
                <details key={change.id} className="change-item">
                  <summary>
                    <Status value={change.changeType} />
                    <span className="change-subject">
                      <strong>{change.subjectStableKey}</strong>
                      <small>{label(change.impactType)}</small>
                    </span>
                    <Badge>{Math.round(change.confidence * 100)}%</Badge>
                  </summary>
                  <ChangeDetails change={change} from={from} to={to} />
                </details>
              ))}
            </div>
          )}
        </>
      )}
    </div>
  );
}
