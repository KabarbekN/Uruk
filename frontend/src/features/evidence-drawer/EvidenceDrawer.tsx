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
  CheckCircle2,
  Copy,
  Expand,
  FileCode2,
  Pin,
  PinOff,
  ShieldAlert,
  X,
} from 'lucide-react';
import { api } from '../../shared/api/client';
import type { Evidence } from '../../shared/api/types';
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

const CodeViewer = lazy(() => import('./CodeViewer'));

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
                {t('lines')} {item.startLine}-{item.endLine}
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
          {current.filePath}:{current.startLine}-{current.endLine}
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
      {current.sourceAvailable === false || current.snippet === null ? (
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
            snippet={current.snippet}
            filePath={current.filePath}
            startLine={current.startLine}
            endLine={current.endLine}
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
  const [tab, setTab] = useState<'source' | 'properties' | 'reviews'>('source');
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
      if (previous instanceof HTMLElement && previous.isConnected)
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
        className="tabs drawer-tabs"
        role="tablist"
        aria-label={t('evidence')}
      >
        {(['source', 'properties', 'reviews'] as const)
          .filter((item) => selection.kind === 'nodes' || item === 'source')
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
      <div className="drawer-content" role="tabpanel" id={`drawer-${tab}`}>
        {node.isError && selection.kind === 'nodes' && (
          <ErrorState
            compact
            error={node.error}
            retry={() => void node.refetch()}
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
