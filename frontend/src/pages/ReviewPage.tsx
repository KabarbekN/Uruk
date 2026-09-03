import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { CheckCheck, FileCheck2 } from 'lucide-react';
import { api } from '../shared/api/client';
import { useT } from '../shared/lib/i18n';
import {
  Badge,
  Button,
  EmptyState,
  ErrorState,
  Loading,
  PageHeader,
  SearchInput,
  Status,
} from '../shared/ui';
import { EvidenceDrawer } from '../features/evidence-drawer/EvidenceDrawer';
import type { Selection } from '../features/semantic-canvas/store';

export default function ReviewPage() {
  const { projectId = '' } = useParams();
  const { t, label } = useT();
  const [search, setSearch] = useState('');
  const [selected, setSelected] = useState<Selection | null>(null);
  const query = useQuery({
    queryKey: ['review-queue', projectId],
    queryFn: ({ signal }) => api.reviewQueue(projectId, signal),
  });
  const nodes =
    query.data?.filter((node) =>
      `${node.label} ${node.subtitle} ${node.kind}`
        .toLowerCase()
        .includes(search.toLowerCase()),
    ) ?? [];
  return (
    <div className="split-page">
      <div className="page split-main">
        <PageHeader title={t('review')} eyebrow={t('workspace')} />
        <div className="section-toolbar">
          <div className="section-title">
            <FileCheck2 size={17} />
            <strong>{t('reviewItems')}</strong>
            {query.data && <Badge>{query.data.length}</Badge>}
          </div>
          <SearchInput
            label={t('search')}
            value={search}
            onChange={setSearch}
          />
        </div>
        {query.isPending ? (
          <Loading />
        ) : query.isError ? (
          <ErrorState error={query.error} retry={() => void query.refetch()} />
        ) : !nodes.length ? (
          <EmptyState
            icon={CheckCheck}
            title={t(query.data.length ? 'noMatches' : 'noReview')}
          />
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>{t('statement')}</th>
                  <th>{t('confidence')}</th>
                  <th>{t('evidence')}</th>
                  <th>{t('reviewStatus')}</th>
                  <th>{t('actions')}</th>
                </tr>
              </thead>
              <tbody>
                {nodes.map((node) => (
                  <tr
                    key={node.id}
                    className={selected?.id === node.id ? 'selected' : ''}
                  >
                    <td>
                      <button
                        className="table-link"
                        onClick={() =>
                          setSelected({
                            kind: 'nodes',
                            id: node.id,
                            label: node.label,
                          })
                        }
                      >
                        {node.label}
                      </button>
                      <small className="muted">{label(node.kind)}</small>
                    </td>
                    <td>{Math.round(node.confidence * 100)}%</td>
                    <td>{node.evidenceCount}</td>
                    <td>
                      <Status value={node.reviewStatus} />
                    </td>
                    <td>
                      <Button
                        onClick={() =>
                          setSelected({
                            kind: 'nodes',
                            id: node.id,
                            label: node.label,
                          })
                        }
                      >
                        <FileCheck2 size={14} />
                        {t('inspect')}
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
      {selected && (
        <EvidenceDrawer
          key={selected.id}
          selection={selected}
          onClose={() => setSelected(null)}
        />
      )}
    </div>
  );
}
