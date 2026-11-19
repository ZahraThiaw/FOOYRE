/**
 * Handlers MSW — DONNÉES FICTIVES. Utilisés par le serveur de développement (si
 * VITE_ENABLE_MOCKS=true) et par les tests. Jamais inclus dans un build de production
 * (contrôlé par scripts/verify-build.mjs).
 */
import { http, HttpResponse } from 'msw';

import { env } from '@/shared/config/env';

import { meFixture } from './fixtures/me';

/** Handlers fictifs pour une URL de base d'API donnée (relative ou absolue). */
export function createHandlers(apiBaseUrl: string) {
  return [http.get(`${apiBaseUrl}/me`, () => HttpResponse.json(meFixture))];
}

export const handlers = createHandlers(env.apiBaseUrl);
