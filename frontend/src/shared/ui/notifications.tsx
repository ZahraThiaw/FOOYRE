import { CheckCircle2, AlertCircle, X } from 'lucide-react';
import { useCallback, useMemo, useRef, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import { NotificationContext, type NotificationKind, type Notify } from './useNotify';

interface Notification {
  readonly id: number;
  readonly kind: NotificationKind;
  /** Texte DÉJÀ traduit. */
  readonly message: string;
}

const AUTO_DISMISS_MS = 6000;

/**
 * Notifications de réussite ou d'échec (région `aria-live`, fermeture automatique). Réussite :
 * feuille blanche au fil d'or ; échec : terre cuite (couleur d'erreur validée), toujours avec
 * une icône et un texte.
 */
export function NotificationProvider({ children }: { readonly children: ReactNode }) {
  const { t } = useTranslation();
  const [items, setItems] = useState<readonly Notification[]>([]);
  const nextId = useRef(1);

  const dismiss = useCallback((id: number) => {
    setItems((current) => current.filter((item) => item.id !== id));
  }, []);

  const notify = useCallback<Notify>(
    (kind, message) => {
      const id = nextId.current;
      nextId.current += 1;
      setItems((current) => [...current, { id, kind, message }]);
      setTimeout(() => dismiss(id), AUTO_DISMISS_MS);
    },
    [dismiss],
  );

  const value = useMemo(() => notify, [notify]);

  return (
    <NotificationContext.Provider value={value}>
      {children}
      <div
        aria-live="polite"
        className="pointer-events-none fixed inset-x-0 bottom-0 z-50 flex flex-col items-center px-4 pb-4 sm:items-end"
      >
        {items.map((item) => (
          <div
            key={item.id}
            role={item.kind === 'error' ? 'alert' : 'status'}
            className={`pointer-events-auto mt-2 flex w-full max-w-sm items-start rounded-xl border border-l-4 bg-sheet p-3 text-sm text-ink shadow-raised ${
              item.kind === 'error' ? 'border-danger/30 border-l-danger' : 'border-line border-l-accent'
            }`}
          >
            {item.kind === 'error' ? (
              <AlertCircle aria-hidden="true" className="mr-2 mt-0.5 h-4 w-4 flex-none text-danger" />
            ) : (
              <CheckCircle2 aria-hidden="true" className="mr-2 mt-0.5 h-4 w-4 flex-none text-brand" />
            )}
            <p className="flex-1">{item.message}</p>
            <button
              type="button"
              onClick={() => dismiss(item.id)}
              aria-label={t('notifications.close')}
              className="ml-2 inline-flex h-6 w-6 items-center justify-center rounded text-ink-muted hover:bg-brand-soft hover:text-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
            >
              <X aria-hidden="true" className="h-4 w-4" />
            </button>
          </div>
        ))}
      </div>
    </NotificationContext.Provider>
  );
}

