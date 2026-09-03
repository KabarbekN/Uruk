import { create } from 'zustand';
import { CheckCircle2, X } from 'lucide-react';
import { IconButton } from './index';
import { useT } from '../lib/i18n';

const useNotifications = create<{
  message: string;
  set: (message: string) => void;
}>((set) => ({ message: '', set: (message) => set({ message }) }));
let dismissTimer: ReturnType<typeof setTimeout>;
export function notify(message: string) {
  clearTimeout(dismissTimer);
  useNotifications.getState().set(message);
  dismissTimer = setTimeout(() => useNotifications.getState().set(''), 5000);
}
export function Notifications() {
  const message = useNotifications((state) => state.message);
  const { t } = useT();
  return (
    <div className="toast-region" aria-live="polite" aria-atomic="true">
      {message && (
        <div className="toast">
          <CheckCircle2 size={17} />
          <span>{message}</span>
          <IconButton
            icon={X}
            label={t('close')}
            onClick={() => useNotifications.getState().set('')}
          />
        </div>
      )}
    </div>
  );
}
