/**
 * Handlers MSW — DONNÉES FICTIVES. Utilisés par le serveur de développement (si
 * VITE_ENABLE_MOCKS=true) et par les tests. Jamais inclus dans un build de production
 * (contrôlé par scripts/verify-build.mjs).
 *
 * Authentification simulée (aucune vérification réelle) : la connexion réussit toujours avec un
 * jeton fictif, la déconnexion aussi ; aucun cookie n'existe, donc le renouvellement de session
 * répond 401 (comme un navigateur sans session).
 */
import { http, HttpResponse } from 'msw';

import { env } from '@/shared/config/env';

import { FIXTURE_ACCESS_TOKEN, meFixture } from './fixtures/me';

/** Handlers fictifs pour une URL de base d'API donnée (relative ou absolue). */
export function createHandlers(apiBaseUrl: string) {
  return [
    http.get(`${apiBaseUrl}/me`, () => HttpResponse.json(meFixture)),
    http.post(`${apiBaseUrl}/auth/login`, () =>
      HttpResponse.json({ accessToken: FIXTURE_ACCESS_TOKEN, expiresIn: 900 }),
    ),
    http.post(`${apiBaseUrl}/auth/refresh`, () =>
      HttpResponse.json({ status: 401, code: 'SESSION_REVOKED' }, { status: 401 }),
    ),
    http.post(`${apiBaseUrl}/auth/logout`, () => new HttpResponse(null, { status: 204 })),
  ];
}

export const handlers = createHandlers(env.apiBaseUrl);
