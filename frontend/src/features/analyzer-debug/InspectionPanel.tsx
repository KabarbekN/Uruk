import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  ChevronLeft,
  ChevronRight,
  Download,
  FileSearch,
  RefreshCw,
} from 'lucide-react';
import { api, type InspectionResource } from '../../shared/api/client';
import { useT } from '../../shared/lib/i18n';
import {
  Badge,
  EmptyState,
  ErrorState,
  IconButton,
  JsonView,
  Loading,
  SearchInput,
} from '../../shared/ui';

function display(value: unknown): string {
  return typeof value === 'object'
    ? JSON.stringify(value)
    : String(value ?? '\u2014');
}

export function InspectionPanel({
  runId,
  resource,
}: {
  runId: string;
  resource: InspectionResource;
}) {
  const { t } = useT();
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const query = useQuery({
    queryKey: ['inspection', runId, resource],
    queryFn: ({ signal }) => api.inspect(runId, resource, signal),
  });
  const rows = useMemo(() => {
    const data = query.data;
    if (!data) return [];
    return (
      Array.isArray(data)
        ? data.map((value, index) => ({ key: String(index + 1), value }))
        : Object.entries(data).map(([key, value]) => ({ key, value }))
    ).filter((row) =>
      `${row.key} ${JSON.stringify(row.value)}`
        .toLowerCase()
        .includes(search.toLowerCase()),
    );
  }, [query.data, search]);
  const exportJson = () => {
    const url = URL.createObjectURL(
      new Blob([JSON.stringify(query.data, null, 2)], {
        type: 'application/json',
      }),
    );
    const link = document.createElement('a');
    link.href = url;
    link.download = `${resource}-${runId}.json`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  };
  const maxPage = Math.max(0, Math.ceil(rows.length / 50) - 1);
  const activePage = Math.min(page, maxPage);
  return (
    <div className="inspection-panel">
      <div className="section-toolbar">
        <SearchInput
          label={t('searchRecords')}
          value={search}
          onChange={(value) => {
            setSearch(value);
            setPage(0);
          }}
        />
        <div className="row-actions">
          <Badge>
            {rows.length} {t('records')}
          </Badge>
          <IconButton
            icon={Download}
            label={t('download')}
            disabled={!query.data}
            onClick={exportJson}
          />
          <IconButton
            icon={RefreshCw}
            label={t('refresh')}
            disabled={query.isFetching}
            onClick={() => void query.refetch()}
          />
        </div>
      </div>
      {query.isPending ? (
        <Loading />
      ) : query.isError ? (
        <ErrorState error={query.error} retry={() => void query.refetch()} />
      ) : !rows.length ? (
        <EmptyState
          icon={FileSearch}
          title={t(search ? 'noMatches' : 'noData')}
        />
      ) : (
        <>
          <div className="inspection-rows">
            {rows.slice(activePage * 50, (activePage + 1) * 50).map((row) => (
              <div key={row.key} className="inspection-row">
                <span className="inspection-key mono">{row.key}</span>
                {row.value && typeof row.value === 'object' ? (
                  <details>
                    <summary>
                      {Array.isArray(row.value)
                        ? `${row.value.length} ${t('records')}`
                        : Object.entries(row.value)
                            .slice(0, 4)
                            .map(
                              ([key, value]) =>
                                `${key}: ${display(value).slice(0, 100)}`,
                            )
                            .join(' / ')}
                    </summary>
                    <JsonView value={row.value} label={t('rawData')} />
                  </details>
                ) : (
                  <span className="break-word">{display(row.value)}</span>
                )}
              </div>
            ))}
          </div>
          {maxPage > 0 && (
            <div className="pagination">
              <IconButton
                icon={ChevronLeft}
                label={t('back')}
                disabled={activePage === 0}
                onClick={() => setPage(activePage - 1)}
              />
              <span>
                {activePage + 1} / {maxPage + 1}
              </span>
              <IconButton
                icon={ChevronRight}
                label={t('viewAll')}
                disabled={activePage === maxPage}
                onClick={() => setPage(activePage + 1)}
              />
            </div>
          )}
        </>
      )}
    </div>
  );
}
