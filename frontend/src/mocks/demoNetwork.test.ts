// @vitest-environment node
/// <reference types="node" />
/**
 * R4 / F3 — Isolation du mode démonstration.
 * Deux VRAIS serveurs HTTP locaux (éphémères, propres au test) comptent chaque requête reçue :
 * - A : origine configurée de l'API FOOYRE ;
 * - B : une AUTRE origine (ex. backend joint directement sur http://localhost:8080, ou
 *   service externe).
 * Avec la configuration exacte du mode démonstration (mêmes handlers et même stratégie que
 * entry.enabled.ts), aucune requête vers le chemin de l'API ne doit atteindre A ni B, quelle
 * que soit l'origine, alors que les autres requêtes leur parviennent normalement.
 * Le cas d'une URL RELATIVE (résolue par le navigateur) est couvert par
 * demoNetwork.relative.test.ts.
 */
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';

import { setupServer } from 'msw/node';
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import { server as testServer } from '@/test/server';

import {
  createDemoHandlers,
  DEMO_ON_UNHANDLED_FRAME,
  isApiPathOnAnyOrigin,
  MOCK_NOT_IMPLEMENTED,
} from './demoNetwork';
import { meFixture } from './fixtures/me';

interface RealBackend {
  readonly server: Server;
  readonly origin: string;
  readonly received: string[];
}

async function startRealBackend(): Promise<RealBackend> {
  const received: string[] = [];
  const server = createServer((request, response) => {
    received.push(`${request.method ?? '?'} ${request.url ?? '?'}`);
    response.writeHead(200, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify({ source: 'backend-reel' }));
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const origin = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  return { server, origin, received };
}

let backendA: RealBackend;
let backendB: RealBackend;
let apiBaseUrl = '';
let demoServer: ReturnType<typeof setupServer>;

const ALL_METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'];

function apiRequestsReceived(): string[] {
  return [...backendA.received, ...backendB.received].filter((entry) =>
    /^\S+ \/api\/v1(\/|\?|$)/.test(entry),
  );
}

beforeAll(async () => {
  backendA = await startRealBackend();
  backendB = await startRealBackend();
  apiBaseUrl = `${backendA.origin}/api/v1`;

  // Remplace le serveur MSW des tests par la configuration exacte du mode démonstration.
  testServer.close();
  demoServer = setupServer(...createDemoHandlers(apiBaseUrl));
  demoServer.listen({ onUnhandledFrame: DEMO_ON_UNHANDLED_FRAME });
});

afterAll(async () => {
  demoServer.close();
  await Promise.all(
    [backendA, backendB].map(
      (backend) => new Promise<void>((resolve) => backend.server.close(() => resolve())),
    ),
  );
  testServer.listen({ onUnhandledFrame: 'error' });
});

beforeEach(() => {
  backendA.received.length = 0;
  backendB.received.length = 0;
  vi.spyOn(console, 'warn').mockImplementation(() => undefined);
});

async function expectBlocked(url: string, method = 'GET') {
  const response = await fetch(url, { method });
  expect(response.status, `${method} ${url}`).toBe(501);
  if (method !== 'HEAD') {
    const body = (await response.json()) as { code: string };
    expect(body.code).toBe(MOCK_NOT_IMPLEMENTED);
  }
}

describe('mode démonstration — données fictives', () => {
  it('une requête API simulée renvoie la donnée fictive', async () => {
    const response = await fetch(`${apiBaseUrl}/me`);
    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toEqual(meFixture);
    expect(apiRequestsReceived()).toEqual([]);
  });
});

describe('mode démonstration — blocage de l’API sur toute origine', () => {
  it('requête absolue vers l’API, même origine, non simulée : bloquée', async () => {
    await expectBlocked(`${backendA.origin}/api/v1/sales`);
    await expectBlocked(`${backendA.origin}/api/v1`);
    await expectBlocked(`${backendA.origin}/api/v1/`);
    expect(backendA.received).toEqual([]);
  });

  it('requête absolue vers /api/v1 sur une AUTRE origine : bloquée', async () => {
    await expectBlocked(`${backendB.origin}/api/v1`);
    await expectBlocked(`${backendB.origin}/api/v1?x=1`);
    expect(backendB.received).toEqual([]);
  });

  it('requête vers /api/v1/sales sur une AUTRE origine : bloquée', async () => {
    await expectBlocked(`${backendB.origin}/api/v1/sales`);
    await expectBlocked(`${backendB.origin}/api/v1/me`); // donnée fictive non servie hors origine
    expect(backendB.received).toEqual([]);
  });

  it.each(ALL_METHODS)('méthode %s vers l’API (deux origines) : bloquée', async (method) => {
    await expectBlocked(`${backendA.origin}/api/v1/products/123`, method);
    await expectBlocked(`${backendB.origin}/api/v1/products/123`, method);
    expect(apiRequestsReceived()).toEqual([]);
  });

  it('/auth/refresh non simulé (POST) : bloqué', async () => {
    await expectBlocked(`${backendB.origin}/api/v1/auth/refresh`, 'POST');
    expect(apiRequestsReceived()).toEqual([]);
  });
});

describe('mode démonstration — ce qui n’est pas l’API passe normalement', () => {
  it.each([
    ['/api/v10/sales', 'préfixe trompeur'],
    ['/api/v1-old/sales', 'préfixe trompeur'],
    ['/docs/api/v1/sales', '/api/v1 non situé en tête du chemin'],
  ])('%s (%s) : non considéré comme l’API', async (path) => {
    const response = await fetch(`${backendB.origin}${path}`);
    expect(response.status).toBe(200);
    expect(backendB.received).toEqual([`GET ${path}`]);
  });

  it('ressource externe sans rapport avec l’API : autorisée', async () => {
    const response = await fetch(`${backendB.origin}/images/logo.png`);
    expect(response.status).toBe(200);
    expect(backendB.received).toEqual(['GET /images/logo.png']);
  });

  it('ressource de l’application (même origine que l’API) : autorisée', async () => {
    const response = await fetch(`${backendA.origin}/assets/app.js`);
    expect(response.status).toBe(200);
    expect(backendA.received).toEqual(['GET /assets/app.js']);
  });
});

describe('mode démonstration — bilan', () => {
  it('aucune requête API non simulée n’atteint un backend réel, quelle que soit l’origine', async () => {
    const urls = [
      `${backendA.origin}/api/v1/sales`,
      `${backendA.origin}/api/v1/customers`,
      `${backendB.origin}/api/v1/finance/summary`,
      `${backendB.origin}/api/v1/auth/login`,
      `${backendB.origin}/api/v1`,
    ];
    await Promise.all(urls.map((url) => fetch(url, { method: 'POST', body: '{}' })));
    expect(apiRequestsReceived()).toEqual([]);
  });
});

describe('isApiPathOnAnyOrigin', () => {
  it.each([
    ['http://autre.example/api/v1', true],
    ['http://autre.example/api/v1/', true],
    ['http://autre.example/api/v1/a/b?c=1', true],
    ['http://autre.example/api/v1/../v1/sales', true],
    ['http://autre.example/api/v1/%2e%2e/admin', false],
    ['http://autre.example/api/v10/sales', false],
    ['http://autre.example/api/v1-old/sales', false],
    ['http://autre.example/docs/api/v1/sales', false],
    ['http://autre.example/API/v1/sales', false],
  ])('%s → %s', (url, expected) => {
    expect(isApiPathOnAnyOrigin(url, '/api/v1')).toBe(expected);
    expect(isApiPathOnAnyOrigin(url, 'https://app.fooyre.example/api/v1/')).toBe(expected);
  });
});
