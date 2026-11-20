import { createContext, useContext } from 'react';

export type NotificationKind = 'success' | 'error';

/** Affiche une notification ; `message` est un texte DÉJÀ traduit. */
export type Notify = (kind: NotificationKind, message: string) => void;

export const NotificationContext = createContext<Notify>(() => undefined);

/** Notifications de réussite ou d'échec (fournies par NotificationProvider). */
export function useNotify(): Notify {
  return useContext(NotificationContext);
}
