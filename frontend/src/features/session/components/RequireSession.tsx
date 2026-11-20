import { useQueryClient } from '@tanstack/react-query';
import { Loader2 } from 'lucide-react';
import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { Navigate, Outlet, useLocation } from 'react-router';

import { deriveSessionState, sessionQueryKeys, useMe } from '@/features/session/api';
import { subscribeToAccessToken } from '@/shared/auth/accessTokenStore';
import { ApiErrorMessage } from '@/shared/ui/ApiErrorMessage';

/** État de navigation transmis à la page de connexion. */
export interface LoginLocationState {
  /** Chemin demandé avant la redirection (retour après connexion). */
  readonly from?: string;
  /** Une session existait et a expiré (message dédié). */
  readonly sessionExpired?: boolean;
  /** Déconnexion volontaire (message dédié). */
  readonly loggedOut?: boolean;
  /** Déconnexion locale sans confirmation de la révocation par le serveur. */
  readonly logoutUnconfirmed?: boolean;
}

/**
 * Protection des routes privées (confort d'affichage uniquement : le backend refuse toute
 * requête non authentifiée). Au chargement, `/me` est demandé ; sans jeton en mémoire, httpClient
 * tente UNE fois le renouvellement par le cookie HttpOnly (session restaurée après actualisation).
 * - session absente ou expirée (401) → page de connexion ;
 * - jeton effacé en cours d'utilisation (renouvellement impossible) → `/me` revérifié ;
 * - 403 / erreur → message, sans contenu protégé.
 */
export function RequireSession() {
  const { t } = useTranslation(['session']);
  const location = useLocation();
  const queryClient = useQueryClient();
  const query = useMe();
  const session = deriveSessionState(query);

  useEffect(
    () =>
      subscribeToAccessToken((token) => {
        if (token === null) {
          void queryClient.invalidateQueries({ queryKey: sessionQueryKeys.me });
        }
      }),
    [queryClient],
  );

  switch (session.status) {
    case 'loading':
      return (
        <div role="status" className="flex items-center justify-center py-16 text-ink-muted">
          <Loader2 aria-hidden="true" className="mr-2 h-5 w-5 animate-spin" />
          <span>{t('session:loading')}</span>
        </div>
      );
    case 'expired': {
      const state: LoginLocationState = {
        from: `${location.pathname}${location.search}`,
        // Des données de profil existaient : la session a expiré pendant l'utilisation.
        sessionExpired: query.data !== undefined,
      };
      return <Navigate to="/login" replace state={state} />;
    }
    case 'forbidden':
      return (
        <p role="alert" className="py-8">
          {t('session:forbidden')}
        </p>
      );
    case 'error':
      return (
        <div className="space-y-3 py-8">
          <p>{t('session:error')}</p>
          <ApiErrorMessage error={session.error} />
          <button
            type="button"
            onClick={() => void query.refetch()}
            className="inline-flex min-h-touch items-center rounded-md bg-brand px-4 font-medium text-brand-contrast focus:outline-none focus-visible:ring-2 focus-visible:ring-brand focus-visible:ring-offset-2"
          >
            {t('session:retry')}
          </button>
        </div>
      );
    case 'ready':
    case 'no-business':
      return <Outlet />;
  }
}
