/**
 * Client HTTP unique de l'application (Axios). Les modules l'utilisent via leurs
 * fichiers `features/<module>/api.ts`.
 *
 * - Requêtes destinées à l'API FOOYRE (même origine et chemin sous `apiBaseUrl`, voir
 *   apiUrl.ts) : `Authorization: Bearer <jeton en mémoire>` (ADR‑04) ; sans jeton courant,
 *   tout `Authorization` présent est retiré (jamais de jeton périmé).
 * - Toute AUTRE destination (autre domaine, ou même origine hors du chemin de l'API) :
 *   l'en‑tête `Authorization` est SUPPRIMÉ, quelle que soit sa provenance (options de la
 *   requête, en‑têtes par défaut de l'instance, configuration héritée, rejeu), et l'option
 *   `auth` d'Axios est retirée (sinon l'adaptateur ajouterait un `Authorization: Basic`
 *   APRÈS les intercepteurs). Cet intercepteur, enregistré en premier, est exécuté en
 *   DERNIER par Axios parmi les intercepteurs de requête : il a le dernier mot.
 *   Limite : des identifiants placés dans l'URL elle‑même (`https://user:pass@…`) ne sont
 *   pas retirés ; ces URL ne sont jamais considérées comme appartenant à l'API.
 * - `X-Requested-With: fooyre` sur les routes `/auth/*` de l'API : complément aux contrôles
 *   backend (SameSite=Strict, Path, Origin), PAS une protection CSRF suffisante à elle seule.
 * - Sur 401 d'une requête de l'API (hors `/auth/*`), en comparant le jeton utilisé à l'envoi
 *   au jeton courant :
 *   - jeton déjà renouvelé entre‑temps → rejeu avec le jeton courant, SANS nouveau
 *     renouvellement (évite une rotation superflue du refresh token, ADR‑04) ;
 *   - jeton inchangé → renouvellement partagé (une seule requête pour tous les appels
 *     simultanés), puis rejeu ;
 *   - session effacée entre‑temps → échec, sans renouvellement.
 *   Une requête n'est rejouée qu'UNE fois. Un jeton n'est effacé que s'il est encore celui
 *   utilisé par la requête en échec : un jeton plus récent n'est jamais effacé.
 * - Les jetons ne sont jamais journalisés. Toute erreur est normalisée en `ApiError`.
 */
import axios, { type AxiosError, type InternalAxiosRequestConfig } from 'axios';

import { clearAccessToken, getAccessToken, setAccessToken } from '@/shared/auth/accessTokenStore';
import { refreshAccessToken } from '@/shared/auth/sessionRefresh';
import { env } from '@/shared/config/env';

import { toApiError } from './apiError';
import { apiRelativePath } from './apiUrl';

const AUTH_ROUTE_PREFIX = 'auth/';

/** Métadonnées internes attachées à chaque requête par l'intercepteur. */
interface FooyreRequestMeta {
  /** La requête vise l'API FOOYRE. */
  isApi: boolean;
  /** La requête vise une route `/auth/*` de l'API. */
  isAuthRoute: boolean;
  /** Jeton effectivement envoyé (null si aucun). */
  sentToken: string | null;
  /** La requête a déjà été rejouée une fois. */
  retried: boolean;
}

type FooyreRequestConfig = InternalAxiosRequestConfig & { fooyre?: FooyreRequestMeta };

export const httpClient = axios.create({
  baseURL: env.apiBaseUrl,
  timeout: 15_000,
  headers: { Accept: 'application/json' },
});

httpClient.interceptors.request.use((config: FooyreRequestConfig) => {
  const relativePath = apiRelativePath(httpClient.getUri(config), env.apiBaseUrl);
  const isApi = relativePath !== null;
  const isAuthRoute = isApi && relativePath.startsWith(AUTH_ROUTE_PREFIX);
  const token = isApi ? getAccessToken() : null;

  if (!isApi) {
    // Destination non autorisée : aucune autorisation ne doit partir, quelle qu'en soit la source.
    config.headers.delete('Authorization');
    delete config.auth;
  } else if (token) {
    config.headers.set('Authorization', `Bearer ${token}`);
  } else {
    // Requête API sans jeton courant (ex. rejeu après effacement) : jamais de jeton périmé.
    config.headers.delete('Authorization');
  }
  if (isAuthRoute) {
    config.headers.set('X-Requested-With', 'fooyre');
  }

  config.fooyre = {
    isApi,
    isAuthRoute,
    sentToken: token,
    retried: config.fooyre?.retried ?? false,
  };
  return config;
});

httpClient.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const config = error.config as FooyreRequestConfig | undefined;
    const meta = config?.fooyre;

    if (error.response?.status !== 401 || !config || !meta?.isApi || meta.isAuthRoute) {
      throw toApiError(error);
    }

    const { sentToken } = meta;
    const currentToken = getAccessToken();

    // Second 401 après rejeu : échec définitif, sans nouvelle tentative.
    if (meta.retried) {
      if (sentToken !== null && currentToken === sentToken) {
        clearAccessToken();
      }
      throw toApiError(error);
    }
    meta.retried = true;

    // Le jeton a changé depuis l'envoi de la requête.
    if (currentToken !== sentToken) {
      if (currentToken === null) {
        throw toApiError(error); // session effacée entre‑temps
      }
      return httpClient.request(config); // déjà renouvelé : rejeu sans renouvellement
    }

    // Jeton inchangé : renouvellement partagé entre toutes les requêtes concernées.
    const newToken = await refreshAccessToken();
    if (newToken) {
      if (getAccessToken() === sentToken) {
        setAccessToken(newToken); // ne jamais écraser un jeton plus récent
      }
      return httpClient.request(config);
    }

    if (sentToken !== null && getAccessToken() === sentToken) {
      clearAccessToken();
    }
    throw toApiError(error);
  },
);
