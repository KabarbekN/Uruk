import { useState, useEffect, useRef } from 'react';
import {
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  Copy,
  ExternalLink,
  Eye,
  EyeOff,
  Lock,
  Loader2,
  RefreshCw,
  ShieldCheck,
} from 'lucide-react';
import { api } from '../../shared/api/client';
import { Button, Modal } from '../../shared/ui';
import { notify } from '../../shared/ui/notifications';
import { useT } from '../../shared/lib/i18n';

interface OAuthLoginSectionProps {
  provider: 'github' | 'gitlab' | 'git';
  token: string;
  onTokenChange: (token: string) => void;
  onTestAccess?: () => void;
}

function GithubIcon({ size = 16 }: { size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="currentColor"
      aria-hidden="true"
    >
      <path
        fillRule="evenodd"
        clipRule="evenodd"
        d="M12 2C6.477 2 2 6.484 2 12.017c0 4.425 2.865 8.18 6.839 9.504.5.092.682-.217.682-.483 0-.237-.008-.868-.013-1.703-2.782.605-3.369-1.343-3.369-1.343-.454-1.158-1.11-1.466-1.11-1.466-.908-.62.069-.608.069-.608 1.003.07 1.53 1.032 1.53 1.032.892 1.53 2.341 1.088 2.91.832.092-.647.35-1.088.636-1.338-2.22-.253-4.555-1.113-4.555-4.951 0-1.093.39-1.988 1.029-2.688-.103-.253-.446-1.272.098-2.65 0 0 .84-.27 2.75 1.026A9.564 9.564 0 0112 6.844c.85.004 1.705.115 2.504.337 1.909-1.296 2.747-1.027 2.747-1.027.546 1.379.202 2.398.1 2.651.64.7 1.028 1.595 1.028 2.688 0 3.848-2.339 4.695-4.566 4.943.359.309.678.92.678 1.855 0 1.338-.012 2.419-.012 2.747 0 .268.18.58.688.482A10.019 10.019 0 0022 12.017C22 6.484 17.522 2 12 2z"
      />
    </svg>
  );
}

function GitlabIcon({ size = 16 }: { size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="currentColor"
      aria-hidden="true"
    >
      <path d="m23.6 9.58-1.06-3.26a1.47 1.47 0 0 0-.56-.73 1.52 1.52 0 0 0-.92-.26 1.55 1.55 0 0 0-.91.31 1.45 1.45 0 0 0-.5.68l-1.9 5.86H6.25L4.35 6.32a1.45 1.45 0 0 0-.5-.68 1.55 1.55 0 0 0-.91-.31 1.52 1.52 0 0 0-.92.26 1.47 1.47 0 0 0-.56.73L.4 9.58a1.9 1.9 0 0 0 .69 2.12L12 20.08l10.91-8.38a1.9 1.9 0 0 0 .69-2.12Z" />
    </svg>
  );
}

export function OAuthLoginSection({
  provider,
  token,
  onTokenChange,
  onTestAccess,
}: OAuthLoginSectionProps) {
  const { t } = useT();

  const [oauthUser, setOauthUser] = useState<{
    provider: string;
    username: string;
  } | null>(null);

  // Hidden by default for github/gitlab; open if user entered token or generic git
  const [showPatInput, setShowPatInput] = useState(Boolean((token && !oauthUser) || provider === 'git'));
  const [showToken, setShowToken] = useState(false);

  useEffect(() => {
    if (provider === 'git') {
      setShowPatInput(true);
    }
  }, [provider]);

  // GitHub Device Flow modal state
  const [deviceModalOpen, setDeviceModalOpen] = useState(false);
  const [isStartingDevice, setIsStartingDevice] = useState(false);
  const [isManualChecking, setIsManualChecking] = useState(false);
  const [userCode, setUserCode] = useState('');
  const [verificationUri, setVerificationUri] = useState('https://github.com/login/device');
  const [codeCopied, setCodeCopied] = useState(false);
  const [pollError, setPollError] = useState<string | null>(null);
  const [pollStatusText, setPollStatusText] = useState<string>('Ожидание подтверждения на GitHub...');

  const activeDeviceCodeRef = useRef<string | null>(null);
  const pollTimeoutRef = useRef<number | null>(null);
  const pollIntervalRef = useRef<number>(6000);
  const isPollingRef = useRef<boolean>(false);

  const stopPolling = () => {
    activeDeviceCodeRef.current = null;
    if (pollTimeoutRef.current) {
      clearTimeout(pollTimeoutRef.current);
      pollTimeoutRef.current = null;
    }
  };

  useEffect(() => {
    return () => stopPolling();
  }, []);

  const doPoll = async (code: string): Promise<boolean> => {
    if (!code || isPollingRef.current) return false;
    isPollingRef.current = true;
    try {
      const pollRes = await api.pollGithubDeviceFlow(code);
      if (pollRes.status === 'success' && pollRes.token) {
        stopPolling();
        onTokenChange(pollRes.token);
        setOauthUser({
          provider: 'github',
          username: pollRes.username || 'github-user',
        });
        setDeviceModalOpen(false);
        notify(`${t('authenticatedAs')} @${pollRes.username || 'user'} (${t('connectionSuccessful')})`);
        if (onTestAccess) {
          setTimeout(() => onTestAccess(), 300);
        }
        return true;
      } else if (pollRes.status === 'expired') {
        stopPolling();
        setPollError('Время действия кода истекло. Нажмите «Сгенерировать новый код».');
      } else if (pollRes.status === 'denied') {
        stopPolling();
        setPollError('Вы отклонили авторизацию в GitHub.');
      } else if (pollRes.status === 'error') {
        stopPolling();
        setPollError(pollRes.error || 'Ошибка авторизации.');
      } else if (pollRes.status === 'slow_down') {
        const nextSec = pollRes.interval ? Math.max(pollRes.interval, 8) : 8;
        pollIntervalRef.current = nextSec * 1000;
        setPollStatusText('GitHub синхронизирует подтверждение...');
      } else {
        setPollStatusText('Ожидание подтверждения на GitHub...');
      }
    } catch (err) {
      console.error('Device poll error:', err);
    } finally {
      isPollingRef.current = false;
    }
    return false;
  };

  const scheduleNextPoll = (code: string) => {
    if (!activeDeviceCodeRef.current || activeDeviceCodeRef.current !== code) return;
    pollTimeoutRef.current = window.setTimeout(async () => {
      if (!activeDeviceCodeRef.current || activeDeviceCodeRef.current !== code) return;
      const done = await doPoll(code);
      if (!done && activeDeviceCodeRef.current === code) {
        scheduleNextPoll(code);
      }
    }, pollIntervalRef.current);
  };

  // Window focus listener: when user switches back from GitHub tab, trigger poll immediately
  useEffect(() => {
    if (!deviceModalOpen) return;
    const onFocus = () => {
      if (activeDeviceCodeRef.current) {
        void doPoll(activeDeviceCodeRef.current);
      }
    };
    window.addEventListener('focus', onFocus);
    return () => window.removeEventListener('focus', onFocus);
  }, [deviceModalOpen]);

  const handleStartGithubDeviceLogin = async () => {
    setIsStartingDevice(true);
    setPollError(null);
    setCodeCopied(false);
    setPollStatusText('Ожидание подтверждения на GitHub...');
    pollIntervalRef.current = 6000;

    try {
      const resp = await api.startGithubDeviceFlow();
      setUserCode(resp.userCode);
      setVerificationUri(resp.verificationUri);
      setDeviceModalOpen(true);
      activeDeviceCodeRef.current = resp.deviceCode;

      // Automatically copy code to clipboard
      try {
        await navigator.clipboard.writeText(resp.userCode);
        setCodeCopied(true);
      } catch {
        // clipboard access denied, user can copy manually
      }

      // Automatically open GitHub device verification page
      window.open(resp.verificationUri, '_blank', 'noopener,noreferrer');

      // Start safe recurring polling
      if (resp.interval && resp.interval > 5) {
        pollIntervalRef.current = resp.interval * 1000;
      }
      scheduleNextPoll(resp.deviceCode);
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Не удалось связаться с GitHub');
    } finally {
      setIsStartingDevice(false);
    }
  };

  const handleManualCheck = async () => {
    if (!activeDeviceCodeRef.current) return;
    setIsManualChecking(true);
    setPollError(null);
    try {
      const success = await doPoll(activeDeviceCodeRef.current);
      if (!success) {
        notify('GitHub ещё обрабатывает подтверждение. Подождите несколько секунд и попробуйте снова.');
      }
    } finally {
      setIsManualChecking(false);
    }
  };

  const handleCopyCode = async () => {
    try {
      await navigator.clipboard.writeText(userCode);
      setCodeCopied(true);
      notify('Код скопирован в буфер обмена');
    } catch {
      notify('Не удалось скопировать код');
    }
  };

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: '10px',
        padding: '14px',
        background: 'var(--surface-muted, #f8f9fa)',
        borderRadius: '8px',
        border: '1px solid var(--border-subtle, #e9ecef)',
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
        }}
      >
        <span
          style={{
            fontSize: '13px',
            fontWeight: 600,
            display: 'flex',
            alignItems: 'center',
            gap: '6px',
          }}
        >
          <ShieldCheck size={16} style={{ color: 'var(--accent, #1098ad)' }} />
          Авторизация в репозитории
        </span>
      </div>

      {oauthUser && token ? (
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            padding: '10px 12px',
            background: 'rgba(43, 138, 62, 0.09)',
            border: '1px solid var(--positive, #2b8a3e)',
            borderRadius: '6px',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <CheckCircle2
              size={18}
              style={{ color: 'var(--positive, #2b8a3e)', flexShrink: 0 }}
            />
            <div>
              <div
                style={{
                  fontWeight: 600,
                  fontSize: '13px',
                  color: 'var(--positive, #2b8a3e)',
                }}
              >
                {t('authenticatedAs')} @{oauthUser.username}
              </div>
              <div
                style={{
                  fontSize: '11px',
                  color: 'var(--text-muted, #666)',
                }}
              >
                Через {oauthUser.provider === 'github' ? 'GitHub' : 'GitLab'} (доступ подтверждён)
              </div>
            </div>
          </div>
          <Button
            type="button"
            variant="ghost"
            onClick={() => {
              onTokenChange('');
              setOauthUser(null);
            }}
            style={{ fontSize: '12px' }}
          >
            Сменить
          </Button>
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
          {provider === 'gitlab' ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
              <a
                href="https://gitlab.com/-/user_settings/personal_access_tokens?name=SemanticBusinessMap&scopes=read_repository"
                target="_blank"
                rel="noreferrer"
                className="button primary"
                style={{
                  width: '100%',
                  background: '#e24329',
                  borderColor: '#e24329',
                  color: '#ffffff',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  gap: '8px',
                  padding: '10px 16px',
                  fontWeight: 600,
                  textDecoration: 'none',
                  borderRadius: '6px',
                  textAlign: 'center',
                }}
                onClick={() => setShowPatInput(true)}
              >
                <GitlabIcon size={18} />
                <span>Авторизоваться в GitLab ↗</span>
              </a>
            </div>
          ) : provider === 'github' ? (
            <Button
              type="button"
              variant="primary"
              busy={isStartingDevice}
              onClick={handleStartGithubDeviceLogin}
              style={{
                width: '100%',
                background: '#24292e',
                borderColor: '#24292e',
                color: '#ffffff',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                gap: '8px',
                padding: '10px 16px',
                fontWeight: 600,
              }}
            >
              <GithubIcon size={18} />
              <span>Войти через GitHub (в 1 клик)</span>
            </Button>
          ) : (
            <div
              style={{
                fontSize: '12px',
                color: 'var(--text-muted, #495057)',
                lineHeight: 1.4,
              }}
            >
              Укажите Personal Access Token (PAT) или HTTP-пароль для доступа к репозиторию:
            </div>
          )}
        </div>
      )}

      {/* Manual PAT input: collapsed accordion for github/gitlab, or direct field for git */}
      {provider !== 'git' ? (
        <div style={{ marginTop: '2px', borderTop: '1px dashed var(--border-subtle, #dee2e6)', paddingTop: '6px' }}>
          <button
            type="button"
            onClick={() => setShowPatInput(!showPatInput)}
            style={{
              background: 'none',
              border: 'none',
              padding: '2px 0',
              cursor: 'pointer',
              fontSize: '12px',
              color: 'var(--text-muted, #495057)',
              display: 'inline-flex',
              alignItems: 'center',
              gap: '4px',
            }}
          >
            <Lock size={12} />
            <span>{showPatInput ? 'Скрыть ввод токена PAT' : 'Использовать токен PAT вручную'}</span>
            {showPatInput ? <ChevronUp size={13} /> : <ChevronDown size={13} />}
          </button>

          {showPatInput && (
            <div
              style={{
                marginTop: '8px',
                display: 'flex',
                flexDirection: 'column',
                gap: '6px',
              }}
            >
              <div style={{ display: 'flex', gap: '6px' }}>
                <input
                  type={showToken ? 'text' : 'password'}
                  value={token}
                  placeholder={
                    provider === 'github'
                      ? 'ghp_... (Personal Access Token)'
                      : 'glpat-... (Personal Access Token)'
                  }
                  onChange={(e) => {
                    onTokenChange(e.target.value);
                    setOauthUser(null);
                  }}
                  spellCheck={false}
                  style={{ flex: 1 }}
                />
                <Button
                  type="button"
                  variant="ghost"
                  title={t(showToken ? 'hideToken' : 'showToken')}
                  onClick={() => setShowToken(!showToken)}
                >
                  {showToken ? <EyeOff size={15} /> : <Eye size={15} />}
                </Button>
              </div>
              {provider === 'github' ? (
                <a
                  href="https://github.com/settings/tokens/new?description=SemanticBusinessMap&scopes=repo"
                  target="_blank"
                  rel="noreferrer"
                  className="text-link"
                  style={{
                    fontSize: '11px',
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: '4px',
                  }}
                >
                  <ExternalLink size={11} />
                  {t('patHelpGithub')}
                </a>
              ) : (
                <a
                  href="https://gitlab.com/-/user_settings/personal_access_tokens?name=SemanticBusinessMap&scopes=read_repository"
                  target="_blank"
                  rel="noreferrer"
                  className="text-link"
                  style={{
                    fontSize: '11px',
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: '4px',
                  }}
                >
                  <ExternalLink size={11} />
                  {t('patHelpGitlab')}
                </a>
              )}
            </div>
          )}
        </div>
      ) : (
        <div
          style={{
            marginTop: '4px',
            display: 'flex',
            flexDirection: 'column',
            gap: '6px',
          }}
        >
          <div style={{ display: 'flex', gap: '6px' }}>
            <input
              type={showToken ? 'text' : 'password'}
              value={token}
              placeholder="Токен доступа (PAT) или пароль"
              onChange={(e) => {
                onTokenChange(e.target.value);
                setOauthUser(null);
              }}
              spellCheck={false}
              style={{ flex: 1 }}
            />
            <Button
              type="button"
              variant="ghost"
              title={t(showToken ? 'hideToken' : 'showToken')}
              onClick={() => setShowToken(!showToken)}
            >
              {showToken ? <EyeOff size={15} /> : <Eye size={15} />}
            </Button>
          </div>
        </div>
      )}

      {/* GitHub Device Code Confirmation Modal */}
      {deviceModalOpen && (
        <Modal
          title="Авторизация через GitHub"
          onClose={() => {
            stopPolling();
            setDeviceModalOpen(false);
          }}
        >
          <div
            className="form-body"
            style={{ display: 'flex', flexDirection: 'column', gap: '16px', padding: '20px' }}
          >
            <div style={{ fontSize: '13px', lineHeight: 1.5, color: 'var(--text)' }}>
              Для безопасного подключения без ввода секретов подтвердите вход на официальном сайте GitHub:
            </div>

            <div
              style={{
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                gap: '8px',
                padding: '16px',
                background: 'var(--surface-muted, #f1f3f5)',
                borderRadius: '8px',
                border: '1px solid var(--border-subtle, #dee2e6)',
              }}
            >
              <span style={{ fontSize: '12px', color: 'var(--text-muted, #6c757d)' }}>
                Одноразовый код авторизации:
              </span>
              <div
                style={{
                  fontSize: '28px',
                  fontWeight: 700,
                  letterSpacing: '3px',
                  fontFamily: 'monospace',
                  color: 'var(--text, #212529)',
                }}
              >
                {userCode}
              </div>
              <Button
                type="button"
                variant="secondary"
                onClick={handleCopyCode}
                style={{ fontSize: '12px', display: 'flex', alignItems: 'center', gap: '5px' }}
              >
                <Copy size={13} />
                {codeCopied ? 'Код скопирован!' : 'Скопировать код'}
              </Button>
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
              <Button
                type="button"
                variant="primary"
                onClick={() => window.open(verificationUri, '_blank', 'noopener,noreferrer')}
                style={{
                  background: '#24292e',
                  borderColor: '#24292e',
                  color: '#ffffff',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  gap: '8px',
                  padding: '10px 16px',
                  fontWeight: 600,
                }}
              >
                <GithubIcon size={16} />
                <span>Открыть страницу подтверждения GitHub ↗</span>
              </Button>

              <Button
                type="button"
                variant="secondary"
                busy={isManualChecking}
                onClick={handleManualCheck}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  gap: '8px',
                  padding: '9px 14px',
                  fontWeight: 600,
                  fontSize: '13px',
                }}
              >
                <CheckCircle2 size={16} style={{ color: 'var(--positive, #2b8a3e)' }} />
                <span>Я подтвердил на GitHub, завершить вход</span>
              </Button>
            </div>

            {pollError ? (
              <div
                style={{
                  padding: '10px',
                  background: '#fff5f5',
                  color: '#e03131',
                  borderRadius: '6px',
                  fontSize: '12px',
                  textAlign: 'center',
                  display: 'flex',
                  flexDirection: 'column',
                  gap: '8px',
                  alignItems: 'center',
                }}
              >
                <div>{pollError}</div>
                <Button
                  type="button"
                  variant="ghost"
                  onClick={handleStartGithubDeviceLogin}
                  style={{ fontSize: '12px', display: 'inline-flex', alignItems: 'center', gap: '5px' }}
                >
                  <RefreshCw size={13} />
                  <span>Сгенерировать новый код</span>
                </Button>
              </div>
            ) : (
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  gap: '8px',
                  fontSize: '12px',
                  color: 'var(--text-muted, #868e96)',
                }}
              >
                <Loader2 size={15} className="spin" />
                <span>{pollStatusText}</span>
              </div>
            )}

            <footer className="form-footer" style={{ marginTop: '8px', display: 'flex', justifyContent: 'space-between' }}>
              <Button
                type="button"
                variant="ghost"
                onClick={handleStartGithubDeviceLogin}
                style={{ fontSize: '12px', display: 'inline-flex', alignItems: 'center', gap: '5px' }}
              >
                <RefreshCw size={13} />
                <span>Сгенерировать новый код</span>
              </Button>
              <Button
                onClick={() => {
                  stopPolling();
                  setDeviceModalOpen(false);
                }}
              >
                {t('cancel')}
              </Button>
            </footer>
          </div>
        </Modal>
      )}
    </div>
  );
}
