import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { fetchMe } from '@/features/session/api';
import { meFixture } from '@/mocks/fixtures/me';
import { clearAccessToken, getAccessToken, setAccessToken } from '@/shared/auth/accessTokenStore';
import { configureSessionRefresh } from '@/shared/auth/sessionRefresh';
import { server } from '@/test/server';

import { ApiError } from './apiError';
import { httpClient } from './httpClient';

const ORIGIN = globalThis.location.origin;
const TOKEN = 'jeton-de-test';
const EXTERNAL = 'https://autre-domaine.example';

/**
 * jsdom applique la politique CORS : un domaine externe simulé doit l'autoriser
 * explicitement. La pré‑requête (OPTIONS) est acceptée afin qu'un jeton envoyé par erreur
 * atteigne bien le handler et soit détecté par les assertions (et non masqué par une
 * erreur réseau).
 */
const CORS_HEADERS = {
  'Access-Control-Allow-Origin': ORIGIN,
  'Access-Control-Allow-Methods': 'GET, POST',
  'Access-Control-Allow-Headers': 'Authorization, X-Requested-With',
};
const externalPreflight = http.options(
  `${EXTERNAL}/*`,
  () => new HttpResponse(null, { status: 204, headers: CORS_HEADERS }),
);

/** Promesse contrôlable depuis le test. */
function deferred<T = void>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

/** Enregistre toutes les valeurs d'en‑têtes d'une requête interceptée. */
function headerValues(request: Request): string[] {
  const values: string[] = [];
  request.headers.forEach((value) => values.push(value));
  return values;
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe('httpClient — fonctionnement de base', () => {
  it('récupère /me depuis les données fictives (tests uniquement)', async () => {
    await expect(fetchMe()).resolves.toEqual(meFixture);
  });

  it('n’ajoute X-Requested-With que sur les routes /auth/* de l’API', async () => {
    const seen: Record<string, string | null> = {};
    server.use(
      http.post('/api/v1/auth/logout', ({ request }) => {
        seen.auth = request.headers.get('X-Requested-With');
        return new HttpResponse(null, { status: 204 });
      }),
      http.get('/api/v1/echo', ({ request }) => {
        seen.business = request.headers.get('X-Requested-With');
        return HttpResponse.json({});
      }),
      externalPreflight,
      http.post(`${EXTERNAL}/auth/logout`, ({ request }) => {
        seen.external = request.headers.get('X-Requested-With');
        return new HttpResponse(null, { status: 204, headers: CORS_HEADERS });
      }),
    );
    await httpClient.post('/auth/logout');
    await httpClient.get('/echo');
    await httpClient.post(`${EXTERNAL}/auth/logout`);
    expect(seen).toEqual({ auth: 'fooyre', business: null, external: null });
  });

  it('normalise une réponse Problem Details en ApiError', async () => {
    server.use(
      http.get('/api/v1/echo', () =>
        HttpResponse.json(
          { title: 'Stock insuffisant', status: 409, code: 'INSUFFICIENT_STOCK', traceId: 't-1' },
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );
    const error: unknown = await httpClient.get('/echo').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 409, code: 'INSUFFICIENT_STOCK', traceId: 't-1' });
  });
});

describe('httpClient — R1 : le jeton n’est transmis qu’à l’API FOOYRE', () => {
  it('URL relative de l’API : jeton transmis', async () => {
    let authorization: string | null = null;
    server.use(
      http.get('/api/v1/echo', ({ request }) => {
        authorization = request.headers.get('Authorization');
        return HttpResponse.json({});
      }),
    );
    setAccessToken(TOKEN);
    await httpClient.get('/echo');
    expect(authorization).toBe(`Bearer ${TOKEN}`);
  });

  it('URL absolue correspondant à l’API : jeton transmis', async () => {
    let authorization: string | null = null;
    server.use(
      http.get(`${ORIGIN}/api/v1/echo`, ({ request }) => {
        authorization = request.headers.get('Authorization');
        return HttpResponse.json({});
      }),
    );
    setAccessToken(TOKEN);
    await httpClient.get(`${ORIGIN}/api/v1/echo`);
    expect(authorization).toBe(`Bearer ${TOKEN}`);
  });

  it.each([
    ['un autre domaine', 'https://autre-domaine.example/api/v1/echo'],
    ['un autre domaine (URL sans schéma)', '//autre-domaine.example/api/v1/echo'],
    ['la même origine hors du chemin de l’API', `${ORIGIN}/autre/echo`],
    ['un chemin qui ressort de l’API par « .. »', `${ORIGIN}/api/v1/%2e%2e/autre/echo`],
  ])('requête vers %s : aucun jeton dans les en‑têtes', async (_label, url) => {
    let values: string[] = [];
    let authorization: string | null = 'non intercepté';
    server.use(
      externalPreflight,
      http.get(new URL(url, ORIGIN).href, ({ request }) => {
        authorization = request.headers.get('Authorization');
        values = headerValues(request);
        return HttpResponse.json({}, { headers: CORS_HEADERS });
      }),
    );
    setAccessToken(TOKEN);
    await httpClient.get(url);
    expect(authorization).toBeNull();
    expect(values.some((value) => value.includes(TOKEN))).toBe(false);
  });

  it('un 401 d’une destination externe ne déclenche ni renouvellement ni effacement', async () => {
    server.use(
      externalPreflight,
      http.get(
        `${EXTERNAL}/ressource`,
        () => new HttpResponse(null, { status: 401, headers: CORS_HEADERS }),
      ),
    );
    const refresh = vi.fn(() => Promise.resolve('nouveau'));
    configureSessionRefresh(refresh);
    setAccessToken(TOKEN);
    await expect(httpClient.get(`${EXTERNAL}/ressource`)).rejects.toMatchObject({
      status: 401,
    });
    expect(refresh).not.toHaveBeenCalled();
    expect(getAccessToken()).toBe(TOKEN);
  });
});

describe('httpClient — F1 : aucun Authorization vers une destination non autorisée', () => {
  /** Capture les en‑têtes réellement émis vers une URL (GET), interceptés au niveau réseau. */
  function captureHeaders(url: string) {
    const captured: { authorization: string | null; values: string[]; seen: boolean } = {
      authorization: 'non intercepté',
      values: [],
      seen: false,
    };
    server.use(
      externalPreflight,
      http.get(url, ({ request }) => {
        captured.seen = true;
        captured.authorization = request.headers.get('Authorization');
        captured.values = headerValues(request);
        return HttpResponse.json({}, { headers: CORS_HEADERS });
      }),
    );
    return captured;
  }

  afterEach(() => {
    delete httpClient.defaults.headers.common.Authorization;
  });

  it('requête externe avec un Authorization explicitement fourni : en‑tête supprimé', async () => {
    const captured = captureHeaders(`${EXTERNAL}/ressource`);
    setAccessToken(TOKEN);
    await httpClient.get(`${EXTERNAL}/ressource`, {
      headers: { Authorization: `Bearer ${TOKEN}` },
    });
    expect(captured.seen).toBe(true);
    expect(captured.authorization).toBeNull();
    expect(captured.values.some((value) => value.includes(TOKEN))).toBe(false);
  });

  it('requête externe avec un Authorization défini dans defaults.headers.common : supprimé', async () => {
    const captured = captureHeaders(`${EXTERNAL}/ressource`);
    httpClient.defaults.headers.common.Authorization = `Bearer ${TOKEN}`;
    await httpClient.get(`${EXTERNAL}/ressource`);
    expect(captured.seen).toBe(true);
    expect(captured.authorization).toBeNull();
  });

  it('requête externe avec l’option `auth` d’Axios : aucun Authorization Basic ajouté', async () => {
    const captured = captureHeaders(`${EXTERNAL}/ressource`);
    await httpClient.get(`${EXTERNAL}/ressource`, {
      auth: { username: 'utilisateur', password: 'secret' },
    });
    expect(captured.seen).toBe(true);
    expect(captured.authorization).toBeNull();
  });

  it('même origine hors de /api/v1 avec un Authorization explicite : en‑tête supprimé', async () => {
    const captured = captureHeaders(`${ORIGIN}/autre/ressource`);
    setAccessToken(TOKEN);
    await httpClient.get(`${ORIGIN}/autre/ressource`, {
      headers: { Authorization: `Bearer ${TOKEN}` },
    });
    expect(captured.seen).toBe(true);
    expect(captured.authorization).toBeNull();
  });

  it('requête vers l’API FOOYRE : le jeton courant est transmis normalement', async () => {
    const captured = captureHeaders(`${ORIGIN}/api/v1/ressource`);
    httpClient.defaults.headers.common.Authorization = 'Bearer valeur-par-defaut';
    setAccessToken(TOKEN);
    await httpClient.get('/ressource');
    expect(captured.authorization).toBe(`Bearer ${TOKEN}`);
  });

  it('requête externe : X-Requested-With n’est pas ajouté automatiquement', async () => {
    let requestedWith: string | null = 'non intercepté';
    server.use(
      externalPreflight,
      http.post(`${EXTERNAL}/auth/refresh`, ({ request }) => {
        requestedWith = request.headers.get('X-Requested-With');
        return new HttpResponse(null, { status: 204, headers: CORS_HEADERS });
      }),
    );
    await httpClient.post(`${EXTERNAL}/auth/refresh`);
    expect(requestedWith).toBeNull();
  });
});

describe('httpClient — R2 : échec après renouvellement', () => {
  it('second 401 après rejeu : jeton correspondant effacé, aucun rejeu supplémentaire', async () => {
    const seenTokens: (string | null)[] = [];
    server.use(
      http.get('/api/v1/echo', ({ request }) => {
        seenTokens.push(request.headers.get('Authorization'));
        return new HttpResponse(null, { status: 401 });
      }),
    );
    const refresh = vi.fn(() => Promise.resolve('nouveau'));
    configureSessionRefresh(refresh);
    setAccessToken('ancien');

    await expect(httpClient.get('/echo')).rejects.toMatchObject({ status: 401 });

    expect(refresh).toHaveBeenCalledTimes(1); // le premier 401 a déclenché le renouvellement
    expect(seenTokens).toEqual(['Bearer ancien', 'Bearer nouveau']); // un seul rejeu
    expect(getAccessToken()).toBeNull(); // le jeton « nouveau », rejeté, est effacé
  });

  it('un jeton plus récent n’est jamais effacé par l’échec d’une ancienne requête', async () => {
    let calls = 0;
    server.use(
      http.get('/api/v1/echo', () => {
        calls += 1;
        if (calls === 2) {
          // Pendant le rejeu, un jeton plus récent est obtenu ailleurs (ex. reconnexion).
          setAccessToken('plus-recent');
        }
        return new HttpResponse(null, { status: 401 });
      }),
    );
    configureSessionRefresh(() => Promise.resolve('nouveau'));
    setAccessToken('ancien');

    await expect(httpClient.get('/echo')).rejects.toMatchObject({ status: 401 });

    expect(calls).toBe(2);
    expect(getAccessToken()).toBe('plus-recent');
  });

  it('sur 401 sans renouvellement possible : efface le jeton et rejette', async () => {
    server.use(http.get('/api/v1/echo', () => new HttpResponse(null, { status: 401 })));
    setAccessToken('jeton-expire');
    await expect(httpClient.get('/echo')).rejects.toMatchObject({ status: 401 });
    expect(getAccessToken()).toBeNull();
  });
});

describe('httpClient — R3 : concurrence et 401 tardifs', () => {
  it('plusieurs requêtes simultanées en 401 : un seul renouvellement, rejeu avec le nouveau jeton', async () => {
    const replayTokens: (string | null)[] = [];
    server.use(
      http.get('/api/v1/echo', ({ request }) => {
        const authorization = request.headers.get('Authorization');
        if (authorization === 'Bearer nouveau') {
          replayTokens.push(authorization);
          return HttpResponse.json({ ok: true });
        }
        return new HttpResponse(null, { status: 401 });
      }),
    );
    const refresh = vi.fn(
      () => new Promise<string>((resolve) => setTimeout(() => resolve('nouveau'), 10)),
    );
    configureSessionRefresh(refresh);
    setAccessToken('ancien');

    const results = await Promise.all([
      httpClient.get<{ ok: boolean }>('/echo'),
      httpClient.get<{ ok: boolean }>('/echo'),
      httpClient.get<{ ok: boolean }>('/echo'),
    ]);

    expect(results.map((r) => r.data)).toEqual([{ ok: true }, { ok: true }, { ok: true }]);
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(replayTokens).toEqual(['Bearer nouveau', 'Bearer nouveau', 'Bearer nouveau']);
    expect(getAccessToken()).toBe('nouveau');
  });

  it('401 tardif reçu après un renouvellement réussi : aucun deuxième renouvellement', async () => {
    const releaseSlow = deferred();
    const slowTokens: (string | null)[] = [];
    server.use(
      // Requête lente : envoyée avec l'ancien jeton, son 401 arrive après le renouvellement.
      http.get('/api/v1/lente', async ({ request }) => {
        const authorization = request.headers.get('Authorization');
        slowTokens.push(authorization);
        if (authorization === 'Bearer nouveau') {
          return HttpResponse.json({ requete: 'lente' });
        }
        await releaseSlow.promise;
        return new HttpResponse(null, { status: 401 });
      }),
      http.get('/api/v1/rapide', ({ request }) =>
        request.headers.get('Authorization') === 'Bearer nouveau'
          ? HttpResponse.json({ requete: 'rapide' })
          : new HttpResponse(null, { status: 401 }),
      ),
    );
    const refresh = vi.fn(() => Promise.resolve('nouveau'));
    configureSessionRefresh(refresh);
    setAccessToken('ancien');

    const slow = httpClient.get<{ requete: string }>('/lente');
    const fast = await httpClient.get<{ requete: string }>('/rapide');
    expect(fast.data).toEqual({ requete: 'rapide' });
    expect(refresh).toHaveBeenCalledTimes(1);

    releaseSlow.resolve();
    const slowResult = await slow;

    expect(slowResult.data).toEqual({ requete: 'lente' });
    expect(refresh).toHaveBeenCalledTimes(1); // pas de deuxième renouvellement
    expect(slowTokens).toEqual(['Bearer ancien', 'Bearer nouveau']); // rejeu avec le jeton courant
  });

  it('échec du renouvellement : un seul essai pour les requêtes simultanées, jeton effacé, erreur 401', async () => {
    server.use(http.get('/api/v1/echo', () => new HttpResponse(null, { status: 401 })));
    const refresh = vi.fn(() => Promise.reject(new Error('refus du serveur')));
    configureSessionRefresh(refresh);
    setAccessToken('ancien');

    const outcomes = await Promise.allSettled([httpClient.get('/echo'), httpClient.get('/echo')]);

    expect(outcomes.map((o) => o.status)).toEqual(['rejected', 'rejected']);
    for (const outcome of outcomes) {
      if (outcome.status === 'rejected') {
        expect(outcome.reason).toBeInstanceOf(ApiError);
        expect(outcome.reason).toMatchObject({ status: 401 });
      }
    }
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(getAccessToken()).toBeNull();
  });

  it('échec du renouvellement : un jeton obtenu entre‑temps n’est pas effacé', async () => {
    server.use(http.get('/api/v1/echo', () => new HttpResponse(null, { status: 401 })));
    configureSessionRefresh(() => {
      setAccessToken('connexion-recente'); // ex. nouvelle connexion pendant le renouvellement
      return Promise.resolve(null);
    });
    setAccessToken('ancien');

    await expect(httpClient.get('/echo')).rejects.toMatchObject({ status: 401 });
    expect(getAccessToken()).toBe('connexion-recente');
  });

  it('renouvellement réussi : n’écrase pas un jeton plus récent obtenu entre‑temps', async () => {
    const tokens: (string | null)[] = [];
    server.use(
      http.get('/api/v1/echo', ({ request }) => {
        const authorization = request.headers.get('Authorization');
        tokens.push(authorization);
        return authorization === 'Bearer ancien'
          ? new HttpResponse(null, { status: 401 })
          : HttpResponse.json({ ok: true });
      }),
    );
    configureSessionRefresh(() => {
      setAccessToken('plus-recent');
      return Promise.resolve('issu-du-renouvellement');
    });
    setAccessToken('ancien');

    await httpClient.get('/echo');

    expect(getAccessToken()).toBe('plus-recent');
    expect(tokens).toEqual(['Bearer ancien', 'Bearer plus-recent']);
  });

  it('session effacée entre l’envoi et le 401 : ni renouvellement ni rejeu', async () => {
    let calls = 0;
    server.use(
      http.get('/api/v1/echo', () => {
        calls += 1;
        clearAccessToken(); // déconnexion survenue pendant la requête
        return new HttpResponse(null, { status: 401 });
      }),
    );
    const refresh = vi.fn(() => Promise.resolve('nouveau'));
    configureSessionRefresh(refresh);
    setAccessToken('ancien');

    await expect(httpClient.get('/echo')).rejects.toMatchObject({ status: 401 });
    expect(calls).toBe(1);
    expect(refresh).not.toHaveBeenCalled();
    expect(getAccessToken()).toBeNull();
  });

  it('ne journalise jamais les jetons', async () => {
    const spies = [
      vi.spyOn(console, 'log'),
      vi.spyOn(console, 'info'),
      vi.spyOn(console, 'warn'),
      vi.spyOn(console, 'error'),
      vi.spyOn(console, 'debug'),
    ];
    // 401 systématique : parcourt envoi, renouvellement, rejeu et échec définitif.
    server.use(http.get('/api/v1/echo', () => new HttpResponse(null, { status: 401 })));
    configureSessionRefresh(() => Promise.resolve('secret-renouvele'));
    setAccessToken('secret-initial');

    await httpClient.get('/echo').catch(() => undefined);

    const logged = spies
      .flatMap((spy): unknown[] => spy.mock.calls.flat() as unknown[])
      .map((arg) => String(arg));
    expect(logged.some((text) => text.includes('secret-'))).toBe(false);
  });
});
