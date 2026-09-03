import { useMemo, useState } from 'react';
import { ArrowUpRight, FolderGit2, Pencil, Plus, Trash2 } from 'lucide-react';
import { Link } from 'react-router-dom';
import { useProjects } from '../shared/api/queries';
import type { Project } from '../shared/api/types';
import {
  Badge,
  Button,
  EmptyState,
  ErrorState,
  IconButton,
  Loading,
  PageHeader,
  SearchInput,
} from '../shared/ui';
import {
  DeleteProject,
  ProjectEditor,
} from '../features/project-management/forms';
import { useT } from '../shared/lib/i18n';

export default function ProjectsPage() {
  const { t, date } = useT();
  const query = useProjects();
  const [search, setSearch] = useState('');
  const [editing, setEditing] = useState<Project | 'new' | null>(null);
  const [deleting, setDeleting] = useState<Project | null>(null);
  const projects = useMemo(
    () =>
      query.data?.filter((project) =>
        `${project.name} ${project.description} ${project.repositoryPath}`
          .toLowerCase()
          .includes(search.toLowerCase()),
      ) ?? [],
    [query.data, search],
  );
  return (
    <div className="page">
      <PageHeader title={t('projects')} eyebrow={t('workspace')}>
        <Button variant="primary" onClick={() => setEditing('new')}>
          <Plus size={16} />
          {t('createProject')}
        </Button>
      </PageHeader>
      <div className="section-toolbar">
        <div className="section-title">
          <FolderGit2 size={17} />
          <strong>{t('projects')}</strong>
          {query.data && <Badge>{query.data.length}</Badge>}
        </div>
        <SearchInput
          label={t('searchProjects')}
          value={search}
          onChange={setSearch}
        />
      </div>
      {query.isPending ? (
        <Loading />
      ) : query.isError ? (
        <ErrorState error={query.error} retry={() => void query.refetch()} />
      ) : !query.data.length ? (
        <EmptyState
          icon={FolderGit2}
          title={t('noProjects')}
          detail={t('noProjectsDetail')}
        >
          <Button variant="primary" onClick={() => setEditing('new')}>
            <Plus size={16} />
            {t('createProject')}
          </Button>
        </EmptyState>
      ) : !projects.length ? (
        <EmptyState icon={FolderGit2} title={t('noMatches')} />
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>{t('project')}</th>
                <th>{t('repository')}</th>
                <th>{t('created')}</th>
                <th className="right">{t('actions')}</th>
              </tr>
            </thead>
            <tbody>
              {projects.map((project) => (
                <tr key={project.id}>
                  <td>
                    <Link
                      className="project-link"
                      to={`/projects/${project.id}`}
                    >
                      <span className="project-icon">
                        <FolderGit2 size={20} />
                      </span>
                      <span>
                        <strong>{project.name}</strong>
                        <small>
                          {project.description || t('noDescription')}
                        </small>
                      </span>
                    </Link>
                  </td>
                  <td>
                    <code className="path-text">
                      {project.repositoryPath || '\u2014'}
                    </code>
                  </td>
                  <td className="muted nowrap">{date(project.createdAt)}</td>
                  <td>
                    <div className="row-actions">
                      <IconButton
                        icon={Pencil}
                        label={t('editProject')}
                        onClick={() => setEditing(project)}
                      />
                      <IconButton
                        icon={Trash2}
                        label={t('deleteProject')}
                        onClick={() => setDeleting(project)}
                      />
                      <Link
                        className="icon-button"
                        to={`/projects/${project.id}`}
                        aria-label={`${t('overview')}: ${project.name}`}
                        title={t('overview')}
                      >
                        <ArrowUpRight size={17} />
                      </Link>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {editing && (
        <ProjectEditor
          project={editing === 'new' ? undefined : editing}
          onClose={() => setEditing(null)}
        />
      )}
      {deleting && (
        <DeleteProject project={deleting} onClose={() => setDeleting(null)} />
      )}
    </div>
  );
}
