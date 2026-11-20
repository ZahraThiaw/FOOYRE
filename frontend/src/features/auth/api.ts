/**
 * Connexion, renouvellement et déconnexion (ADR‑04, MVP‑1).
 *
 * - Le jeton d'accès reçu est conservé EN MÉMOIRE uniquement (accessTokenStore).
 * - Le refresh token n'est jamais vu par le frontend : cookie `HttpOnly` posé par le backend,
 *   limité à `Path=/api/v1/auth`. `X-Requested-With: fooyre` est ajouté par httpClient.
 * - Le frontend ne décide jamais du rôle ni des permissions : il recharge `/me` après connexion.
 */
import { useMutation, useQueryClient, type QueryClient } from '@tanstack/react-query';

import { ApiError } from '@/shared/api/apiError';
import { httpClient } from '@/shared/api/httpClient';
import { clearAccessToken, setAccessToken } from '@/shared/auth/accessTokenStore';
import { suspendSessionRefresh } from '@/shared/auth/sessionRefresh';

/** Réponse de `/auth/login` et `/auth/refresh` (même forme que `/auth/switch-business`). */
export interface TokenResponse {
  readonly accessToken: string;
  /** Durée de validité du jeton d'accès, en secondes. */
  readonly expiresIn: number;
}

export interface LoginCredentials {
  readonly email: string;
  readonly password: string;
}

function readAccessToken(data: unknown, status: number, route: string): string {
  const token = (data as Partial<TokenResponse> | null)?.accessToken;
  if (typeof token !== 'string' || token === '') {
    throw new ApiError({
      status,
      code: 'UNKNOWN_ERROR',
      kind: 'http',
      message: `Réponse de ${route} sans jeton d'accès.`,
    });
  }
  return token;
}

/**
 * Connexion. En cas de succès : jeton en mémoire, cache entièrement vidé (aucune donnée d'une
 * session précédente ne subsiste). En cas d'échec, rien n'est modifié.
 */
export async function login(queryClient: QueryClient, credentials: LoginCredentials): Promise<void> {
  const response = await httpClient.post<TokenResponse>('/auth/login', credentials);
  const token = readAccessToken(response.data, response.status, '/auth/login');
  queryClient.clear();
  setAccessToken(token);
}

export function useLogin() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (credentials: LoginCredentials) => login(queryClient, credentials),
  });
}

/** Nom du verrou Web Locks partagé par les onglets de l'application (même origine). */
export const SESSION_REFRESH_LOCK = 'fooyre-session-refresh';

async function requestRefresh(): Promise<string | null> {
  try {
    const response = await httpClient.post<TokenResponse>('/auth/refresh');
    return readAccessToken(response.data, response.status, '/auth/refresh');
  } catch {
    return null;
  }
}

/** API Web Locks si le navigateur la fournit (Chrome/Edge ≥ 69, Firefox ≥ 96, Safari ≥ 15.4). */
function webLocks(): LockManager | null {
  if (typeof navigator === 'undefined' || !('locks' in navigator)) {
    return null;
  }
  const locks = navigator.locks as LockManager | undefined;
  return locks && typeof locks.request === 'function' ? locks : null;
}

/**
 * Renouvellement de session par le cookie HttpOnly (gestionnaire branché dans
 * `configureSessionRefresh`). Renvoie le nouveau jeton, ou `null` si la session n'a pas pu être
 * rétablie (cookie absent, expiré ou révoqué).
 *
 * Coordination entre onglets (amélioration progressive) : quand l'API Web Locks existe, les
 * renouvellements de TOUS les onglets passent par un verrou exclusif. Un onglet n'envoie donc sa
 * demande qu'après la réponse de l'autre : le navigateur présente alors le cookie DÉJÀ renouvelé
 * (cookie partagé entre onglets) au lieu de l'ancien, qui serait refusé par la rotation. Sans
 * Web Locks (ex. Safari 14), comportement inchangé : un onglet peut, rarement, être renvoyé à la
 * connexion. Aucun jeton n'est partagé ni stocké : chaque onglet garde son jeton d'accès en mémoire.
 */
export async function refreshSession(): Promise<string | null> {
  const locks = webLocks();
  if (!locks) {
    return requestRefresh();
  }
  try {
    return await locks.request(SESSION_REFRESH_LOCK, { mode: 'exclusive' }, () => requestRefresh());
  } catch {
    // Verrou indisponible (contexte restreint) : renouvellement sans coordination.
    return requestRefresh();
  }
}

/** Résultat d'une déconnexion. */
export interface LogoutResult {
  /**
   * `true` uniquement si le serveur a CONFIRMÉ la révocation de la session (204). Sinon la
   * session est fermée sur cet appareil seulement : le cookie HttpOnly, illisible par JavaScript,
   * peut rester valide côté serveur jusqu'à son expiration.
   */
  readonly serverConfirmed: boolean;
}

/**
 * Déconnexion. La session LOCALE est toujours fermée immédiatement, même si le serveur est
 * injoignable : renouvellement suspendu (aucune réouverture par le cookie pendant l'opération),
 * jeton d'accès effacé, cache vidé ; puis demande de révocation au serveur.
 */
export async function logout(queryClient: QueryClient): Promise<LogoutResult> {
  const resume = suspendSessionRefresh();
  try {
    clearAccessToken();
    queryClient.clear();
    try {
      const response = await httpClient.post('/auth/logout');
      return { serverConfirmed: response.status === 204 };
    } catch {
      return { serverConfirmed: false };
    }
  } finally {
    resume();
  }
}

export function useLogout() {
  const queryClient = useQueryClient();
  return useMutation({ mutationFn: () => logout(queryClient) });
}
