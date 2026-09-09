import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Cpu,
  DownloadCloud,
  CheckCircle2,
  HardDrive,
  Sparkles,
  Zap,
  KeyRound,
  Server,
  AlertTriangle,
  RefreshCw,
  Search,
} from 'lucide-react';
import { api } from '../../shared/api/client';
import { Button, ErrorState, Field, Modal, Badge } from '../../shared/ui';
import { notify } from '../../shared/ui/notifications';
import { useT } from '../../shared/lib/i18n';

const CATEGORY_FILTERS = [
  { id: 'all', label: 'Все модели' },
  { id: 'lightweight', label: '🚀 Легкие (<6 GB)' },
  { id: 'optimal', label: '⚖️ Оптимальные (8-16 GB)' },
  { id: 'power', label: '💪 Мощные (16-32 GB)' },
  { id: 'enterprise', label: '🏢 Enterprise (32+ GB)' },
  { id: 'reasoning', label: '🧠 Рассуждения (R1)' },
];

const CATEGORY_TAGS: Record<
  string,
  { label: string; color: string; bg: string }
> = {
  lightweight: { label: '🚀 Легкая (<6 GB)', color: '#099268', bg: '#ebfbee' },
  optimal: {
    label: '⚖️ Оптимальная (8-16 GB)',
    color: '#0c8599',
    bg: '#e3fafc',
  },
  power: { label: '💪 Мощная (16-32 GB)', color: '#e67700', bg: '#fff9db' },
  enterprise: {
    label: '🏢 Enterprise (32+ GB)',
    color: '#d6336c',
    bg: '#fff0f6',
  },
  reasoning: { label: '🧠 Рассуждения (R1)', color: '#7048e8', bg: '#f3f0ff' },
  cloud: { label: '☁️ Облачный API', color: '#1c7ed6', bg: '#e7f5ff' },
};

export function ModelManagerModal({ onClose }: { onClose: () => void }) {
  const { t } = useT();
  const client = useQueryClient();
  const [activeTab, setActiveTab] = useState<'local' | 'remote'>('local');
  const [showAdvanced, setShowAdvanced] = useState(false);

  // Search and Category filters for Local models
  const [searchTerm, setSearchTerm] = useState('');
  const [selectedCategory, setSelectedCategory] = useState<string>('all');

  // Cloud API form
  const [cloudModel, setCloudModel] = useState('gpt-4o');
  const [cloudApiKey, setCloudApiKey] = useState('');
  const [cloudBaseUrl, setCloudBaseUrl] = useState('');
  const [customOllamaModel, setCustomOllamaModel] = useState('');
  const [customRemoteModel, setCustomRemoteModel] = useState('');

  // Pulling state
  const [pullingModel, setPullingModel] = useState<string | null>(null);
  const [pullProgress, setPullProgress] = useState<{
    status: string;
    completed: number;
    total: number;
    percent: number;
  }>({ status: '', completed: 0, total: 0, percent: 0 });

  const {
    data: profile,
    isLoading,
    isError,
    error,
    refetch,
  } = useQuery({
    queryKey: ['system-resources'],
    queryFn: ({ signal }) => api.getSystemResources(signal),
  });

  const selectMutation = useMutation({
    mutationFn: (vars: {
      model: string;
      providerType: string;
      apiKey?: string;
      baseUrl?: string;
    }) => api.selectAiModel(vars),
    onSuccess: (data) => {
      void client.invalidateQueries({ queryKey: ['settings'] });
      void client.invalidateQueries({ queryKey: ['system-resources'] });
      notify(
        `Модель ${data ? String((data as Record<string, unknown>).model ?? '') : ''} активирована!`,
      );
      onClose();
    },
  });

  const handleStartPull = async (modelName: string) => {
    setPullingModel(modelName);
    setPullProgress({
      status: 'Подготовка к загрузке...',
      completed: 0,
      total: 0,
      percent: 0,
    });

    try {
      const response = await fetch('/api/v1/system/ai/pull', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ model: modelName }),
      });

      if (!response.body) {
        throw new Error('No streaming response body');
      }

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';

      while (true) {
        const { value, done } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() ?? '';

        for (const line of lines) {
          if (line.startsWith('data:')) {
            try {
              const data = JSON.parse(line.substring(5).trim());
              if (data.percent !== undefined) {
                setPullProgress({
                  status: data.status || 'Загрузка весов...',
                  completed: data.completed || 0,
                  total: data.total || 0,
                  percent: Math.min(100, Math.max(0, data.percent)),
                });
              }
              if (data.active) {
                void client.invalidateQueries({ queryKey: ['settings'] });
                void client.invalidateQueries({
                  queryKey: ['system-resources'],
                });
                notify(`Модель ${modelName} успешно загружена и активирована!`);
                setPullingModel(null);
                onClose();
                return;
              }
            } catch {
              continue;
            }
          }
        }
      }
    } catch (err) {
      notify(
        `Ошибка скачивания: ${err instanceof Error ? err.message : String(err)}`,
      );
      setPullingModel(null);
    }
  };

  const formatGb = (bytes: number) => (bytes / (1024 * 1024 * 1024)).toFixed(1);

  const isModelActive = (modelId: string, isRemote = false) => {
    if (!profile?.activeModel) return false;
    const current = profile.activeModel.trim().toLowerCase();
    const target = modelId.trim().toLowerCase();
    if (isRemote) {
      if (profile.isLocalActive) return false;
      return current === target;
    }
    if (!profile.isLocalActive) return false;
    if (current === target) return true;
    const currentBase = current.split(':')[0];
    const targetBase = target.split(':')[0];
    if (currentBase === targetBase) {
      const currentTag = current.split(':')[1] ?? '';
      const targetTag = target.split(':')[1] ?? '';
      if (
        !currentTag ||
        !targetTag ||
        currentTag === targetTag ||
        currentTag === 'latest' ||
        targetTag === 'latest'
      ) {
        return true;
      }
      if (current.startsWith(target) || target.startsWith(current)) {
        return true;
      }
    }
    return false;
  };

  const compatibleInstalled = (profile?.catalog ?? [])
    .filter(
      (model) =>
        model.isInstalled &&
        /LOCAL|OLLAMA/.test(model.providerType) &&
        model.requiredRamBytes <= (profile?.totalRamBytes ?? 0) * 0.82,
    )
    .sort((a, b) => a.requiredRamBytes - b.requiredRamBytes);
  const recommendedModel = profile?.catalog.find(
    (model) => model.id === profile.recommendedModelId,
  );
  const qualityModes = profile
    ? [
        {
          id: 'fast',
          title: 'Быстро',
          detail: 'Минимальная задержка для обзора и поиска.',
          icon: Zap,
          model: compatibleInstalled[0],
        },
        {
          id: 'balanced',
          title: 'Баланс',
          detail: 'Рекомендуемый режим для ежедневного анализа.',
          icon: Sparkles,
          model:
            recommendedModel ??
            compatibleInstalled[Math.floor(compatibleInstalled.length / 2)],
        },
        {
          id: 'quality',
          title: 'Лучшее качество',
          detail: 'Самая сильная установленная модель в доступной памяти.',
          icon: Cpu,
          model: compatibleInstalled[compatibleInstalled.length - 1],
        },
      ]
    : [];

  return (
    <Modal
      title="Подбор и настройка ИИ-модели под ресурсы сервера"
      onClose={onClose}
      className="modal-wide"
      width="960px"
    >
      <div
        className="model-manager-content"
        style={{
          display: 'flex',
          flexDirection: 'column',
          gap: '18px',
          width: '100%',
          padding: '20px 24px',
          boxSizing: 'border-box',
        }}
      >
        {isLoading && (
          <div className="loading-state">Анализ ресурсов хоста...</div>
        )}
        {isError && <ErrorState error={error} />}

        {profile && (
          <>
            {/* Active Model Status Banner */}
            <div
              style={{
                padding: '12px 16px',
                background: profile.activeModel
                  ? 'linear-gradient(90deg, rgba(43, 138, 62, 0.08) 0%, rgba(43, 138, 62, 0.02) 100%)'
                  : 'var(--surface-muted, #f8f9fa)',
                border: profile.activeModel
                  ? '1.5px solid #2b8a3e'
                  : '1px solid var(--border-subtle, #dee2e6)',
                borderRadius: '8px',
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
                gap: '12px',
                flexWrap: 'wrap',
              }}
            >
              <div
                style={{ display: 'flex', alignItems: 'center', gap: '10px' }}
              >
                <div
                  style={{
                    width: '12px',
                    height: '12px',
                    borderRadius: '50%',
                    background: profile.activeModel ? '#2b8a3e' : '#fa5252',
                    boxShadow: profile.activeModel ? '0 0 8px #2b8a3e' : 'none',
                    flexShrink: 0,
                  }}
                />
                <div>
                  <div
                    style={{
                      fontSize: '11px',
                      color: 'var(--text-muted, #666)',
                      fontWeight: 600,
                      textTransform: 'uppercase',
                      letterSpacing: '0.5px',
                    }}
                  >
                    Текущая активная модель платформы
                  </div>
                  <div
                    style={{
                      fontSize: '15px',
                      fontWeight: 700,
                      color: profile.activeModel ? '#2b8a3e' : '#c92a2a',
                      marginTop: '2px',
                      display: 'flex',
                      alignItems: 'center',
                      gap: '8px',
                    }}
                  >
                    <span>
                      {profile.activeModel || 'Не выбрана (ИИ отключен)'}
                    </span>
                  </div>
                </div>
              </div>
              {profile.activeModel && (
                <div
                  style={{ display: 'flex', alignItems: 'center', gap: '8px' }}
                >
                  <span
                    style={{
                      fontSize: '11.5px',
                      fontWeight: 600,
                      padding: '3px 8px',
                      borderRadius: '4px',
                      background: profile.isLocalActive ? '#e6fcf5' : '#e7f5ff',
                      color: profile.isLocalActive ? '#0ca678' : '#1c7ed6',
                      border: profile.isLocalActive
                        ? '1px solid #96f2d7'
                        : '1px solid #a5d8ff',
                    }}
                  >
                    {profile.isLocalActive
                      ? '💻 Локальный Ollama'
                      : '☁️ Удаленный облачный API'}
                  </span>
                  <Badge tone="positive">✓ АКТИВНА СЕЙЧАС</Badge>
                </div>
              )}
            </div>

            {/* Specs bar */}
            <div
              style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))',
                gap: '12px',
                padding: '16px',
                background: 'var(--surface-muted, #f8f9fa)',
                borderRadius: '8px',
                border: '1px solid var(--border-subtle, #e9ecef)',
              }}
            >
              <div>
                <small
                  className="muted"
                  style={{ display: 'flex', alignItems: 'center', gap: '5px' }}
                >
                  <HardDrive size={14} /> RAM всего / свободно
                </small>
                <div
                  style={{
                    fontSize: '15px',
                    fontWeight: 600,
                    marginTop: '2px',
                  }}
                >
                  {formatGb(profile.totalRamBytes)} GB /{' '}
                  {formatGb(profile.freeRamBytes)} GB
                </div>
              </div>
              <div>
                <small
                  className="muted"
                  style={{ display: 'flex', alignItems: 'center', gap: '5px' }}
                >
                  <Cpu size={14} /> Процессор
                </small>
                <div
                  style={{
                    fontSize: '15px',
                    fontWeight: 600,
                    marginTop: '2px',
                  }}
                >
                  {profile.cpuCores} ядер CPU
                </div>
              </div>
              {profile.gpuName && (
                <div>
                  <small
                    className="muted"
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      gap: '5px',
                    }}
                  >
                    <Zap size={14} /> Графика GPU
                  </small>
                  <div
                    style={{
                      fontSize: '15px',
                      fontWeight: 600,
                      marginTop: '2px',
                    }}
                  >
                    {profile.gpuName} ({formatGb(profile.gpuVramBytes)} GB VRAM)
                  </div>
                </div>
              )}
              <div>
                <small
                  className="muted"
                  style={{ display: 'flex', alignItems: 'center', gap: '5px' }}
                >
                  <Server size={14} /> Движок Ollama
                </small>
                <div
                  style={{
                    fontSize: '15px',
                    fontWeight: 600,
                    marginTop: '2px',
                  }}
                >
                  {profile.ollamaAvailable ? (
                    <span style={{ color: 'var(--positive, #2b8a3e)' }}>
                      ● Доступен
                    </span>
                  ) : (
                    <span style={{ color: 'var(--warning, #e67700)' }}>
                      ○ Не запущен
                    </span>
                  )}
                </div>
              </div>
            </div>

            {/* Recommendation reason banner */}
            <div
              style={{
                padding: '14px 16px',
                background: 'var(--surface-highlight, #e3fafc)',
                border: '1px solid #99e9f2',
                borderRadius: '8px',
                display: 'flex',
                gap: '12px',
                alignItems: 'flex-start',
              }}
            >
              <Sparkles
                size={20}
                style={{ color: '#0c8599', flexShrink: 0, marginTop: '2px' }}
              />
              <div>
                <strong style={{ color: '#0c8599', fontSize: '14px' }}>
                  Рекомендация системы:
                </strong>
                <p
                  style={{
                    margin: '4px 0 0 0',
                    fontSize: '13px',
                    lineHeight: 1.5,
                  }}
                >
                  {profile.recommendationReason}
                </p>
              </div>
            </div>

            <section className="model-quality-section">
              <div>
                <strong>Режим качества объяснений</strong>
                <p>
                  Выберите результат, а техническую модель система подберет
                  автоматически.
                </p>
              </div>
              <div className="model-quality-grid">
                {qualityModes.map((mode) => {
                  const ModelIcon = mode.icon;
                  const active = Boolean(
                    mode.model && isModelActive(mode.model.id),
                  );
                  return (
                    <button
                      type="button"
                      className={active ? 'active' : ''}
                      key={mode.id}
                      disabled={
                        !mode.model ||
                        selectMutation.isPending ||
                        Boolean(pullingModel)
                      }
                      onClick={() => {
                        if (!mode.model) return;
                        if (mode.model.isInstalled) {
                          selectMutation.mutate({
                            model: mode.model.id,
                            providerType: 'LOCAL',
                          });
                        } else {
                          void handleStartPull(mode.model.id);
                        }
                      }}
                    >
                      <ModelIcon size={19} />
                      <span>
                        <strong>{mode.title}</strong>
                        <small>{mode.detail}</small>
                        <code>
                          {mode.model?.name ?? 'Нет совместимой модели'}
                        </code>
                      </span>
                      {active && <Badge tone="positive">Активен</Badge>}
                    </button>
                  );
                })}
              </div>
              <Button
                variant="ghost"
                onClick={() => setShowAdvanced(!showAdvanced)}
              >
                {showAdvanced
                  ? 'Скрыть расширенные настройки'
                  : 'Расширенный каталог моделей'}
              </Button>
            </section>

            {/* Tabs */}
            <div
              style={{
                display: showAdvanced ? 'flex' : 'none',
                gap: '10px',
                borderBottom: '1px solid var(--border-subtle, #dee2e6)',
              }}
            >
              <button
                type="button"
                className={`tab-btn ${activeTab === 'local' ? 'active strong' : ''}`}
                style={{
                  padding: '10px 18px',
                  background: 'none',
                  border: 'none',
                  borderBottom:
                    activeTab === 'local'
                      ? '2px solid var(--accent, #1098ad)'
                      : '2px solid transparent',
                  cursor: 'pointer',
                  fontWeight: activeTab === 'local' ? 600 : 400,
                  fontSize: '13.5px',
                  display: 'flex',
                  alignItems: 'center',
                  gap: '6px',
                }}
                onClick={() => setActiveTab('local')}
              >
                <Server size={15} />
                Локальные модели (Ollama)
              </button>
              <button
                type="button"
                className={`tab-btn ${activeTab === 'remote' ? 'active strong' : ''}`}
                style={{
                  padding: '10px 18px',
                  background: 'none',
                  border: 'none',
                  borderBottom:
                    activeTab === 'remote'
                      ? '2px solid var(--accent, #1098ad)'
                      : '2px solid transparent',
                  cursor: 'pointer',
                  fontWeight: activeTab === 'remote' ? 600 : 400,
                  fontSize: '13.5px',
                  display: 'flex',
                  alignItems: 'center',
                  gap: '6px',
                }}
                onClick={() => setActiveTab('remote')}
              >
                <Zap size={15} />
                Облачные модели (OpenAI / Claude / Gemini)
              </button>
            </div>

            {/* Local models tab */}
            {showAdvanced && activeTab === 'local' && (
              <div
                style={{
                  display: 'flex',
                  flexDirection: 'column',
                  gap: '12px',
                }}
              >
                {!profile.ollamaAvailable && (
                  <div
                    style={{
                      padding: '10px 12px',
                      background: '#fff9db',
                      border: '1px solid #ffe066',
                      borderRadius: '6px',
                      fontSize: '13px',
                      display: 'flex',
                      gap: '8px',
                      alignItems: 'center',
                    }}
                  >
                    <AlertTriangle
                      size={16}
                      style={{ color: '#f08c00', flexShrink: 0 }}
                    />
                    <span>
                      Локальный сервис Ollama не отвечает на{' '}
                      <code>{profile.ollamaUrl}</code>. Запустите{' '}
                      <code>ollama serve</code> или переключитесь на вкладку
                      «Облачные модели».
                    </span>
                    <Button
                      variant="ghost"
                      onClick={() => void refetch()}
                      title="Обновить статус"
                    >
                      <RefreshCw size={14} />
                    </Button>
                  </div>
                )}

                {/* Search & Category Filter Toolbar */}
                <div
                  style={{
                    display: 'flex',
                    flexDirection: 'column',
                    gap: '8px',
                    padding: '10px 12px',
                    background: 'var(--surface-muted, #f8f9fa)',
                    borderRadius: '8px',
                    border: '1px solid var(--border-subtle, #dee2e6)',
                  }}
                >
                  <div
                    style={{
                      display: 'flex',
                      gap: '8px',
                      alignItems: 'center',
                    }}
                  >
                    <div style={{ position: 'relative', flex: 1 }}>
                      <Search
                        size={15}
                        style={{
                          position: 'absolute',
                          left: '10px',
                          top: '50%',
                          transform: 'translateY(-50%)',
                          color: 'var(--muted, #888)',
                        }}
                      />
                      <input
                        type="text"
                        placeholder="Поиск модели (например: deepseek, coder, r1, llama, 7b, 14b)..."
                        value={searchTerm}
                        onChange={(e) => setSearchTerm(e.target.value)}
                        style={{
                          width: '100%',
                          padding: '7px 10px 7px 32px',
                          borderRadius: '6px',
                          border: '1px solid var(--border-subtle, #ced4da)',
                          fontSize: '13px',
                          boxSizing: 'border-box',
                        }}
                      />
                    </div>
                    {searchTerm && (
                      <Button
                        variant="ghost"
                        onClick={() => setSearchTerm('')}
                        style={{ fontSize: '12px', padding: '4px 8px' }}
                      >
                        Сброс
                      </Button>
                    )}
                  </div>

                  {/* Category filter pills */}
                  <div
                    style={{ display: 'flex', gap: '6px', flexWrap: 'wrap' }}
                  >
                    {CATEGORY_FILTERS.map((cat) => {
                      const isActive = selectedCategory === cat.id;
                      return (
                        <button
                          key={cat.id}
                          type="button"
                          onClick={() => setSelectedCategory(cat.id)}
                          style={{
                            padding: '4px 10px',
                            borderRadius: '20px',
                            border: isActive
                              ? '1.5px solid var(--accent, #1098ad)'
                              : '1px solid var(--border-subtle, #ced4da)',
                            background: isActive
                              ? 'var(--accent, #1098ad)'
                              : 'var(--surface, #ffffff)',
                            color: isActive ? '#ffffff' : 'var(--text, #333)',
                            fontSize: '11.5px',
                            fontWeight: isActive ? 600 : 400,
                            cursor: 'pointer',
                            transition: 'all 0.15s ease',
                          }}
                        >
                          {cat.label}
                        </button>
                      );
                    })}
                  </div>
                </div>

                {/* Model cards list */}
                <div
                  style={{
                    display: 'flex',
                    flexDirection: 'column',
                    gap: '10px',
                    maxHeight: '380px',
                    overflowY: 'auto',
                  }}
                >
                  {profile.catalog
                    .filter((m) => m.providerType === 'OLLAMA')
                    .filter((m) => {
                      if (
                        selectedCategory !== 'all' &&
                        m.category !== selectedCategory
                      ) {
                        return false;
                      }
                      if (searchTerm.trim()) {
                        const q = searchTerm.toLowerCase().trim();
                        const matchName = m.name.toLowerCase().includes(q);
                        const matchId = m.id.toLowerCase().includes(q);
                        const matchDesc = m.description
                          .toLowerCase()
                          .includes(q);
                        const matchBest = (m.bestFor ?? '')
                          .toLowerCase()
                          .includes(q);
                        const matchReqs = (m.hardwareReqs ?? '')
                          .toLowerCase()
                          .includes(q);
                        return (
                          matchName ||
                          matchId ||
                          matchDesc ||
                          matchBest ||
                          matchReqs
                        );
                      }
                      return true;
                    })
                    .map((model) => {
                      const isActive = isModelActive(model.id, false);
                      return (
                        <div
                          key={model.id}
                          style={{
                            padding: '12px 14px',
                            border: isActive
                              ? '2px solid #2b8a3e'
                              : model.isRecommended
                                ? '2px solid var(--accent, #1098ad)'
                                : '1px solid var(--border-subtle, #dee2e6)',
                            borderRadius: '8px',
                            display: 'flex',
                            flexDirection: 'column',
                            gap: '8px',
                            background: isActive
                              ? 'rgba(43, 138, 62, 0.05)'
                              : model.isRecommended
                                ? 'var(--surface-highlight, #f8fcfe)'
                                : 'var(--surface, #ffffff)',
                            boxShadow: isActive
                              ? '0 0 0 1px #2b8a3e, 0 2px 8px rgba(43, 138, 62, 0.15)'
                              : '0 1px 2px rgba(0, 0, 0, 0.03)',
                            position: 'relative',
                          }}
                        >
                          {/* Header: Name, parameter, badges, action button */}
                          <div
                            style={{
                              display: 'flex',
                              justifyContent: 'space-between',
                              alignItems: 'flex-start',
                              gap: '12px',
                            }}
                          >
                            <div style={{ flex: 1 }}>
                              <div
                                style={{
                                  display: 'flex',
                                  alignItems: 'center',
                                  gap: '6px',
                                  flexWrap: 'wrap',
                                }}
                              >
                                <strong
                                  style={{
                                    fontSize: '13.5px',
                                    color: isActive
                                      ? '#1b4721'
                                      : 'var(--text, #1e2925)',
                                  }}
                                >
                                  {model.name}
                                </strong>
                                <span
                                  className="badge mono"
                                  style={{
                                    fontSize: '11px',
                                    padding: '1px 5px',
                                    background: 'var(--surface-muted, #f1f3f5)',
                                  }}
                                >
                                  {model.parameterSize}
                                </span>
                                {(() => {
                                  const catTag = model.category
                                    ? CATEGORY_TAGS[model.category]
                                    : undefined;
                                  if (!catTag) return null;
                                  return (
                                    <span
                                      style={{
                                        fontSize: '10.5px',
                                        padding: '1px 6px',
                                        borderRadius: '4px',
                                        fontWeight: 600,
                                        background: catTag.bg,
                                        color: catTag.color,
                                      }}
                                    >
                                      {catTag.label}
                                    </span>
                                  );
                                })()}
                                {isActive && (
                                  <Badge tone="positive">
                                    ✓ ТЕКУЩАЯ АКТИВНАЯ МОДЕЛЬ
                                  </Badge>
                                )}
                                {model.isRecommended && !isActive && (
                                  <Badge tone="info">
                                    Рекомендуется сервером
                                  </Badge>
                                )}
                                {model.isInstalled && !isActive && (
                                  <span
                                    style={{
                                      fontSize: '11px',
                                      color: 'var(--positive, #2b8a3e)',
                                      display: 'inline-flex',
                                      alignItems: 'center',
                                      gap: '3px',
                                      fontWeight: 600,
                                    }}
                                  >
                                    <CheckCircle2 size={12} /> Установлена
                                  </span>
                                )}
                              </div>
                              <p
                                style={{
                                  margin: '3px 0 0 0',
                                  fontSize: '12px',
                                  color: 'var(--text-muted, #555)',
                                  lineHeight: 1.4,
                                }}
                              >
                                {model.description}
                              </p>
                            </div>

                            <div style={{ flexShrink: 0 }}>
                              {pullingModel === model.id ? (
                                <div
                                  style={{
                                    minWidth: '160px',
                                    textAlign: 'right',
                                  }}
                                >
                                  <div
                                    style={{
                                      fontSize: '11px',
                                      marginBottom: '3px',
                                      fontWeight: 600,
                                    }}
                                  >
                                    {pullProgress.percent}% (
                                    {pullProgress.status})
                                  </div>
                                  <progress
                                    value={pullProgress.percent}
                                    max={100}
                                    style={{ width: '100%' }}
                                  />
                                </div>
                              ) : isActive ? (
                                <div
                                  style={{
                                    display: 'inline-flex',
                                    alignItems: 'center',
                                    gap: '6px',
                                    padding: '6px 14px',
                                    background: '#2b8a3e',
                                    color: '#ffffff',
                                    borderRadius: '6px',
                                    fontSize: '12px',
                                    fontWeight: 600,
                                    boxShadow:
                                      '0 1px 3px rgba(43, 138, 62, 0.3)',
                                  }}
                                >
                                  <CheckCircle2 size={15} /> Используется сейчас
                                </div>
                              ) : model.isInstalled ? (
                                <Button
                                  variant="secondary"
                                  busy={selectMutation.isPending}
                                  onClick={() =>
                                    selectMutation.mutate({
                                      model: model.id,
                                      providerType: 'LOCAL',
                                    })
                                  }
                                >
                                  Переключиться на эту
                                </Button>
                              ) : (
                                <Button
                                  variant="secondary"
                                  disabled={
                                    !profile.ollamaAvailable ||
                                    pullingModel !== null
                                  }
                                  onClick={() => void handleStartPull(model.id)}
                                >
                                  <DownloadCloud size={14} /> Скачать и выбрать
                                </Button>
                              )}
                            </div>
                          </div>

                          {/* Tasks & Hardware Requirements block */}
                          <div
                            style={{
                              display: 'grid',
                              gridTemplateColumns: '1.2fr 1fr',
                              gap: '10px',
                              padding: '7px 10px',
                              background: 'var(--surface-muted, #f8f9fa)',
                              borderRadius: '6px',
                              fontSize: '11.5px',
                              border: '1px solid var(--border-subtle, #edf2f7)',
                            }}
                          >
                            <div>
                              <span
                                style={{ fontWeight: 700, color: '#334155' }}
                              >
                                🎯 Для каких задач:{' '}
                              </span>
                              <span style={{ color: '#1e293b' }}>
                                {model.bestFor ||
                                  'Анализ зависимостей и архитектуры'}
                              </span>
                            </div>
                            <div>
                              <span
                                style={{ fontWeight: 700, color: '#334155' }}
                              >
                                💻 Требования:{' '}
                              </span>
                              <span style={{ color: '#1e293b' }}>
                                {model.hardwareReqs ||
                                  formatGb(model.requiredRamBytes) + ' GB RAM'}
                              </span>
                            </div>
                          </div>
                        </div>
                      );
                    })}
                </div>

                {/* Custom Ollama model input */}
                <div
                  style={{
                    padding: '12px 14px',
                    border: '1px dashed var(--border-subtle, #dee2e6)',
                    borderRadius: '8px',
                    background: 'var(--surface-muted, #fbfbfb)',
                    display: 'flex',
                    flexDirection: 'column',
                    gap: '8px',
                    marginTop: '4px',
                  }}
                >
                  <label
                    style={{
                      fontSize: '13px',
                      fontWeight: 600,
                      display: 'flex',
                      alignItems: 'center',
                      gap: '6px',
                    }}
                  >
                    <DownloadCloud
                      size={15}
                      style={{ color: 'var(--accent, #1098ad)' }}
                    />
                    Загрузить произвольную модель из библиотеки Ollama:
                  </label>
                  <div style={{ display: 'flex', gap: '8px' }}>
                    <input
                      type="text"
                      placeholder="например: mistral, deepseek-r1:14b, codestral, starcoder2:15b, llama3.3:70b"
                      value={customOllamaModel}
                      onChange={(e) => setCustomOllamaModel(e.target.value)}
                      style={{
                        flex: 1,
                        padding: '8px',
                        borderRadius: '4px',
                        border: '1px solid var(--border-subtle, #ced4da)',
                      }}
                      disabled={pullingModel !== null}
                    />
                    <Button
                      variant="secondary"
                      disabled={
                        !profile.ollamaAvailable ||
                        pullingModel !== null ||
                        !customOllamaModel.trim()
                      }
                      onClick={() =>
                        void handleStartPull(customOllamaModel.trim())
                      }
                    >
                      <DownloadCloud size={14} /> Скачать
                    </Button>
                  </div>
                  <small className="muted" style={{ fontSize: '11px' }}>
                    Поддерживаются любые названия и квантования из официальной
                    библиотеки{' '}
                    <a
                      href="https://ollama.com/library"
                      target="_blank"
                      rel="noreferrer"
                      style={{ color: 'var(--accent, #1098ad)' }}
                    >
                      ollama.com/library
                    </a>
                    .
                  </small>
                </div>
              </div>
            )}

            {/* Remote models tab */}
            {showAdvanced && activeTab === 'remote' && (
              <div
                style={{
                  display: 'flex',
                  flexDirection: 'column',
                  gap: '14px',
                }}
              >
                {!profile.isLocalActive && profile.activeModel && (
                  <div
                    style={{
                      padding: '10px 14px',
                      background: 'rgba(43, 138, 62, 0.06)',
                      border: '1.5px solid #2b8a3e',
                      borderRadius: '6px',
                      display: 'flex',
                      alignItems: 'center',
                      gap: '8px',
                      fontSize: '13px',
                    }}
                  >
                    <CheckCircle2
                      size={16}
                      style={{ color: '#2b8a3e', flexShrink: 0 }}
                    />
                    <span>
                      Текущая активная модель:{' '}
                      <strong style={{ color: '#2b8a3e' }}>
                        {profile.activeModel}
                      </strong>{' '}
                      (облачный режим включен)
                    </span>
                  </div>
                )}

                {/* Remote Presets Chips */}
                <div
                  style={{
                    display: 'flex',
                    flexDirection: 'column',
                    gap: '6px',
                  }}
                >
                  <label
                    style={{
                      fontSize: '12px',
                      fontWeight: 600,
                      color: 'var(--text-muted, #666)',
                    }}
                  >
                    Популярные облачные пресеты:
                  </label>
                  <div
                    style={{ display: 'flex', gap: '6px', flexWrap: 'wrap' }}
                  >
                    {[
                      {
                        id: 'deepseek-chat',
                        label: '🔥 DeepSeek V3/R1 (Выгодно)',
                        url: 'https://api.deepseek.com',
                      },
                      {
                        id: 'gpt-4o-mini',
                        label: '⚡ GPT-4o Mini (Быстрый)',
                        url: '',
                      },
                      {
                        id: 'claude-3-7-sonnet',
                        label: '👑 Claude 3.7 Sonnet (Топ кодинг)',
                        url: '',
                      },
                      {
                        id: 'gemini-2.5-pro',
                        label: '🌌 Gemini 2.5 Pro (1M контекст)',
                        url: '',
                      },
                    ].map((preset) => {
                      const isPresetActive = isModelActive(preset.id, true);
                      return (
                        <button
                          key={preset.id}
                          type="button"
                          onClick={() => {
                            setCloudModel(preset.id);
                            if (preset.url) {
                              setCloudBaseUrl(preset.url);
                            } else {
                              setCloudBaseUrl('');
                            }
                          }}
                          style={{
                            padding: '4px 10px',
                            borderRadius: '16px',
                            border: isPresetActive
                              ? '2px solid #2b8a3e'
                              : cloudModel === preset.id
                                ? '1.5px solid var(--accent, #1098ad)'
                                : '1px solid var(--border-subtle, #ced4da)',
                            background: isPresetActive
                              ? 'rgba(43, 138, 62, 0.1)'
                              : cloudModel === preset.id
                                ? 'rgba(16, 152, 173, 0.1)'
                                : 'var(--surface, #ffffff)',
                            color: isPresetActive
                              ? '#2b8a3e'
                              : cloudModel === preset.id
                                ? '#0c8599'
                                : 'var(--text, #333)',
                            fontSize: '11.5px',
                            fontWeight:
                              isPresetActive || cloudModel === preset.id
                                ? 600
                                : 400,
                            cursor: 'pointer',
                            display: 'inline-flex',
                            alignItems: 'center',
                            gap: '4px',
                          }}
                        >
                          {preset.label}
                          {isPresetActive && (
                            <span
                              style={{
                                fontSize: '10px',
                                background: '#2b8a3e',
                                color: '#fff',
                                padding: '1px 5px',
                                borderRadius: '8px',
                              }}
                            >
                              ✓ активна
                            </span>
                          )}
                        </button>
                      );
                    })}
                  </div>
                </div>

                <Field label="Выберите облачную модель">
                  <select
                    value={cloudModel}
                    onChange={(e) => {
                      const val = e.target.value;
                      setCloudModel(val);
                      if (val === 'deepseek-chat' && !cloudBaseUrl) {
                        setCloudBaseUrl('https://api.deepseek.com');
                      }
                    }}
                    style={{
                      width: '100%',
                      padding: '8px',
                      borderRadius: '4px',
                      border: '1px solid var(--border-subtle, #ced4da)',
                    }}
                  >
                    <optgroup label="DeepSeek (Рекомендуется по цене и качеству)">
                      <option value="deepseek-chat">
                        DeepSeek V3 / R1 (api.deepseek.com — в 10 раз дешевле,
                        топ качество)
                      </option>
                    </optgroup>
                    <optgroup label="OpenAI">
                      <option value="gpt-4o">
                        OpenAI GPT-4o (флагман, максимальная глубина)
                      </option>
                      <option value="gpt-4o-mini">
                        OpenAI GPT-4o Mini (быстрая и экономичная)
                      </option>
                      <option value="o3-mini">
                        OpenAI o3-mini (глубокие рассуждения / reasoning)
                      </option>
                    </optgroup>
                    <optgroup label="Anthropic">
                      <option value="claude-3-7-sonnet">
                        Anthropic Claude 3.7 Sonnet (гибридное мышление, топ
                        кодинга)
                      </option>
                      <option value="claude-3-5-sonnet">
                        Anthropic Claude 3.5 Sonnet (высочайшая точность)
                      </option>
                      <option value="claude-3-5-haiku">
                        Anthropic Claude 3.5 Haiku (мгновенный отклик)
                      </option>
                    </optgroup>
                    <optgroup label="Google DeepMind">
                      <option value="gemini-2.5-pro">
                        Google Gemini 2.5 Pro (контекст 1M токенов, весь проект)
                      </option>
                      <option value="gemini-2.5-flash">
                        Google Gemini 2.5 Flash (быстрая и точная)
                      </option>
                    </optgroup>
                    <optgroup label="Кастомный провайдер">
                      <option value="custom">
                        Другая модель (OpenRouter / Groq / vLLM / кастомная)
                      </option>
                    </optgroup>
                  </select>
                </Field>

                {/* Info Card for Selected Cloud Model */}
                <div
                  style={{
                    padding: '8px 12px',
                    background: 'var(--surface-muted, #f8f9fa)',
                    borderRadius: '6px',
                    border: '1px solid var(--border-subtle, #dee2e6)',
                    fontSize: '12px',
                    lineHeight: 1.5,
                  }}
                >
                  <span
                    style={{ fontWeight: 700, color: 'var(--accent, #1098ad)' }}
                  >
                    💡 Преимущество:{' '}
                  </span>
                  {cloudModel === 'deepseek-chat' &&
                    'Прямой доступ к официальному API DeepSeek: непревзойденное качество логики и кода по минимальной стоимости.'}
                  {cloudModel === 'gpt-4o' &&
                    'Универсальный флагман OpenAI: видит неочевидные связи и сложную бизнес-архитектуру.'}
                  {cloudModel === 'gpt-4o-mini' &&
                    'Сверхбыстрый и дешевый инференс для регулярного мониторинга коммитов и pull request-ов.'}
                  {cloudModel === 'o3-mini' &&
                    'Аппаратное пошаговое рассуждение OpenAI для распутывания тяжелого легаси-кода.'}
                  {cloudModel === 'claude-3-7-sonnet' &&
                    'Новейшая гибридная модель Anthropic, занимающая 1-е место в мире по программированию.'}
                  {cloudModel === 'claude-3-5-sonnet' &&
                    'Золотой стандарт детальной бизнес-документации и извлечения сущностей.'}
                  {cloudModel === 'claude-3-5-haiku' &&
                    'Компактная и мгновенная модель Anthropic с минимальной задержкой ответа.'}
                  {cloudModel === 'gemini-2.5-pro' &&
                    'Гигантский контекст 1 000 000 токенов — способна проанализировать все файлы репозитория в едином окне.'}
                  {cloudModel === 'gemini-2.5-flash' &&
                    'Быстрая мультимодальная модель от Google с превосходным русским языком.'}
                  {cloudModel === 'custom' &&
                    'Подключение любого совместимого с OpenAI API сервиса (OpenRouter, Groq, Together, vLLM, локальный хост).'}
                  <div
                    style={{
                      marginTop: '4px',
                      color: 'var(--positive, #2b8a3e)',
                      fontWeight: 600,
                      fontSize: '11.5px',
                    }}
                  >
                    ✓ 0 MB RAM хоста: сервер не нагружается, вычисления
                    выполняются в защищенной облачной инфраструктуре.
                  </div>
                </div>

                {cloudModel === 'custom' && (
                  <Field label="Название модели">
                    <input
                      type="text"
                      placeholder="например: meta-llama/llama-3.3-70b-instruct или mistral-large"
                      value={customRemoteModel}
                      onChange={(e) => setCustomRemoteModel(e.target.value)}
                      style={{
                        width: '100%',
                        padding: '8px',
                        borderRadius: '4px',
                        border: '1px solid var(--border-subtle, #ced4da)',
                      }}
                      required
                    />
                  </Field>
                )}

                <Field label="API Ключ провайдера">
                  <div style={{ position: 'relative' }}>
                    <input
                      type="password"
                      placeholder={
                        cloudModel.includes('claude')
                          ? 'sk-ant-...'
                          : cloudModel.includes('gemini')
                            ? 'AIza...'
                            : 'sk-...'
                      }
                      value={cloudApiKey}
                      onChange={(e) => setCloudApiKey(e.target.value)}
                      style={{
                        width: '100%',
                        padding: '8px',
                        borderRadius: '4px',
                        border: '1px solid var(--border-subtle, #ced4da)',
                      }}
                      required
                    />
                  </div>
                  <small className="muted">
                    Ключ безопасно сохраняется в конфигурации рабочего
                    пространства.
                  </small>
                </Field>

                <Field label="Base URL API (опционально, по умолчанию используется стандартный endpoint провайдера)">
                  <input
                    type="url"
                    placeholder={
                      cloudModel === 'deepseek-chat'
                        ? 'https://api.deepseek.com'
                        : cloudModel.includes('claude')
                          ? 'https://api.anthropic.com/v1'
                          : cloudModel.includes('gemini')
                            ? 'https://generativelanguage.googleapis.com/v1beta/openai'
                            : 'https://api.openai.com/v1'
                    }
                    value={cloudBaseUrl}
                    onChange={(e) => setCloudBaseUrl(e.target.value)}
                    style={{
                      width: '100%',
                      padding: '8px',
                      borderRadius: '4px',
                      border: '1px solid var(--border-subtle, #ced4da)',
                    }}
                  />
                </Field>

                <Button
                  variant="primary"
                  disabled={
                    !cloudApiKey.trim() ||
                    (cloudModel === 'custom' && !customRemoteModel.trim())
                  }
                  busy={selectMutation.isPending}
                  onClick={() => {
                    const resolvedModel =
                      cloudModel === 'custom'
                        ? customRemoteModel.trim()
                        : cloudModel;
                    selectMutation.mutate({
                      model: resolvedModel,
                      providerType: 'REMOTE',
                      apiKey: cloudApiKey.trim(),
                      baseUrl: cloudBaseUrl.trim() || undefined,
                    });
                  }}
                >
                  <KeyRound size={15} /> Сохранить и активировать модель
                </Button>
              </div>
            )}
          </>
        )}

        <footer
          className="form-footer"
          style={{
            marginTop: '12px',
            display: 'flex',
            justifyContent: 'flex-end',
          }}
        >
          <Button onClick={onClose}>{t('cancel')}</Button>
        </footer>
      </div>
    </Modal>
  );
}
