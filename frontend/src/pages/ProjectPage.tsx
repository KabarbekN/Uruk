import { useState } from 'react';
import {
  Activity,
  ArrowUpRight,
  CheckCheck,
  FolderGit2,
  GitBranch,
  Map,
  Pencil,
  Play,
  Sparkles,
  Trash2,
} from 'lucide-react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { useProjects, useRuns } from '../shared/api/queries';
import type { AnalysisRun } from '../shared/api/types';
import {
  Badge,
  Button,
  EmptyState,
  ErrorState,
  IconButton,
  Loading,
  PageHeader,
  Status,
} from '../shared/ui';
import {
  DeleteProject,
  ProjectEditor,
  RepositoryForm,
  StartAnalysis,
} from '../features/project-management/forms';
import { useT } from '../shared/lib/i18n';

export function RunsTable({ runs }: { runs: AnalysisRun[] }) {
  const { t, date, label } = useT();
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>{t('revision')}</th>
            <th>{t('status')}</th>
            <th>{t('stage')}</th>
            <th>{t('started')}</th>
            <th className="right">{t('actions')}</th>
          </tr>
        </thead>
        <tbody>
          {[...runs]
            .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
            .map((run) => (
              <tr key={run.id}>
                <td>
                  <Link
                    className="mono strong"
                    to={`/analyses/${run.id}/canvas`}
                  >
                    {run.revision?.slice(0, 12) || run.id.slice(0, 8)}
                  </Link>
                  <small className="muted">{run.id.slice(0, 8)}</small>
                </td>
                <td>
                  <Status value={run.status} />
                </td>
                <td>
                  <span className="small">
                    {run.currentStage ? label(run.currentStage) : '\u2014'}
                  </span>
                  <div className="table-progress">
                    <progress
                      value={run.progress}
                      max={100}
                      aria-label={t('progress')}
                    />
                    <span>{Math.round(run.progress)}%</span>
                  </div>
                </td>
                <td className="muted nowrap">{date(run.createdAt)}</td>
                <td className="right">
                  <div style={{ display: 'flex', gap: '10px', justifyContent: 'flex-end', alignItems: 'center' }}>
                    <Link
                      className="text-link nowrap"
                      to={`/analyses/${run.id}/canvas`}
                      style={{ color: 'var(--primary, #0e7466)', fontWeight: 600 }}
                    >
                      <Map size={14} />
                      {t('openMap')}
                    </Link>
                  </div>
                </td>
              </tr>
            ))}
        </tbody>
      </table>
    </div>
  );
}

export default function ProjectPage() {
  const { projectId = '' } = useParams();
  const location = useLocation();
  const history = location.pathname.endsWith('/analyses');
  const { t, date } = useT();
  const projects = useProjects();
  const runs = useRuns(projectId);
  const [modal, setModal] = useState<
    'edit' | 'delete' | 'repository' | 'analysis' | null
  >(null);
  const project = projects.data?.find((item) => item.id === projectId);
  if (projects.isPending) return <Loading />;
  if (projects.isError)
    return (
      <ErrorState
        error={projects.error}
        retry={() => void projects.refetch()}
      />
    );
  if (!project)
    return (
      <EmptyState icon={FolderGit2} title={t('projectNotFound')}>
        <Link to="/projects">{t('goProjects')}</Link>
      </EmptyState>
    );
  const sortedRuns = [...(runs.data ?? [])].sort((a, b) =>
    b.createdAt.localeCompare(a.createdAt),
  );
  return (
    <div className="page">
      <PageHeader
        title={history ? t('analyses') : project.name}
        eyebrow={history ? project.name : t('project')}
      >
        <Button onClick={() => setModal('repository')}>
          <GitBranch size={15} />
          {t('addRepository')}
        </Button>
        <Link className="button secondary" to="/settings">
          <Sparkles size={15} />
          {t('selectAiModel')}
        </Link>
        <Button variant="primary" onClick={() => setModal('analysis')}>
          <Play size={15} />
          {t('runAnalysis')}
        </Button>
      </PageHeader>
      {!history && (
        <>
          <div className="project-summary">
            <div>
              <p>{project.description || t('noDescription')}</p>
              <span className="metadata">
                <FolderGit2 size={15} />
                <code>{project.repositoryPath || '\u2014'}</code>
              </span>
            </div>
            <div className="row-actions">
              <IconButton
                icon={Pencil}
                label={t('editProject')}
                onClick={() => setModal('edit')}
              />
              <IconButton
                icon={Trash2}
                label={t('deleteProject')}
                onClick={() => setModal('delete')}
              />
            </div>
          </div>
          <div className="metrics-strip">
            <div>
              <Activity size={18} />
              <span>
                {t('totalAnalyses')}
                <strong>{runs.data?.length ?? '\u2014'}</strong>
              </span>
            </div>
            <div>
              <CheckCheck size={18} />
              <span>
                {t('successfulAnalyses')}
                <strong>
                  {runs.data?.filter((run) =>
                    ['SUCCEEDED', 'COMPLETED'].includes(run.status),
                  ).length ?? '\u2014'}
                </strong>
              </span>
            </div>
            <div>
              <GitBranch size={18} />
              <span>
                {t('latestAnalysis')}
                <strong className="metric-date">
                  {date(sortedRuns[0]?.createdAt)}
                </strong>
              </span>
            </div>
          </div>
          {sortedRuns[0] && (
            <section className="latest-findings-card">
              <div className="latest-findings-icon">
                <Map size={22} />
              </div>
              <div>
                <span className="eyebrow">{t('latestFindings')}</span>
                <h2>
                  {sortedRuns[0].revision?.slice(0, 12) ||
                    sortedRuns[0].id.slice(0, 8)}
                </h2>
                <p>{t('latestFindingsHint')}</p>
                <div className="badge-row">
                  <Status value={sortedRuns[0].status} />
                  <Badge>
                    {date(sortedRuns[0].finishedAt || sortedRuns[0].createdAt)}
                  </Badge>
                </div>
              </div>
              <Link
                className="button primary"
                to={`/analyses/${sortedRuns[0].id}/canvas`}
              >
                {t('openFindings')}
                <ArrowUpRight size={15} />
              </Link>
            </section>
          )}
        </>
      )}
      <div className="section-toolbar">
        <div className="section-title">
          <Activity size={17} />
          <h2>{t('analyses')}</h2>
          {runs.data && <Badge>{runs.data.length}</Badge>}
        </div>
        {!history && Boolean(runs.data?.length) && (
          <Link className="text-link" to={`/projects/${projectId}/analyses`}>
            {t('viewAll')}
            <ArrowUpRight size={14} />
          </Link>
        )}
      </div>
      {runs.isPending ? (
        <Loading />
      ) : runs.isError ? (
        <ErrorState error={runs.error} retry={() => void runs.refetch()} />
      ) : !runs.data.length ? (
        <EmptyState
          icon={Activity}
          title={t('noAnalyses')}
          detail={t('noAnalysesDetail')}
        >
          <Button variant="primary" onClick={() => setModal('analysis')}>
            <Play size={15} />
            {t('runAnalysis')}
          </Button>
        </EmptyState>
      ) : (
        <RunsTable runs={history ? sortedRuns : sortedRuns.slice(0, 8)} />
      )}
      {modal === 'edit' && (
        <ProjectEditor project={project} onClose={() => setModal(null)} />
      )}
      {modal === 'delete' && (
        <DeleteProject project={project} onClose={() => setModal(null)} />
      )}
      {modal === 'repository' && (
        <RepositoryForm projectId={projectId} onClose={() => setModal(null)} />
      )}
      {modal === 'analysis' && (
        <StartAnalysis
          projectId={projectId}
          runs={sortedRuns}
          onClose={() => setModal(null)}
        />
      )}
    </div>
  );
}
