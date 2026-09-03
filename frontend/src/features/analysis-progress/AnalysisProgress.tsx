import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Activity,
  AlertTriangle,
  ChevronDown,
  Square,
  Wifi,
} from 'lucide-react';
import { Link } from 'react-router-dom';
import { api } from '../../shared/api/client';
import { isTerminal } from '../../shared/api/queries';
import type { AnalysisRun } from '../../shared/api/types';
import { useAnalysisEvents } from '../../shared/hooks/useAnalysisEvents';
import { useT } from '../../shared/lib/i18n';
import { Badge, Button, ErrorState, Status } from '../../shared/ui';
import { notify } from '../../shared/ui/notifications';

export function AnalysisProgress({ run }: { run: AnalysisRun }) {
  const { t, label, date } = useT();
  const terminal = isTerminal(run.status);
  const { events, connection } = useAnalysisEvents(run.id, !terminal);
  const [expanded, setExpanded] = useState(false);
  const client = useQueryClient();
  const cancel = useMutation({
    mutationFn: () => api.cancelRun(run.id),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['run', run.id] });
      void client.invalidateQueries({ queryKey: ['runs'] });
      notify(t('cancelRequested'));
    },
  });
  const lastEvent = events[events.length - 1];
  const progress = terminal
    ? run.progress
    : Math.max(run.progress, lastEvent?.progress ?? 0);
  const connectionLabel =
    connection === 'invalid' ? t('invalidStream') : t(connection);
  return (
    <section className="analysis-progress" aria-label={t('progress')}>
      <div className="progress-summary">
        <button
          className="activity-toggle"
          aria-expanded={expanded}
          onClick={() => setExpanded(!expanded)}
        >
          <Activity size={16} />
          <span className="mono">
            {run.revision?.slice(0, 10) || run.id.slice(0, 8)}
          </span>
          <ChevronDown size={14} className={expanded ? 'rotate' : ''} />
        </button>
        <Status value={run.status} />
        <span className="stage-text">{label(run.currentStage)}</span>
        <div className="run-progress">
          <progress max={100} value={progress} aria-label={t('progress')} />
          <span>{Math.round(progress)}%</span>
        </div>
        {!terminal && (
          <>
            <Badge tone={connection === 'live' ? 'positive' : 'warning'}>
              <Wifi size={12} />
              {connectionLabel}
            </Badge>
            <Button
              variant="ghost"
              busy={cancel.isPending}
              disabled={cancel.isSuccess}
              onClick={() => cancel.mutate()}
            >
              <Square size={13} />
              {t('cancelAnalysis')}
            </Button>
          </>
        )}
      </div>
      {cancel.isError && <ErrorState compact error={cancel.error} />}
      {(run.status === 'PARTIALLY_SUCCEEDED' || run.status === 'FAILED') && (
        <div
          className={`notice ${run.status === 'FAILED' ? 'negative' : 'warning'}`}
        >
          <AlertTriangle size={16} />
          <span>
            {t(run.status === 'FAILED' ? 'failedWarning' : 'partialWarning')}
          </span>
          <Link to={`/analyses/${run.id}/debug`}>{t('diagnostics')}</Link>
        </div>
      )}
      {expanded && (
        <div className="event-log">
          <div className="section-title">
            <strong>{t('activity')}</strong>
            <span className="muted">
              {date(run.createdAt)}
              {run.finishedAt && ` / ${date(run.finishedAt)}`}
            </span>
          </div>
          {!events.length ? (
            <p className="muted">
              {terminal ? label(run.currentStage) : t('awaitingEvents')}
            </p>
          ) : (
            <ol>
              {[...events].reverse().map((event) => (
                <li key={event.sequenceNumber}>
                  <span className="mono muted">{event.sequenceNumber}</span>
                  <time>{date(event.createdAt)}</time>
                  <Badge>{label(event.type)}</Badge>
                  <span>{event.message}</span>
                </li>
              ))}
            </ol>
          )}
        </div>
      )}
    </section>
  );
}
