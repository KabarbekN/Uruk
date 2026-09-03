import {
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type ButtonHTMLAttributes,
  type ReactNode,
} from 'react';
import { createPortal } from 'react-dom';
import {
  AlertCircle,
  CheckCircle2,
  Circle,
  Loader2,
  RefreshCw,
  X,
  type LucideIcon,
} from 'lucide-react';
import { ApiError } from '../api/client';
import { useT } from '../lib/i18n';

export function Button({
  children,
  className = '',
  variant = 'secondary',
  busy,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: 'primary' | 'secondary' | 'danger' | 'ghost';
  busy?: boolean;
}) {
  return (
    <button
      type="button"
      className={`button ${variant} ${className}`}
      {...props}
      disabled={props.disabled || busy}
    >
      {busy && <Loader2 size={15} className="spin" aria-hidden="true" />}
      {children}
    </button>
  );
}

export function IconButton({
  icon: Icon,
  label,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  icon: LucideIcon;
  label: string;
}) {
  const anchor = useRef<HTMLSpanElement>(null);
  const tooltip = useRef<HTMLSpanElement>(null);
  const hovered = useRef(false);
  const focused = useRef(false);
  const [host, setHost] = useState<Element | null>(null);
  const id = useId();
  const show = (element: HTMLElement) =>
    setHost(element.closest('dialog') ?? document.body);

  useLayoutEffect(() => {
    const target = anchor.current;
    const tip = tooltip.current;
    if (!host || !target || !tip) return;
    const position = () => {
      const box = target.getBoundingClientRect();
      const { width, height } = tip.getBoundingClientRect();
      const viewportWidth = document.documentElement.clientWidth;
      const viewportHeight = window.innerHeight;
      const left = Math.max(
        8,
        Math.min(box.left + (box.width - width) / 2, viewportWidth - width - 8),
      );
      const above = box.top - height - 7;
      const top = Math.max(
        8,
        Math.min(
          above >= 8 ? above : box.bottom + 7,
          viewportHeight - height - 8,
        ),
      );
      tip.style.left = `${left}px`;
      tip.style.top = `${top}px`;
    };
    position();
    window.addEventListener('resize', position);
    window.addEventListener('scroll', position, true);
    return () => {
      window.removeEventListener('resize', position);
      window.removeEventListener('scroll', position, true);
    };
  }, [host, label]);

  return (
    <span
      ref={anchor}
      className="tooltip-wrap"
      onPointerEnter={(event) => {
        hovered.current = true;
        show(event.currentTarget);
      }}
      onPointerLeave={() => {
        hovered.current = false;
        if (!focused.current) setHost(null);
      }}
      onFocus={(event) => {
        focused.current = true;
        show(event.currentTarget);
      }}
      onBlur={() => {
        focused.current = false;
        if (!hovered.current) setHost(null);
      }}
      onKeyDown={(event) => {
        if (event.key === 'Escape') setHost(null);
      }}
    >
      <button
        type="button"
        {...props}
        className={`icon-button ${props.className ?? ''}`}
        aria-label={label}
        aria-describedby={host ? id : props['aria-describedby']}
      >
        <Icon size={17} aria-hidden="true" />
      </button>
      {host &&
        createPortal(
          <span ref={tooltip} id={id} className="tooltip" role="tooltip">
            {label}
          </span>,
          host,
        )}
    </span>
  );
}

export function Badge({
  children,
  tone = 'neutral',
}: {
  children: ReactNode;
  tone?: 'neutral' | 'positive' | 'warning' | 'negative' | 'info';
}) {
  return <span className={`badge ${tone}`}>{children}</span>;
}

export function Status({ value }: { value: string }) {
  const { label } = useT();
  const positive = /^(SUCCEEDED|COMPLETED|CONFIRMED|PROVEN|VERIFIED)$/.test(
    value,
  );
  const negative = /FAILED|REJECTED|REMOVED/.test(value);
  const warning =
    /PARTIAL|STALE|UNREVIEWED|NEEDS_REVIEW|UNRESOLVED|UNCERTAIN|CANCEL/.test(
      value,
    );
  const running = /RUNNING|QUEUED|STARTED|PENDING/.test(value);
  const Icon = positive
    ? CheckCircle2
    : negative || warning
      ? AlertCircle
      : running
        ? Loader2
        : Circle;
  return (
    <Badge
      tone={
        positive
          ? 'positive'
          : negative
            ? 'negative'
            : warning
              ? 'warning'
              : running
                ? 'info'
                : 'neutral'
      }
    >
      <Icon size={12} className={running ? 'spin' : ''} aria-hidden="true" />
      {label(value)}
    </Badge>
  );
}

export function Loading({ compact = false }: { compact?: boolean }) {
  const { t } = useT();
  return (
    <div className={compact ? 'loading compact' : 'loading'} role="status">
      <Loader2 size={20} className="spin" />
      <span>{t('loading')}</span>
      {!compact && (
        <div className="skeleton-lines">
          <i />
          <i />
          <i />
        </div>
      )}
    </div>
  );
}

export function ErrorState({
  error,
  retry,
  compact = false,
}: {
  error: unknown;
  retry?: () => void;
  compact?: boolean;
}) {
  const { t } = useT();
  const apiError = error instanceof ApiError ? error : null;
  const title =
    apiError?.reason === 'network'
      ? t('offline')
      : apiError?.reason === 'contract'
        ? t('contractError')
        : apiError?.status === 401
          ? t('unauthorized')
          : apiError?.status === 403
            ? t('forbidden')
            : t('requestFailed');
  return (
    <div className={`error-state ${compact ? 'compact' : ''}`} role="alert">
      <AlertCircle size={20} />
      <div>
        <strong>{title}</strong>
        {apiError?.problem.detail && (
          <p className="break-word">{apiError.problem.detail}</p>
        )}
        {apiError?.problem.correlationId && (
          <small>
            {t('correlation')}: {apiError.problem.correlationId}
          </small>
        )}
      </div>
      {retry && (
        <Button onClick={retry}>
          <RefreshCw size={14} />
          {t('retry')}
        </Button>
      )}
    </div>
  );
}

export function EmptyState({
  icon: Icon,
  title,
  detail,
  children,
}: {
  icon: LucideIcon;
  title: string;
  detail?: string;
  children?: ReactNode;
}) {
  return (
    <div className="empty-state">
      <span className="empty-icon">
        <Icon size={26} strokeWidth={1.5} />
      </span>
      <h2>{title}</h2>
      {detail && <p>{detail}</p>}
      {children}
    </div>
  );
}

export function PageHeader({
  title,
  eyebrow,
  children,
}: {
  title: string;
  eyebrow?: string;
  children?: ReactNode;
}) {
  return (
    <div className="page-heading">
      <div>
        {eyebrow && <div className="eyebrow">{eyebrow}</div>}
        <h1>{title}</h1>
      </div>
      {children && <div className="heading-actions">{children}</div>}
    </div>
  );
}

export function Modal({
  title,
  onClose,
  children,
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const id = useId();
  const { t } = useT();
  useEffect(() => {
    const dialog = ref.current;
    if (dialog && !dialog.open) dialog.showModal();
    return () => dialog?.close();
  }, []);
  return (
    <dialog
      className="modal"
      ref={ref}
      aria-labelledby={id}
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
    >
      <header>
        <h2 id={id}>{title}</h2>
        <IconButton icon={X} label={t('close')} onClick={onClose} />
      </header>
      {children}
    </dialog>
  );
}

export function Field({
  label,
  children,
  hint,
}: {
  label: string;
  children: ReactNode;
  hint?: string;
}) {
  return (
    <label className="field">
      <span>{label}</span>
      {children}
      {hint && <small>{hint}</small>}
    </label>
  );
}

export function SearchInput({
  value,
  onChange,
  label,
}: {
  value: string;
  onChange: (value: string) => void;
  label: string;
}) {
  return (
    <input
      type="search"
      className="search-input"
      aria-label={label}
      placeholder={label}
      value={value}
      onChange={(event) => onChange(event.target.value)}
    />
  );
}

export function JsonView({ value, label }: { value: unknown; label?: string }) {
  return (
    <pre className="json-view" tabIndex={0} aria-label={label}>
      {JSON.stringify(value, null, 2)}
    </pre>
  );
}
