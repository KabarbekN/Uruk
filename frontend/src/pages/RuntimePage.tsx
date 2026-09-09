import { useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { Activity } from 'lucide-react';
import { useRun } from '../shared/api/queries';
import { ApiError } from '../shared/api/client';
import { useT } from '../shared/lib/i18n';
import {
  Badge,
  Button,
  EmptyState,
  ErrorState,
  JsonView,
  Loading,
  PageHeader,
} from '../shared/ui';
import { EvidenceDrawer } from '../features/evidence-drawer/EvidenceDrawer';
import type { Selection } from '../features/semantic-canvas/store';
import {
  MAX_TRACE_BYTES,
  PAGE_SIZE,
  durationMilliseconds,
  runtimeApi,
} from '../features/runtime-evidence/api';
import { runtimeText } from '../features/runtime-evidence/text';
import '../features/runtime-evidence/runtime.css';

function RuntimeEvidence({
  runId,
  canImport,
}: {
  runId: string;
  canImport: boolean;
}) {
  const { t, locale } = useT();
  const text = runtimeText[locale];
  const client = useQueryClient();
  const [offset, setOffset] = useState(0);
  const [file, setFile] = useState<File | null>(null);
  const [inputError, setInputError] = useState('');
  const [selection, select] = useState<Selection | null>(null);
  const input = useRef<HTMLInputElement>(null);
  const runtime = useQuery({
    queryKey: ['runtime', runId, offset],
    queryFn: ({ signal }) => runtimeApi.get(runId, offset, signal),
  });
  const upload = useMutation({
    mutationFn: async (chosen: File) => {
      if (chosen.size > MAX_TRACE_BYTES)
        throw new ApiError(400, { detail: text.tooLarge });
      const body = await chosen.text();
      let parsed: unknown;
      try {
        parsed = JSON.parse(body);
      } catch {
        throw new ApiError(400, { detail: text.invalid });
      }
      if (
        !parsed ||
        typeof parsed !== 'object' ||
        !('resourceSpans' in parsed) ||
        !Array.isArray(parsed.resourceSpans)
      )
        throw new ApiError(400, { detail: text.invalid });
      return runtimeApi.ingest(runId, body);
    },
    onSuccess: async () => {
      setOffset(0);
      setFile(null);
      if (input.current) input.current.value = '';
      await client.invalidateQueries({ queryKey: ['runtime', runId] });
    },
  });
  const openNode = (id: string, label: string) =>
    select({ kind: 'nodes', id, label });
  return (
    <>
      <p className="muted">{text.explanation}</p>
      <form
        className="runtime-import"
        onSubmit={(event) => {
          event.preventDefault();
          if (file && canImport) {
            setInputError('');
            upload.mutate(file);
          }
        }}
      >
        <label htmlFor="runtime-file">{text.file}</label>
        <input
          id="runtime-file"
          ref={input}
          type="file"
          accept=".json,application/json"
          disabled={!canImport || upload.isPending}
          aria-describedby="runtime-upload-help"
          onChange={(event) => {
            const chosen = event.target.files?.[0] ?? null;
            upload.reset();
            setInputError(
              chosen && chosen.size > MAX_TRACE_BYTES ? text.tooLarge : '',
            );
            setFile(chosen && chosen.size <= MAX_TRACE_BYTES ? chosen : null);
          }}
        />
        <Button
          type="submit"
          variant="primary"
          disabled={!canImport || !file}
          busy={upload.isPending}
        >
          {text.upload}
        </Button>
        <p id="runtime-upload-help" className="muted small">
          {text.uploadHelp}
        </p>
      </form>
      {!canImport && <p role="status">{text.wait}</p>}
      {inputError && <p role="alert">{inputError}</p>}
      {upload.isError && <ErrorState error={upload.error} compact />}
      {upload.isSuccess && (
        <p role="status">
          {text.accepted}: {upload.data.acceptedSpans}; {text.duplicates}:{' '}
          {upload.data.duplicateSpans}; {text.matched}:{' '}
          {upload.data.matchedSpans}; {text.unresolved}:{' '}
          {upload.data.unresolvedSpans}
        </p>
      )}
      <div className="section-toolbar">
        <h2>{text.title}</h2>
        <Button
          disabled={runtime.isFetching}
          onClick={() => void runtime.refetch()}
        >
          {t('refresh')}
        </Button>
      </div>
      {runtime.isPending ? (
        <Loading />
      ) : runtime.isError ? (
        <ErrorState
          error={runtime.error}
          retry={() => void runtime.refetch()}
        />
      ) : (
        <>
          <dl className="runtime-summary">
            {(
              [
                ['total', runtime.data.summary.totalSpans],
                ['traces', runtime.data.summary.traceCount],
                ['matched', runtime.data.summary.matchedSpans],
                ['unresolved', runtime.data.summary.unresolvedSpans],
                ['observed', runtime.data.summary.observedNodes],
                ['unobserved', runtime.data.summary.notObservedNodes],
              ] as const
            ).map(([key, count]) => (
              <div key={key}>
                <dt>{text[key]}</dt>
                <dd>{count}</dd>
              </div>
            ))}
          </dl>
          {runtime.data.summary.totalSpans === 0 ? (
            <EmptyState
              icon={Activity}
              title={text.empty}
              detail={text.emptyHelp}
            />
          ) : (
            <>
              <div className="table-wrap">
                <table className="runtime-table">
                  <thead>
                    <tr>
                      <th>{text.span}</th>
                      <th>{text.match}</th>
                      <th>{text.duration}</th>
                      <th>{text.source}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {runtime.data.spans.map((span) => (
                      <tr key={span.id}>
                        <td>
                          <strong>{span.name}</strong>
                          <small className="muted">
                            {text[`kind${span.kind}` as 'kind0']}
                          </small>
                          <details>
                            <summary>{text.details}</summary>
                            <div className="small mono break-word">
                              {span.traceId} / {span.spanId}
                            </div>
                            <p>
                              {text.parent}:{' '}
                              {span.parentSpanId || text.noParent}
                            </p>
                            <JsonView
                              value={{
                                attributes: span.attributes,
                                resourceAttributes: span.resourceAttributes,
                                status: span.status,
                                events: span.events,
                                links: span.links,
                                scope: span.scope,
                              }}
                            />
                          </details>
                        </td>
                        <td>
                          <Badge
                            tone={span.matchedNodeId ? 'positive' : 'warning'}
                          >
                            {text[span.matchStatus]}
                          </Badge>
                        </td>
                        <td className="mono">
                          {durationMilliseconds(span.startNanos, span.endNanos)}
                        </td>
                        <td>
                          {span.matchedNodeId ? (
                            <Button
                              onClick={() =>
                                openNode(span.matchedNodeId!, span.name)
                              }
                            >
                              {text.source}
                            </Button>
                          ) : (
                            <span className="muted">{text.noMatch}</span>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <nav className="runtime-pagination" aria-label={text.title}>
                <Button
                  disabled={offset === 0 || runtime.isFetching}
                  onClick={() => setOffset(Math.max(0, offset - PAGE_SIZE))}
                >
                  {t('previousPage')}
                </Button>
                <span>
                  {text.page} {Math.floor(offset / PAGE_SIZE) + 1}
                </span>
                <Button
                  disabled={!runtime.data.hasMore || runtime.isFetching}
                  onClick={() => setOffset(offset + PAGE_SIZE)}
                >
                  {t('nextPage')}
                </Button>
              </nav>
            </>
          )}
          {runtime.data.observedPaths.length > 0 && (
            <section>
              <h2>{text.paths}</h2>
              {runtime.data.pathsTruncated && (
                <p role="status">{text.pathsLimited}</p>
              )}
              <ul className="runtime-paths">
                {runtime.data.observedPaths.map((edge) => (
                  <li key={`${edge.sourceNodeId}:${edge.targetNodeId}`}>
                    <Button
                      onClick={() =>
                        openNode(edge.sourceNodeId, edge.sourceNodeId)
                      }
                    >
                      {text.from} {edge.sourceNodeId.slice(0, 8)}
                    </Button>
                    <span aria-hidden="true">→</span>
                    <Button
                      onClick={() =>
                        openNode(edge.targetNodeId, edge.targetNodeId)
                      }
                    >
                      {text.to} {edge.targetNodeId.slice(0, 8)}
                    </Button>
                  </li>
                ))}
              </ul>
            </section>
          )}
        </>
      )}
      {selection && (
        <EvidenceDrawer
          key={selection.id}
          selection={selection}
          onClose={() => select(null)}
        />
      )}
    </>
  );
}

export default function RuntimePage() {
  const { analysisRunId = '' } = useParams();
  const { locale } = useT();
  const run = useRun(analysisRunId);
  if (run.isPending) return <Loading />;
  if (run.isError)
    return <ErrorState error={run.error} retry={() => void run.refetch()} />;
  return (
    <div className="page runtime-page">
      <PageHeader
        title={runtimeText[locale].title}
        eyebrow={run.data.revision?.slice(0, 12) || analysisRunId.slice(0, 8)}
      />
      <RuntimeEvidence
        key={analysisRunId}
        runId={analysisRunId}
        canImport={['SUCCEEDED', 'PARTIALLY_SUCCEEDED'].includes(
          run.data.status,
        )}
      />
    </div>
  );
}
