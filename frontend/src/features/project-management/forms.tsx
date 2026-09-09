import { useState, useEffect, type FormEvent } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import {
  CheckCircle2,
  FolderGit2,
  GitBranch,
  Loader2,
  Lock,
  Play,
  Save,
  Sparkles,
  Trash2,
} from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { api } from '../../shared/api/client';
import type {
  AnalysisRun,
  Project,
  ResolvedRepository,
  TestAccessResponse,
} from '../../shared/api/types';
import { Badge, Button, ErrorState, Field, Modal } from '../../shared/ui';
import { notify } from '../../shared/ui/notifications';
import { useT } from '../../shared/lib/i18n';
import { ModelManagerModal } from './ModelManagerModal';
import { OAuthLoginSection } from './OAuthLoginSection';

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

  const [sourceType, setSourceType] = useState<'git' | 'local'>('git');
  const [name, setName] = useState(project?.name ?? '');
  const [autoName, setAutoName] = useState('');
  const [description, setDescription] = useState(project?.description ?? '');
  const [repositoryPath, setPath] = useState(project?.repositoryPath ?? '');

  // Remote Git state for creation
  const [gitUrl, setGitUrl] = useState('');
  const [gitBranch, setGitBranch] = useState('main');
  const [gitToken, setGitToken] = useState('');
  const [testResult, setTestResult] = useState<TestAccessResponse | null>(null);
  const [testError, setTestError] = useState<string | null>(null);
  const [isModelManagerOpen, setIsModelManagerOpen] = useState(false);
  const [resolvedRepo, setResolvedRepo] = useState<ResolvedRepository | null>(null);
  const [isResolving, setIsResolving] = useState(false);
  const [manualAuthOpen, setManualAuthOpen] = useState(false);

  const gitProvider = gitUrl.includes('github.com')
    ? 'github'
    : gitUrl.includes('gitlab.com')
      ? 'gitlab'
      : 'git';

  const isAuthRequired = Boolean(
    (resolvedRepo && (!resolvedRepo.hasAccess || resolvedRepo.visibility === 'PRIVATE' || resolvedRepo.visibility === 'ACCESS_DENIED')) ||
    (testError && !testResult) ||
    gitToken.trim().length > 0 ||
    manualAuthOpen
  );

  useEffect(() => {
    const trimmed = gitUrl.trim();
    if (!trimmed || sourceType !== 'git' || trimmed.length < 8) {
      setResolvedRepo(null);
      setIsResolving(false);
      return;
    }

    const timer = setTimeout(async () => {
      setIsResolving(true);
      try {
        const res = await api.resolveRepository({
          url: trimmed,
          token: gitToken.trim() || undefined,
        });
        setResolvedRepo(res);
        if (res.name && (!name.trim() || name === autoName)) {
          setName(res.name);
          setAutoName(res.name);
        }
        if (res.defaultBranch) {
          setGitBranch(res.defaultBranch);
        }
        if (res.hasAccess) {
          setTestError(null);
        } else if (res.errorMessage) {
          setTestError(res.errorMessage);
        }
      } catch {
        // Silently ignore transient resolution errors while typing
      } finally {
        setIsResolving(false);
      }
    }, 450);

    return () => clearTimeout(timer);
  }, [gitUrl, gitToken, sourceType]);

  const handleGitUrlChange = (value: string) => {
    setGitUrl(value);
    setTestResult(null);
    setTestError(null);
    if (!name.trim() || name === autoName) {
      const clean = value.replace(/\.git$/, '').replace(/\/$/, '');
      const parts = clean.split(/[/:]/);
      const extracted = parts[parts.length - 1]?.trim();
      if (extracted) {
        setName(extracted);
        setAutoName(extracted);
      }
    }
  };

  const testAccessMutation = useMutation({
    mutationFn: () =>
      api.testRepositoryAccess({
        url: gitUrl.trim(),
        token: gitToken.trim() || undefined,
      }),
    onSuccess: (data) => {
      setTestResult(data);
      setTestError(null);
      if (data.defaultBranch && (gitBranch === 'main' || !gitBranch.trim())) {
        setGitBranch(data.defaultBranch);
      }
      notify(t('connectionSuccessful'));
    },
    onError: (err: unknown) => {
      setTestResult(null);
      setTestError(err instanceof Error ? err.message : String(err));
    },
  });

  const mutation = useMutation({
    mutationFn: async () => {
      if (project) {
        return api.updateProject(project.id, {
          name: name.trim(),
          description: description.trim(),
          repositoryPath: repositoryPath.trim(),
        });
      }
      if (sourceType === 'git') {
        const saved = await api.createProject({
          name: name.trim(),
          description: description.trim(),
          repositoryPath: gitUrl.trim(),
        });
        await api.addRepository(saved.id, {
          url: gitUrl.trim(),
          branch: gitBranch.trim() || 'main',
          personalAccessToken: gitToken.trim() || undefined,
        });
        return saved;
      } else {
        return api.createProject({
          name: name.trim(),
          description: description.trim(),
          repositoryPath: repositoryPath.trim(),
        });
      }
    },
    onSuccess: (saved) => {
      void client.invalidateQueries({ queryKey: ['projects'] });
      notify(t('saved'));
      onClose();
      if (!project) void navigate(`/projects/${saved.id}`);
    },
  });

  return (
    <>
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
          {!project && (
            <div
              style={{
                display: 'flex',
                gap: '8px',
                borderBottom: '1px solid var(--border-subtle, #dee2e6)',
                marginBottom: '8px',
              }}
            >
              <button
                type="button"
                className={`tab-btn ${sourceType === 'git' ? 'active strong' : ''}`}
                style={{
                  padding: '8px 14px',
                  background: 'none',
                  border: 'none',
                  borderBottom:
                    sourceType === 'git'
                      ? '2px solid var(--accent, #1098ad)'
                      : '2px solid transparent',
                  cursor: 'pointer',
                  fontWeight: sourceType === 'git' ? 600 : 400,
                  display: 'flex',
                  alignItems: 'center',
                  gap: '6px',
                }}
                onClick={() => setSourceType('git')}
              >
                <GitBranch size={15} />
                {t('remoteGit')}
              </button>
              <button
                type="button"
                className={`tab-btn ${sourceType === 'local' ? 'active strong' : ''}`}
                style={{
                  padding: '8px 14px',
                  background: 'none',
                  border: 'none',
                  borderBottom:
                    sourceType === 'local'
                      ? '2px solid var(--accent, #1098ad)'
                      : '2px solid transparent',
                  cursor: 'pointer',
                  fontWeight: sourceType === 'local' ? 600 : 400,
                  display: 'flex',
                  alignItems: 'center',
                  gap: '6px',
                }}
                onClick={() => setSourceType('local')}
              >
                <FolderGit2 size={15} />
                {t('localPath')}
              </button>
            </div>
          )}

          {!project && sourceType === 'git' ? (
            <>
              <Field label={t('repositoryUrl')}>
                <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
                  <input
                    autoFocus
                    required
                    value={gitUrl}
                    placeholder="https://github.com/owner/repo или https://gitlab.com/..."
                    onChange={(e) => handleGitUrlChange(e.target.value)}
                    spellCheck={false}
                    style={{ flex: 1 }}
                  />
                  {isResolving && (
                    <Badge tone="neutral">
                      <Loader2 size={11} className="spin" /> Проверка...
                    </Badge>
                  )}
                  {resolvedRepo ? (
                    <>
                      {resolvedRepo.provider === 'GITHUB' && <Badge tone="info">GitHub</Badge>}
                      {resolvedRepo.provider === 'GITLAB' && <Badge tone="warning">GitLab</Badge>}
                      {resolvedRepo.provider === 'GENERIC_GIT' && <Badge tone="neutral">Git</Badge>}

                      {resolvedRepo.visibility === 'PUBLIC' && (
                        <Badge tone="positive">● Публичный</Badge>
                      )}
                      {resolvedRepo.visibility === 'PRIVATE' && (
                        <Badge tone={resolvedRepo.hasAccess ? 'positive' : 'warning'}>
                          {resolvedRepo.hasAccess ? '● Доступен' : '🔒 Нужен доступ'}
                        </Badge>
                      )}
                      {resolvedRepo.visibility === 'ACCESS_DENIED' && (
                        <Badge tone="negative">✕ Ошибка доступа</Badge>
                      )}
                    </>
                  ) : (
                    <>
                      {gitProvider === 'github' && <Badge tone="info">GitHub</Badge>}
                      {gitProvider === 'gitlab' && <Badge tone="warning">GitLab</Badge>}
                    </>
                  )}
                </div>
              </Field>

              {/* Informative resolution notice */}
              {resolvedRepo && resolvedRepo.visibility === 'PUBLIC' && (
                <div
                  style={{
                    padding: '8px 12px',
                    background: 'rgba(43, 138, 62, 0.08)',
                    border: '1px solid rgba(43, 138, 62, 0.25)',
                    color: '#2b8a3e',
                    borderRadius: '6px',
                    fontSize: '12px',
                    display: 'flex',
                    alignItems: 'center',
                    gap: '6px',
                  }}
                >
                  <CheckCircle2 size={15} style={{ flexShrink: 0 }} />
                  <span>
                    Публичный репозиторий <strong>{resolvedRepo.fullName}</strong> подтверждён. Авторизация не требуется (ветка {resolvedRepo.defaultBranch}).
                  </span>
                </div>
              )}

              {resolvedRepo && resolvedRepo.visibility === 'PRIVATE' && resolvedRepo.hasAccess && (
                <div
                  style={{
                    padding: '8px 12px',
                    background: 'rgba(43, 138, 62, 0.08)',
                    border: '1px solid rgba(43, 138, 62, 0.25)',
                    color: '#2b8a3e',
                    borderRadius: '6px',
                    fontSize: '12px',
                    display: 'flex',
                    alignItems: 'center',
                    gap: '6px',
                  }}
                >
                  <CheckCircle2 size={15} style={{ flexShrink: 0 }} />
                  <span>
                    Доступ к приватному репозиторию <strong>{resolvedRepo.fullName}</strong> подтверждён ({resolvedRepo.branches.length} веток).
                  </span>
                </div>
              )}

              {resolvedRepo && resolvedRepo.visibility === 'PRIVATE' && !resolvedRepo.hasAccess && (
                <div
                  style={{
                    padding: '8px 12px',
                    background: '#fff9db',
                    border: '1px solid #ffe066',
                    color: '#d9480f',
                    borderRadius: '6px',
                    fontSize: '12px',
                    display: 'flex',
                    alignItems: 'center',
                    gap: '6px',
                  }}
                >
                  <Lock size={15} style={{ flexShrink: 0 }} />
                  <span>
                    Репозиторий приватный. Авторизуйтесь через кнопку ниже, чтобы получить доступ.
                  </span>
                </div>
              )}

              {isAuthRequired ? (
                <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                  <OAuthLoginSection
                    provider={gitProvider}
                    token={gitToken}
                    onTokenChange={(tok) => {
                      setGitToken(tok);
                      setTestResult(null);
                      setTestError(null);
                    }}
                    onTestAccess={() => {
                      if (gitUrl.trim()) {
                        testAccessMutation.mutate();
                      }
                    }}
                  />
                  {manualAuthOpen && !gitToken.trim() && (
                    <button
                      type="button"
                      onClick={() => setManualAuthOpen(false)}
                      style={{
                        alignSelf: 'flex-start',
                        background: 'none',
                        border: 'none',
                        padding: '2px 0',
                        cursor: 'pointer',
                        fontSize: '11px',
                        color: 'var(--text-muted, #6c757d)',
                        textDecoration: 'underline',
                      }}
                    >
                      Скрыть блок авторизации
                    </button>
                  )}
                </div>
              ) : (
                <div style={{ marginTop: '2px' }}>
                  <button
                    type="button"
                    onClick={() => setManualAuthOpen(true)}
                    style={{
                      background: 'none',
                      border: 'none',
                      padding: '4px 0',
                      cursor: 'pointer',
                      fontSize: '12px',
                      color: 'var(--accent, #1098ad)',
                      display: 'inline-flex',
                      alignItems: 'center',
                      gap: '5px',
                    }}
                  >
                    <Lock size={12} />
                    <span>Приватный репозиторий? Авторизоваться или указать токен</span>
                  </button>
                </div>
              )}

              <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <Button
                  type="button"
                  variant="secondary"
                  disabled={!gitUrl.trim()}
                  busy={testAccessMutation.isPending}
                  onClick={() => testAccessMutation.mutate()}
                >
                  <CheckCircle2 size={15} />
                  {testAccessMutation.isPending
                    ? t('testingConnection')
                    : t('testConnection')}
                </Button>
                {testResult && (
                  <span
                    style={{
                      color: 'var(--positive, #2b8a3e)',
                      fontSize: '12px',
                      display: 'inline-flex',
                      alignItems: 'center',
                      gap: '4px',
                    }}
                  >
                    <CheckCircle2 size={14} />
                    {t('connectionSuccessful')} (
                    {gitProvider === 'github'
                      ? 'GitHub'
                      : gitProvider === 'gitlab'
                        ? 'GitLab'
                        : 'Git'}
                    , {testResult.branches.length} веток)
                  </span>
                )}
              </div>

              {testError && (
                <div
                  style={{
                    padding: '8px 12px',
                    background: '#fff5f5',
                    color: '#e03131',
                    borderRadius: '6px',
                    fontSize: '12px',
                  }}
                >
                  <strong>{t('connectionFailed')}:</strong> {testError}
                </div>
              )}

              <Field label={t('branch')}>
                <input
                  required
                  list="project-editor-branches"
                  value={gitBranch}
                  onChange={(e) => setGitBranch(e.target.value)}
                  spellCheck={false}
                />
                {Boolean(resolvedRepo?.branches?.length || testResult?.branches?.length) && (
                  <datalist id="project-editor-branches">
                    {(resolvedRepo?.branches || testResult?.branches || []).map((b) => (
                      <option key={b} value={b} />
                    ))}
                  </datalist>
                )}
              </Field>

              <Field label={t('name')}>
                <input
                  required
                  maxLength={120}
                  value={name}
                  placeholder="my-cool-project"
                  onChange={(event) => setName(event.target.value)}
                />
              </Field>

              <Field label={t('description')}>
                <textarea
                  maxLength={2000}
                  rows={2}
                  value={description}
                  onChange={(event) => setDescription(event.target.value)}
                />
              </Field>

              <div
                style={{
                  padding: '10px 12px',
                  background: 'var(--surface-muted, #f8f9fa)',
                  borderRadius: '6px',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                }}
              >
                <span
                  style={{
                    fontSize: '13px',
                    display: 'flex',
                    alignItems: 'center',
                    gap: '6px',
                  }}
                >
                  <Sparkles size={15} style={{ color: '#0c8599' }} />
                  {t('selectAiModel')}
                </span>
                <Button
                  type="button"
                  variant="ghost"
                  onClick={() => setIsModelManagerOpen(true)}
                >
                  {t('aiModelSettings')}
                </Button>
              </div>
            </>
          ) : (
            <>
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
                type="button"
                onClick={() => {
                  setPath('fixtures/spring-order-service');
                  if (!name.trim()) setName('spring-order-service');
                }}
              >
                <FolderGit2 size={15} />
                {t('fixture')}
              </Button>
            </>
          )}

          {mutation.isError && <ErrorState compact error={mutation.error} />}
          <footer className="form-footer">
            <Button onClick={onClose}>{t('cancel')}</Button>
            <Button
              variant="primary"
              type="submit"
              data-testid={project ? 'save-project' : 'create-project'}
              busy={mutation.isPending}
              disabled={
                !name.trim() ||
                (!project && sourceType === 'git' && !gitUrl.trim())
              }
            >
              <Save size={15} />
              {t(project ? 'save' : 'createProject')}
            </Button>
          </footer>
        </form>
      </Modal>

      {isModelManagerOpen && (
        <ModelManagerModal onClose={() => setIsModelManagerOpen(false)} />
      )}
    </>
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
  const [personalAccessToken, setPersonalAccessToken] = useState('');
  const [testResult, setTestResult] = useState<TestAccessResponse | null>(null);
  const [testError, setTestError] = useState<string | null>(null);
  const [isModelManagerOpen, setIsModelManagerOpen] = useState(false);
  const [resolvedRepo, setResolvedRepo] = useState<ResolvedRepository | null>(null);
  const [isResolving, setIsResolving] = useState(false);
  const [manualAuthOpen, setManualAuthOpen] = useState(false);

  const provider = url.includes('github.com')
    ? 'github'
    : url.includes('gitlab.com')
      ? 'gitlab'
      : 'git';

  useEffect(() => {
    const trimmed = url.trim();
    if (!trimmed || trimmed.length < 8) {
      setResolvedRepo(null);
      setIsResolving(false);
      return;
    }

    const timer = setTimeout(async () => {
      setIsResolving(true);
      try {
        const res = await api.resolveRepository({
          url: trimmed,
          token: personalAccessToken.trim() || undefined,
        });
        setResolvedRepo(res);
        if (res.defaultBranch && (branch === 'main' || !branch.trim())) {
          setBranch(res.defaultBranch);
        }
        if (res.hasAccess) {
          setTestError(null);
        } else if (res.errorMessage) {
          setTestError(res.errorMessage);
        }
      } catch {
        // Silently ignore transient errors
      } finally {
        setIsResolving(false);
      }
    }, 450);

    return () => clearTimeout(timer);
  }, [url, personalAccessToken, branch]);

  const isAuthRequired = Boolean(
    (resolvedRepo && (!resolvedRepo.hasAccess || resolvedRepo.visibility === 'PRIVATE' || resolvedRepo.visibility === 'ACCESS_DENIED')) ||
    (testError && !testResult) ||
    personalAccessToken.trim().length > 0 ||
    manualAuthOpen
  );

  const testAccessMutation = useMutation({
    mutationFn: () =>
      api.testRepositoryAccess({
        url: url.trim(),
        token: personalAccessToken.trim() || undefined,
      }),
    onSuccess: (data) => {
      setTestResult(data);
      setTestError(null);
      if (data.defaultBranch && (branch === 'main' || !branch.trim())) {
        setBranch(data.defaultBranch);
      }
      notify(t('connectionSuccessful'));
    },
    onError: (err: unknown) => {
      setTestResult(null);
      setTestError(err instanceof Error ? err.message : String(err));
    },
  });

  const mutation = useMutation({
    mutationFn: () =>
      api.addRepository(projectId, {
        url: url.trim(),
        branch: branch.trim(),
        personalAccessToken: personalAccessToken.trim() || undefined,
      }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['projects'] });
      notify(t('repositoryAdded'));
      onClose();
    },
  });

  return (
    <>
      <Modal title={t('addRepository')} onClose={onClose}>
        <form
          className="form-body"
          onSubmit={(event) => {
            event.preventDefault();
            mutation.mutate();
          }}
        >
          <Field label={t('repositoryUrl')}>
            <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
              <input
                autoFocus
                required
                value={url}
                placeholder="https://github.com/owner/repo или https://gitlab.com/..."
                onChange={(event) => {
                  setUrl(event.target.value);
                  setTestResult(null);
                  setTestError(null);
                }}
                spellCheck={false}
                style={{ flex: 1 }}
              />
              {isResolving && (
                <Badge tone="neutral">
                  <Loader2 size={11} className="spin" /> Проверка...
                </Badge>
              )}
              {resolvedRepo ? (
                <>
                  {resolvedRepo.provider === 'GITHUB' && <Badge tone="info">GitHub</Badge>}
                  {resolvedRepo.provider === 'GITLAB' && <Badge tone="warning">GitLab</Badge>}
                  {resolvedRepo.provider === 'GENERIC_GIT' && <Badge tone="neutral">Git</Badge>}

                  {resolvedRepo.visibility === 'PUBLIC' && (
                    <Badge tone="positive">● Публичный</Badge>
                  )}
                  {resolvedRepo.visibility === 'PRIVATE' && (
                    <Badge tone={resolvedRepo.hasAccess ? 'positive' : 'warning'}>
                      {resolvedRepo.hasAccess ? '● Доступен' : '🔒 Нужен доступ'}
                    </Badge>
                  )}
                  {resolvedRepo.visibility === 'ACCESS_DENIED' && (
                    <Badge tone="negative">✕ Ошибка доступа</Badge>
                  )}
                </>
              ) : (
                <>
                  {provider === 'github' && <Badge tone="info">GitHub</Badge>}
                  {provider === 'gitlab' && <Badge tone="warning">GitLab</Badge>}
                </>
              )}
            </div>
          </Field>

          {/* Informative resolution notice */}
          {resolvedRepo && resolvedRepo.visibility === 'PUBLIC' && (
            <div
              style={{
                padding: '8px 12px',
                background: 'rgba(43, 138, 62, 0.08)',
                border: '1px solid rgba(43, 138, 62, 0.25)',
                color: '#2b8a3e',
                borderRadius: '6px',
                fontSize: '12px',
                display: 'flex',
                alignItems: 'center',
                gap: '6px',
              }}
            >
              <CheckCircle2 size={15} style={{ flexShrink: 0 }} />
              <span>
                Публичный репозиторий <strong>{resolvedRepo.fullName}</strong> подтверждён. Авторизация не требуется (ветка {resolvedRepo.defaultBranch}).
              </span>
            </div>
          )}

          {resolvedRepo && resolvedRepo.visibility === 'PRIVATE' && resolvedRepo.hasAccess && (
            <div
              style={{
                padding: '8px 12px',
                background: 'rgba(43, 138, 62, 0.08)',
                border: '1px solid rgba(43, 138, 62, 0.25)',
                color: '#2b8a3e',
                borderRadius: '6px',
                fontSize: '12px',
                display: 'flex',
                alignItems: 'center',
                gap: '6px',
              }}
            >
              <CheckCircle2 size={15} style={{ flexShrink: 0 }} />
              <span>
                Доступ к приватному репозиторию <strong>{resolvedRepo.fullName}</strong> подтверждён ({resolvedRepo.branches.length} веток).
              </span>
            </div>
          )}

          {resolvedRepo && resolvedRepo.visibility === 'PRIVATE' && !resolvedRepo.hasAccess && (
            <div
              style={{
                padding: '8px 12px',
                background: '#fff9db',
                border: '1px solid #ffe066',
                color: '#d9480f',
                borderRadius: '6px',
                fontSize: '12px',
                display: 'flex',
                alignItems: 'center',
                gap: '6px',
              }}
            >
              <Lock size={15} style={{ flexShrink: 0 }} />
              <span>
                Репозиторий приватный. Авторизуйтесь через кнопку ниже, чтобы получить доступ.
              </span>
            </div>
          )}

          {isAuthRequired ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
              <OAuthLoginSection
                provider={provider}
                token={personalAccessToken}
                onTokenChange={(tok) => {
                  setPersonalAccessToken(tok);
                  setTestResult(null);
                  setTestError(null);
                }}
                onTestAccess={() => {
                  if (url.trim()) {
                    testAccessMutation.mutate();
                  }
                }}
              />
              {manualAuthOpen && !personalAccessToken.trim() && (
                <button
                  type="button"
                  onClick={() => setManualAuthOpen(false)}
                  style={{
                    alignSelf: 'flex-start',
                    background: 'none',
                    border: 'none',
                    padding: '2px 0',
                    cursor: 'pointer',
                    fontSize: '11px',
                    color: 'var(--text-muted, #6c757d)',
                    textDecoration: 'underline',
                  }}
                >
                  Скрыть блок авторизации
                </button>
              )}
            </div>
          ) : (
            <div style={{ marginTop: '2px' }}>
              <button
                type="button"
                onClick={() => setManualAuthOpen(true)}
                style={{
                  background: 'none',
                  border: 'none',
                  padding: '4px 0',
                  cursor: 'pointer',
                  fontSize: '12px',
                  color: 'var(--accent, #1098ad)',
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: '5px',
                }}
              >
                <Lock size={12} />
                <span>Приватный репозиторий? Авторизоваться или указать токен</span>
              </button>
            </div>
          )}

          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <Button
              type="button"
              variant="secondary"
              disabled={!url.trim()}
              busy={testAccessMutation.isPending}
              onClick={() => testAccessMutation.mutate()}
            >
              <CheckCircle2 size={15} />
              {testAccessMutation.isPending
                ? t('testingConnection')
                : t('testConnection')}
            </Button>
            {testResult && (
              <span
                style={{
                  color: 'var(--positive, #2b8a3e)',
                  fontSize: '12px',
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: '4px',
                }}
              >
                <CheckCircle2 size={14} />
                {t('connectionSuccessful')} (
                {provider === 'github'
                  ? 'GitHub'
                  : provider === 'gitlab'
                    ? 'GitLab'
                    : 'Git'}
                , {testResult.branches.length} веток)
              </span>
            )}
          </div>

          {testError && (
            <div
              style={{
                padding: '8px 12px',
                background: '#fff5f5',
                color: '#e03131',
                borderRadius: '6px',
                fontSize: '12px',
              }}
            >
              <strong>{t('connectionFailed')}:</strong> {testError}
            </div>
          )}

          <Field label={t('branch')}>
            <input
              required
              list="detected-branches"
              value={branch}
              onChange={(event) => setBranch(event.target.value)}
              spellCheck={false}
            />
            {Boolean(testResult?.branches?.length) && (
              <datalist id="detected-branches">
                {testResult?.branches.map((b) => (
                  <option key={b} value={b} />
                ))}
              </datalist>
            )}
          </Field>

          <div
            style={{
              padding: '10px 12px',
              background: 'var(--surface-muted, #f8f9fa)',
              borderRadius: '6px',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
            }}
          >
            <span
              style={{
                fontSize: '13px',
                display: 'flex',
                alignItems: 'center',
                gap: '6px',
              }}
            >
              <Sparkles size={15} style={{ color: '#0c8599' }} />
              {t('selectAiModel')}
            </span>
            <Button
              type="button"
              variant="ghost"
              onClick={() => setIsModelManagerOpen(true)}
            >
              {t('aiModelSettings')}
            </Button>
          </div>

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

      {isModelManagerOpen && (
        <ModelManagerModal onClose={() => setIsModelManagerOpen(false)} />
      )}
    </>
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
  const [isModelManagerOpen, setIsModelManagerOpen] = useState(false);
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
    <>
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

          <div
            style={{
              padding: '10px 12px',
              background: 'var(--surface-muted, #f8f9fa)',
              borderRadius: '6px',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
            }}
          >
            <span
              style={{
                fontSize: '13px',
                display: 'flex',
                alignItems: 'center',
                gap: '6px',
              }}
            >
              <Sparkles size={15} style={{ color: '#0c8599' }} />
              {t('selectAiModel')}
            </span>
            <Button
              type="button"
              variant="ghost"
              onClick={() => setIsModelManagerOpen(true)}
            >
              {t('aiModelSettings')}
            </Button>
          </div>

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

      {isModelManagerOpen && (
        <ModelManagerModal onClose={() => setIsModelManagerOpen(false)} />
      )}
    </>
  );
}
