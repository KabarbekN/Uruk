import { useState, type FormEvent } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { FolderGit2, GitBranch, Play, Save, Trash2 } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { api } from '../../shared/api/client';
import type { AnalysisRun, Project } from '../../shared/api/types';
import { Button, ErrorState, Field, Modal } from '../../shared/ui';
import { notify } from '../../shared/ui/notifications';
import { useT } from '../../shared/lib/i18n';

export function ProjectEditor({
  project,
  onClose,
}: {
  project?: Project;
  onClose: () => void;
}) {
  const { t } = useT();
  const client = useQueryClient();
  const navigate = useNavigate();
  const [name, setName] = useState(project?.name ?? '');
  const [description, setDescription] = useState(project?.description ?? '');
  const [repositoryPath, setPath] = useState(project?.repositoryPath ?? '');
  const mutation = useMutation({
    mutationFn: () => {
      const body = {
        name: name.trim(),
        description: description.trim(),
        repositoryPath: repositoryPath.trim(),
      };
      return project
        ? api.updateProject(project.id, body)
        : api.createProject(body);
    },
    onSuccess: (saved) => {
      void client.invalidateQueries({ queryKey: ['projects'] });
      notify(t('saved'));
      onClose();
      if (!project) void navigate(`/projects/${saved.id}`);
    },
  });
  return (
    <Modal
      title={t(project ? 'editProject' : 'createProject')}
      onClose={onClose}
    >
      <form
        className="form-body"
        onSubmit={(event) => {
          event.preventDefault();
          mutation.mutate();
        }}
      >
        <Field label={t('name')}>
          <input
            autoFocus
            required
            maxLength={120}
            value={name}
            onChange={(event) => setName(event.target.value)}
          />
        </Field>
        <Field label={t('description')}>
          <textarea
            maxLength={2000}
            rows={3}
            value={description}
            onChange={(event) => setDescription(event.target.value)}
          />
        </Field>
        <Field label={t('repositoryPath')}>
          <input
            value={repositoryPath}
            maxLength={2000}
            onChange={(event) => setPath(event.target.value)}
            spellCheck={false}
          />
        </Field>
        <Button
          className="align-start"
          onClick={() => {
            setPath('fixtures/spring-order-service');
            if (!name.trim()) setName('spring-order-service');
          }}
        >
          <FolderGit2 size={15} />
          {t('fixture')}
        </Button>
        {mutation.isError && <ErrorState compact error={mutation.error} />}
        <footer className="form-footer">
          <Button onClick={onClose}>{t('cancel')}</Button>
          <Button
            variant="primary"
            type="submit"
            data-testid={project ? 'save-project' : 'create-project'}
            busy={mutation.isPending}
            disabled={!name.trim()}
          >
            <Save size={15} />
            {t(project ? 'save' : 'createProject')}
          </Button>
        </footer>
      </form>
    </Modal>
  );
}

export function DeleteProject({
  project,
  onClose,
}: {
  project: Project;
  onClose: () => void;
}) {
  const { t } = useT();
  const client = useQueryClient();
  const navigate = useNavigate();
  const [confirmation, setConfirmation] = useState('');
  const mutation = useMutation({
    mutationFn: () => api.deleteProject(project.id),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['projects'] });
      onClose();
      void navigate('/projects');
    },
  });
  return (
    <Modal title={t('deleteProject')} onClose={onClose}>
      <form
        className="form-body"
        onSubmit={(event) => {
          event.preventDefault();
          if (confirmation === project.name) mutation.mutate();
        }}
      >
        <p>{t('deleteWarning')}</p>
        <strong className="break-word">{project.name}</strong>
        <Field label={t('deleteConfirm')}>
          <input
            autoFocus
            value={confirmation}
            onChange={(event) => setConfirmation(event.target.value)}
          />
        </Field>
        {mutation.isError && <ErrorState compact error={mutation.error} />}
        <footer className="form-footer">
          <Button onClick={onClose}>{t('cancel')}</Button>
          <Button
            type="submit"
            variant="danger"
            busy={mutation.isPending}
            disabled={confirmation !== project.name}
          >
            <Trash2 size={15} />
            {t('remove')}
          </Button>
        </footer>
      </form>
    </Modal>
  );
}

export function RepositoryForm({
  projectId,
  onClose,
}: {
  projectId: string;
  onClose: () => void;
}) {
  const { t } = useT();
  const client = useQueryClient();
  const [url, setUrl] = useState('');
  const [branch, setBranch] = useState('main');
  const mutation = useMutation({
    mutationFn: () =>
      api.addRepository(projectId, { url: url.trim(), branch: branch.trim() }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['projects'] });
      notify(t('repositoryAdded'));
      onClose();
    },
  });
  return (
    <Modal title={t('addRepository')} onClose={onClose}>
      <form
        className="form-body"
        onSubmit={(event) => {
          event.preventDefault();
          mutation.mutate();
        }}
      >
        <Field label={t('repositoryUrl')}>
          <input
            autoFocus
            required
            value={url}
            onChange={(event) => setUrl(event.target.value)}
            spellCheck={false}
          />
        </Field>
        <Field label={t('branch')}>
          <input
            required
            value={branch}
            onChange={(event) => setBranch(event.target.value)}
            spellCheck={false}
          />
        </Field>
        {mutation.isError && <ErrorState compact error={mutation.error} />}
        <footer className="form-footer">
          <Button onClick={onClose}>{t('cancel')}</Button>
          <Button
            variant="primary"
            type="submit"
            busy={mutation.isPending}
            disabled={!url.trim() || !branch.trim()}
          >
            <GitBranch size={15} />
            {t('addRepository')}
          </Button>
        </footer>
      </form>
    </Modal>
  );
}

export function StartAnalysis({
  projectId,
  runs,
  onClose,
}: {
  projectId: string;
  runs: AnalysisRun[];
  onClose: () => void;
}) {
  const { t, date } = useT();
  const [ref, setRef] = useState('');
  const [baseline, setBaseline] = useState('');
  const client = useQueryClient();
  const navigate = useNavigate();
  const mutation = useMutation({
    mutationFn: () =>
      api.startRun(projectId, {
        ...(ref.trim() ? { ref: ref.trim() } : {}),
        ...(baseline ? { baselineRunId: baseline } : {}),
      }),
    onSuccess: (run) => {
      client.setQueryData(['run', run.id], run);
      void client.invalidateQueries({ queryKey: ['runs', projectId] });
      onClose();
      void navigate(`/analyses/${run.id}/canvas`);
    },
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    mutation.mutate();
  };
  return (
    <Modal title={t('newAnalysis')} onClose={onClose}>
      <form className="form-body" onSubmit={submit}>
        <Field label={t('reference')}>
          <input
            autoFocus
            value={ref}
            placeholder={t('defaultRef')}
            onChange={(event) => setRef(event.target.value)}
            spellCheck={false}
          />
        </Field>
        <Field label={t('baseline')}>
          <select
            value={baseline}
            onChange={(event) => setBaseline(event.target.value)}
          >
            <option value="">{t('noBaseline')}</option>
            {runs
              .filter((run) =>
                ['SUCCEEDED', 'PARTIALLY_SUCCEEDED'].includes(run.status),
              )
              .map((run) => (
                <option value={run.id} key={run.id}>
                  {run.revision?.slice(0, 12) || run.id.slice(0, 8)} /{' '}
                  {date(run.createdAt)}
                </option>
              ))}
          </select>
        </Field>
        {mutation.isError && <ErrorState compact error={mutation.error} />}
        <footer className="form-footer">
          <Button onClick={onClose}>{t('cancel')}</Button>
          <Button
            type="submit"
            data-testid="start-analysis"
            variant="primary"
            busy={mutation.isPending}
          >
            <Play size={15} />
            {t('runAnalysis')}
          </Button>
        </footer>
      </form>
    </Modal>
  );
}
