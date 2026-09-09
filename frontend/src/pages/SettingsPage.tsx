import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { KeyRound, Moon, Save, Sparkles, Sun, Trash2 } from 'lucide-react';
import { api } from '../shared/api/client';
import type { Settings, SettingsInput } from '../shared/api/types';
import { useSessionAuth } from '../shared/lib/auth';
import { useT } from '../shared/lib/i18n';
import { useTheme, type Theme } from '../shared/lib/theme';
import { ModelManagerModal } from '../features/project-management/ModelManagerModal';
import {
  Button,
  ErrorState,
  Field,
  JsonView,
  Loading,
  PageHeader,
} from '../shared/ui';
import { notify } from '../shared/ui/notifications';

function SettingsForm({ settings }: { settings: Settings }) {
  const { t, label } = useT();
  const client = useQueryClient();
  const [form, setForm] = useState(settings);
  const mutation = useMutation({
    mutationFn: () =>
      api.saveSettings({
        aiMode: form.aiMode as SettingsInput['aiMode'],
        remoteAllowed: form.remoteAllowed,
        retentionDays: form.retentionDays,
      }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['settings'] });
      notify(t('settingsSaved'));
    },
  });
  const modes = ['DISABLED', 'LOCAL_PROVIDER', 'REMOTE_PROVIDER'];
  const invalidRemote =
    form.aiMode === 'REMOTE_PROVIDER' && !form.remoteAllowed;
  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        if (!invalidRemote) mutation.mutate();
      }}
    >
      <section className="settings-section">
        <div>
          <h2>{t('aiSettings')}</h2>
        </div>
        <div className="settings-fields">
          <Field label={t('aiMode')}>
            <select
              value={form.aiMode}
              onChange={(event) =>
                setForm({ ...form, aiMode: event.target.value })
              }
            >
              {!modes.includes(form.aiMode) && (
                <option value={form.aiMode}>{form.aiMode}</option>
              )}
              {modes.map((mode) => (
                <option key={mode} value={mode}>
                  {label(mode)}
                </option>
              ))}
            </select>
          </Field>
          <label className="switch-row">
            <span>{t('remoteAllowed')}</span>
            <input
              type="checkbox"
              role="switch"
              checked={form.remoteAllowed}
              onChange={(event) =>
                setForm({ ...form, remoteAllowed: event.target.checked })
              }
            />
          </label>
          {invalidRemote && (
            <p className="notice warning" role="alert">
              {t('remoteRequired')}
            </p>
          )}
        </div>
      </section>
      <section className="settings-section">
        <div>
          <h2>{t('retention')}</h2>
        </div>
        <div className="settings-fields">
          <Field label={t('retentionDays')}>
            <input
              type="number"
              required
              min={1}
              max={365}
              step={1}
              value={form.retentionDays}
              onChange={(event) =>
                setForm({ ...form, retentionDays: Number(event.target.value) })
              }
            />
          </Field>
        </div>
      </section>
      <section className="settings-section">
        <div>
          <h2>{t('authentication')}</h2>
        </div>
        <div className="settings-fields">
          <Field label={t('authMode')}>
            <input readOnly value={form.authMode} />
          </Field>
        </div>
      </section>
      <section className="settings-section">
        <div>
          <h2>{t('capabilities')}</h2>
        </div>
        <div className="settings-fields">
          <JsonView value={settings.capabilities} />
        </div>
      </section>
      {mutation.isError && <ErrorState error={mutation.error} />}
      <div className="settings-save">
        <Button
          type="submit"
          variant="primary"
          busy={mutation.isPending}
          disabled={
            invalidRemote ||
            !Number.isInteger(form.retentionDays) ||
            form.retentionDays < 1 ||
            form.retentionDays > 365
          }
        >
          <Save size={15} />
          {t('save')}
        </Button>
      </div>
    </form>
  );
}

export default function SettingsPage() {
  const { t } = useT();
  const theme = useTheme((state) => state.theme);
  const setTheme = useTheme((state) => state.setTheme);
  const client = useQueryClient();
  const auth = useSessionAuth();
  const [token, setToken] = useState(auth.token);
  const [showModelManager, setShowModelManager] = useState(false);
  const query = useQuery({
    queryKey: ['settings'],
    queryFn: ({ signal }) => api.settings(signal),
  });
  const applyToken = (value: string) => {
    auth.setToken(value);
    setToken(value);
    void client.resetQueries();
    notify(t('tokenApplied'));
  };
  return (
    <div className="page settings-page">
      <PageHeader title={t('settings')} eyebrow={t('workspace')} />
      <section className="settings-section">
        <div>
          <h2>{t('appearance')}</h2>
          <p className="muted small">{t('appearanceHint')}</p>
        </div>
        <div className="settings-fields">
          <div
            className="theme-options"
            role="radiogroup"
            aria-label={t('colorTheme')}
          >
            {(
              [
                { value: 'light', label: t('lightTheme'), icon: Sun },
                { value: 'dark', label: t('darkTheme'), icon: Moon },
              ] as Array<{ value: Theme; label: string; icon: typeof Sun }>
            ).map((option) => (
              <button
                type="button"
                role="radio"
                aria-checked={theme === option.value}
                className={theme === option.value ? 'active' : ''}
                key={option.value}
                onClick={() => setTheme(option.value)}
              >
                <option.icon size={20} />
                <span>
                  <strong>{option.label}</strong>
                  <small>
                    {t(
                      option.value === 'light'
                        ? 'lightThemeHint'
                        : 'darkThemeHint',
                    )}
                  </small>
                </span>
              </button>
            ))}
          </div>
        </div>
      </section>
      <section className="settings-section">
        <div>
          <h2>{t('aiModelSettings')}</h2>
          <p className="muted small">{t('aiModelSettingsHint')}</p>
        </div>
        <div className="settings-fields">
          <div className="advanced-setting-card">
            <Sparkles size={21} />
            <div>
              <strong>{t('selectAiModel')}</strong>
              <p>{t('aiModelAdvancedHint')}</p>
            </div>
            <Button onClick={() => setShowModelManager(true)}>
              {t('configure')}
            </Button>
          </div>
        </div>
      </section>
      <section className="settings-section">
        <div>
          <h2>{t('token')}</h2>
          <p className="muted small">{t('sessionOnly')}</p>
        </div>
        <form
          className="settings-fields"
          onSubmit={(event) => {
            event.preventDefault();
            applyToken(token);
          }}
        >
          <Field label={t('token')}>
            <input
              type="password"
              autoComplete="off"
              value={token}
              onChange={(event) => setToken(event.target.value)}
              spellCheck={false}
            />
          </Field>
          <div className="heading-actions">
            <Button type="submit">
              <KeyRound size={15} />
              {t('applyToken')}
            </Button>
            <Button
              disabled={!auth.token && !token}
              onClick={() => applyToken('')}
            >
              <Trash2 size={15} />
              {t('clearToken')}
            </Button>
          </div>
        </form>
      </section>
      {query.isPending ? (
        <Loading />
      ) : query.isError ? (
        <ErrorState error={query.error} retry={() => void query.refetch()} />
      ) : (
        <SettingsForm key={JSON.stringify(query.data)} settings={query.data} />
      )}
      {showModelManager && (
        <ModelManagerModal onClose={() => setShowModelManager(false)} />
      )}
    </div>
  );
}
