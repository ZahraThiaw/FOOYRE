import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { meFixture } from '@/mocks/fixtures/me';
import { ApiError } from '@/shared/api/apiError';
import { createQueryClient } from '@/shared/api/queryClient';
import { getAccessToken, setAccessToken } from '@/shared/auth/accessTokenStore';
import { server } from '@/test/server';

import { deriveSessionState, hasPermission, sessionQueryKeys, switchBusiness } from './api';
import type { MeResponse } from './types';

const me: MeResponse = meFixture;
const noBusiness: MeResponse = { ...me, activeBusiness: null, activeMembership: null };
const httpError = (status: number) =>
  new ApiError({ status, code: 'X', kind: 'http', message: 'x' });

describe('deriveSessionState (R8)', () => {
  it('loading', () => {
    expect(deriveSessionState({ status: 'pending', data: undefined, error: null })).toEqual({
      status: 'loading',
    });
  });

  it('ready : entreprise et appartenance actives', () => {
    expect(deriveSessionState({ status: 'success', data: me, error: null })).toEqual({
      status: 'ready',
      me,
    });
  });

  it('no-business : aucune entreprise active', () => {
    expect(deriveSessionState({ status: 'success', data: noBusiness, error: null })).toEqual({
      status: 'no-business',
      me: noBusiness,
    });
  });

  it.each([
    [401, 'expired'],
    [403, 'forbidden'],
    [404, 'error'],
    [500, 'error'],
  ])('%i → %s', (status, expected) => {
    const state = deriveSessionState({
      status: 'error',
      data: undefined,
      error: httpError(status),
    });
    expect(state.status).toBe(expected);
  });

  it('coupure réseau → error', () => {
    const error = new ApiError({
      status: null,
      code: 'NETWORK_ERROR',
      kind: 'network',
      message: 'x',
    });
    expect(deriveSessionState({ status: 'error', data: undefined, error }).status).toBe('error');
  });

  it('contrat incohérent (une seule des deux valeurs nulle) → error, jamais ready', () => {
    for (const data of [
      { ...me, activeMembership: null },
      { ...me, activeBusiness: null },
    ]) {
      expect(deriveSessionState({ status: 'success', data, error: null }).status).toBe('error');
    }
  });
});

describe('hasPermission (affichage uniquement)', () => {
  it('vrai seulement si la permission figure dans /me', () => {
    expect(hasPermission(me, 'BUSINESS_READ')).toBe(true);
    expect(hasPermission(me, 'MEMBER_MANAGE')).toBe(false);
    expect(hasPermission(undefined, 'BUSINESS_READ')).toBe(false);
  });
});

describe('switchBusiness (R8, ADR‑04)', () => {
  it('envoie la demande, enregistre le jeton, vide le cache et recharge /me', async () => {
    const queryClient = createQueryClient();
    queryClient.setQueryData(['ventes', 'ancienne-entreprise'], ['donnée A']);
    queryClient.setQueryData(sessionQueryKeys.me, me);
    setAccessToken('ancien-jeton');

    const switched: MeResponse = {
      ...me,
      activeBusiness: { id: 'b-2', name: 'Boutique B (fictive)', currency: 'XOF' },
      activeMembership: { id: 'm-2', role: 'EMPLOYEE', employeeMaxDiscountRate: '10.00' },
    };
    let body: unknown;
    let requestedWith: string | null = null;
    let meAuthorization: string | null = null;
    server.use(
      http.post('/api/v1/auth/switch-business', async ({ request }) => {
        body = await request.json();
        requestedWith = request.headers.get('X-Requested-With');
        return HttpResponse.json({ accessToken: 'nouveau-jeton', expiresIn: 900 });
      }),
      http.get('/api/v1/me', ({ request }) => {
        meAuthorization = request.headers.get('Authorization');
        return HttpResponse.json(switched);
      }),
    );

    const result = await switchBusiness(queryClient, 'b-2');

    expect(body).toEqual({ businessId: 'b-2' });
    expect(requestedWith).toBe('fooyre');
    expect(getAccessToken()).toBe('nouveau-jeton');
    expect(meAuthorization).toBe('Bearer nouveau-jeton');
    expect(queryClient.getQueryData(['ventes', 'ancienne-entreprise'])).toBeUndefined();
    expect(result).toEqual(switched);
    expect(queryClient.getQueryData(sessionQueryKeys.me)).toEqual(switched);
  });

  it('refus (404, appartenance inexistante) : jeton et cache inchangés', async () => {
    const queryClient = createQueryClient();
    queryClient.setQueryData(['ventes'], ['donnée A']);
    setAccessToken('ancien-jeton');
    server.use(
      http.post('/api/v1/auth/switch-business', () =>
        HttpResponse.json({ status: 404, code: 'NOT_FOUND' }, { status: 404 }),
      ),
    );

    await expect(switchBusiness(queryClient, 'inconnue')).rejects.toMatchObject({
      status: 404,
      code: 'NOT_FOUND',
    });
    expect(getAccessToken()).toBe('ancien-jeton');
    expect(queryClient.getQueryData(['ventes'])).toEqual(['donnée A']);
  });

  it('réponse sans jeton : erreur, jeton et cache inchangés', async () => {
    const queryClient = createQueryClient();
    queryClient.setQueryData(['ventes'], ['donnée A']);
    setAccessToken('ancien-jeton');
    server.use(
      http.post('/api/v1/auth/switch-business', () => HttpResponse.json({ expiresIn: 900 })),
    );

    await expect(switchBusiness(queryClient, 'b-2')).rejects.toBeInstanceOf(ApiError);
    expect(getAccessToken()).toBe('ancien-jeton');
    expect(queryClient.getQueryData(['ventes'])).toEqual(['donnée A']);
  });
});
