/**
 * F3 — Requête RELATIVE vers l'API en mode démonstration (environnement navigateur jsdom).
 * Utilise la configuration réelle (apiBaseUrl relatif `/api/v1`, comme en développement) et
 * le vrai client HTTP de l'application. Une requête relative non simulée doit recevoir la
 * réponse fictive 501 : si elle était transmise au réseau, elle échouerait (aucun serveur
 * n'écoute sur l'origine jsdom) au lieu de renvoyer MOCK_NOT_IMPLEMENTED.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { meFixture } from '@/mocks/fixtures/me';
import { ApiError } from '@/shared/api/apiError';
import { httpClient } from '@/shared/api/httpClient';
import { env } from '@/shared/config/env';
import { server } from '@/test/server';

import { createDemoHandlers, MOCK_NOT_IMPLEMENTED } from './demoNetwork';

beforeEach(() => {
  server.use(...createDemoHandlers(env.apiBaseUrl));
  vi.spyOn(console, 'warn').mockImplementation(() => undefined);
});

describe('mode démonstration — URL relative', () => {
  it('l’URL de base est bien relative (configuration de développement)', () => {
    expect(env.apiBaseUrl).toBe('/api/v1');
  });

  it('requête relative simulée : donnée fictive', async () => {
    const response = await httpClient.get('/me');
    expect(response.data).toEqual(meFixture);
  });

  it('requête relative non simulée : bloquée par la réponse fictive 501', async () => {
    const error: unknown = await httpClient.post('/sales', {}).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 501, code: MOCK_NOT_IMPLEMENTED });
  });
});
