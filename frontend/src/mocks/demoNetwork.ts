/**
 * Configuration réseau du MODE DÉMONSTRATION (serveur de développement avec
 * VITE_ENABLE_MOCKS=true) — empêche toute requête destinée à l'API FOOYRE d'atteindre un
 * backend réel.
 *
 * Principe : après les handlers fictifs, un handler final répond `501 MOCK_NOT_IMPLEMENTED`
 * à toute requête dont le CHEMIN est celui de l'API (`/api/v1` ou `/api/v1/…`), quelle que
 * soit son ORIGINE (même domaine, `http://localhost:8080`, autre hôte…) et quelle que soit
 * la méthode HTTP. Le test porte sur le chemin normalisé : `/api/v10`, `/api/v1-old` ou
 * `/docs/api/v1/…` ne sont pas concernés.
 *
 * Effet de bord assumé : en mode démonstration, un service externe dont le chemin commencerait
 * par `/api/v1` serait lui aussi bloqué (aucun n'est utilisé aujourd'hui). Les autres requêtes
 * externes ne sont pas bloquées.
 *
 * On ne s'appuie pas sur `onUnhandledFrame` pour bloquer l'API : dans MSW 3.0.7, un rappel
 * personnalisé appelant `defaults.error()` affiche un message puis LAISSE PASSER la requête.
 * `onUnhandledFrame: 'bypass'` ne concerne donc que les requêtes hors API (modules Vite,
 * images, polices, services externes), qui doivent continuer à fonctionner normalement.
 */
import { http, HttpResponse } from 'msw';

import { env } from '@/shared/config/env';

import { createHandlers } from './handlers';

export const MOCK_NOT_IMPLEMENTED = 'MOCK_NOT_IMPLEMENTED';

/** Seules les requêtes HORS API peuvent être non gérées ; elles passent normalement. */
export const DEMO_ON_UNHANDLED_FRAME = 'bypass' as const;

/** Chemin de l'API sans barre finale (ex. `/api/v1`), quelle que soit la forme de apiBaseUrl. */
function apiBasePath(apiBaseUrl: string): string {
  return new URL(apiBaseUrl, 'http://origine-indifferente.invalid').pathname.replace(/\/+$/, '');
}

/** Vrai si le chemin normalisé de l'URL est le chemin de l'API ou se trouve sous lui. */
export function isApiPathOnAnyOrigin(url: string, apiBaseUrl: string): boolean {
  const basePath = apiBasePath(apiBaseUrl);
  const { pathname } = new URL(url);
  return pathname === basePath || pathname.startsWith(`${basePath}/`);
}

export function createApiFallbackHandlers(apiBaseUrl: string) {
  const blockUnmockedApiRequest = ({ request }: { request: Request }) => {
    const { origin, pathname } = new URL(request.url);
    console.warn(
      `[FOOYRE][démo] Requête API non simulée bloquée : ${request.method} ${origin}${pathname}`,
    );
    return HttpResponse.json(
      {
        type: 'about:blank',
        title: 'Requête non simulée en mode démonstration',
        status: 501,
        code: MOCK_NOT_IMPLEMENTED,
        detail: `${request.method} ${pathname}`,
      },
      { status: 501, headers: { 'Content-Type': 'application/problem+json' } },
    );
  };
  return [
    http.all(
      ({ request }) => isApiPathOnAnyOrigin(request.url, apiBaseUrl),
      blockUnmockedApiRequest,
    ),
  ];
}

/** Handlers du mode démonstration : données fictives d'abord, blocage de l'API ensuite. */
export function createDemoHandlers(apiBaseUrl: string = env.apiBaseUrl) {
  return [...createHandlers(apiBaseUrl), ...createApiFallbackHandlers(apiBaseUrl)];
}
