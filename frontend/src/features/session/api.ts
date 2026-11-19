/**
 * Session de l'utilisateur connecté (R8) : profil `/me`, états de session, changement
 * d'entreprise active. Les permissions et rôles reçus servent uniquement à l'affichage ;
 * le backend contrôle chaque requête.
 */
import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';

import { ApiError, toApiError } from '@/shared/api/apiError';
import { httpClient } from '@/shared/api/httpClient';
import { setAccessToken } from '@/shared/auth/accessTokenStore';

import type { MeResponse, Permission, SwitchBusinessResponse } from './types';

export const sessionQueryKeys = {
  me: ['session', 'me'] as const,
};

export async function fetchMe(): Promise<MeResponse> {
  const response = await httpClient.get<MeResponse>('/me');
  return response.data;
}

export function useMe() {
  return useQuery({ queryKey: sessionQueryKeys.me, queryFn: fetchMe });
}

/** Affichage uniquement : n'accorde aucun droit (le backend reste seul juge). */
export function hasPermission(me: MeResponse | undefined, permission: Permission): boolean {
  return me?.permissions.includes(permission) ?? false;
}

// ─── États de session ────────────────────────────────────────────────────────────────────────

export type SessionState =
  | { readonly status: 'loading' }
  | { readonly status: 'ready'; readonly me: MeResponse }
  | { readonly status: 'no-business'; readonly me: MeResponse }
  /** 401 : session absente, expirée ou révoquée (renouvellement impossible). */
  | { readonly status: 'expired'; readonly error: ApiError }
  /** 403 : compte authentifié mais non autorisé. */
  | { readonly status: 'forbidden'; readonly error: ApiError }
  | { readonly status: 'error'; readonly error: ApiError };

/** Vrai si entreprise et appartenance actives sont toutes deux renseignées ou toutes deux nulles. */
export function hasCoherentActiveContext(me: MeResponse): boolean {
  return (me.activeBusiness === null) === (me.activeMembership === null);
}

export function deriveSessionState(query: {
  readonly status: 'pending' | 'error' | 'success';
  readonly data: MeResponse | undefined;
  readonly error: unknown;
}): SessionState {
  if (query.status === 'pending') {
    return { status: 'loading' };
  }
  if (query.status === 'error' || query.data === undefined) {
    const error = toApiError(query.error);
    if (error.status === 401) return { status: 'expired', error };
    if (error.status === 403) return { status: 'forbidden', error };
    return { status: 'error', error };
  }
  const me = query.data;
  if (!hasCoherentActiveContext(me)) {
    return {
      status: 'error',
      error: new ApiError({
        status: null,
        code: 'UNKNOWN_ERROR',
        kind: 'unknown',
        message: 'Contrat /me non respecté : activeBusiness et activeMembership incohérents.',
      }),
    };
  }
  return me.activeBusiness === null ? { status: 'no-business', me } : { status: 'ready', me };
}

export function useSession(): SessionState {
  const query = useMe();
  return deriveSessionState(query);
}

// ─── Changement d'entreprise active ─────────────────────────────────────────────────────────

/**
 * POST /auth/switch-business (ADR‑04, ADR‑05). L'identifiant n'est qu'une DEMANDE : le backend
 * vérifie l'appartenance active et recalcule les permissions. En cas de succès :
 * nouveau jeton en mémoire → cache TanStack Query entièrement vidé → `/me` rechargé.
 * En cas d'échec, ni le jeton ni le cache ne sont modifiés.
 */
export async function switchBusiness(
  queryClient: QueryClient,
  businessId: string,
): Promise<MeResponse> {
  const response = await httpClient.post<SwitchBusinessResponse>('/auth/switch-business', {
    businessId,
  });
  const { accessToken } = response.data;
  if (typeof accessToken !== 'string' || accessToken === '') {
    throw new ApiError({
      status: response.status,
      code: 'UNKNOWN_ERROR',
      kind: 'http',
      message: 'Réponse de /auth/switch-business sans jeton d’accès.',
    });
  }

  setAccessToken(accessToken);
  // Aucune donnée de l'entreprise précédente ne doit subsister.
  queryClient.removeQueries({ type: 'inactive' });
  await queryClient.resetQueries();
  return queryClient.fetchQuery({ queryKey: sessionQueryKeys.me, queryFn: fetchMe });
}

export function useSwitchBusiness() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (businessId: string) => switchBusiness(queryClient, businessId),
  });
}
