import { delay, http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { server } from '@/test/server';

import { ApiError, parseRetryAfter, toApiError } from './apiError';
import { httpClient } from './httpClient';

async function requestError(path: string, config: { timeout?: number } = {}): Promise<ApiError> {
  const error: unknown = await httpClient.get(path, config).catch((e: unknown) => e);
  expect(error).toBeInstanceOf(ApiError);
  return error as ApiError;
}

describe('apiError — normalisation (R7)', () => {
  it('délai dépassé : kind timeout, code TIMEOUT, sans statut', async () => {
    server.use(
      http.get('/api/v1/lent', async () => {
        await delay(500);
        return HttpResponse.json({});
      }),
    );
    const error = await requestError('/lent', { timeout: 50 });
    expect(error).toMatchObject({ kind: 'timeout', code: 'TIMEOUT', status: null });
  });

  it('coupure réseau : kind network, code NETWORK_ERROR, sans statut', async () => {
    server.use(http.get('/api/v1/coupure', () => HttpResponse.error()));
    const error = await requestError('/coupure');
    expect(error).toMatchObject({ kind: 'network', code: 'NETWORK_ERROR', status: null });
  });

  it('429 : lit Retry-After en secondes', async () => {
    server.use(
      http.get('/api/v1/limite', () =>
        HttpResponse.json(
          { status: 429, code: 'RATE_LIMITED' },
          { status: 429, headers: { 'Retry-After': '30' } },
        ),
      ),
    );
    const error = await requestError('/limite');
    expect(error).toMatchObject({
      kind: 'http',
      status: 429,
      code: 'RATE_LIMITED',
      retryAfterSeconds: 30,
    });
  });

  it('erreurs de champ : code stable et message serveur facultatif', async () => {
    server.use(
      http.get('/api/v1/validation', () =>
        HttpResponse.json(
          {
            status: 400,
            code: 'VALIDATION_ERROR',
            errors: [
              { field: 'name', code: 'REQUIRED', message: 'ne doit pas être vide' },
              { field: 'phone', code: 'INVALID_FORMAT' },
              { field: 'legacy', message: 'ancien format sans code' },
              { field: 'ignored' },
              'invalide',
            ],
          },
          { status: 400 },
        ),
      ),
    );
    const error = await requestError('/validation');
    expect(error.fieldErrors).toEqual([
      { field: 'name', code: 'REQUIRED', message: 'ne doit pas être vide' },
      { field: 'phone', code: 'INVALID_FORMAT', message: null },
      { field: 'legacy', code: null, message: 'ancien format sans code' },
    ]);
  });

  it('réponse non JSON (ex. page HTML d’un proxy en 502) : UNKNOWN_ERROR avec statut', async () => {
    server.use(
      http.get(
        '/api/v1/proxy',
        () =>
          new HttpResponse('<html>Bad Gateway</html>', {
            status: 502,
            headers: { 'Content-Type': 'text/html' },
          }),
      ),
    );
    const error = await requestError('/proxy');
    expect(error).toMatchObject({ kind: 'http', status: 502, code: 'UNKNOWN_ERROR' });
  });

  it('erreur hors Axios : kind unknown', () => {
    expect(toApiError(new Error('boum'))).toMatchObject({
      kind: 'unknown',
      code: 'UNKNOWN_ERROR',
      status: null,
    });
  });
});

describe('parseRetryAfter', () => {
  const now = Date.parse('2026-11-19T10:00:00Z');

  it.each([
    ['30', 30],
    ['0', 0],
    [' 12 ', 12],
    ['Thu, 19 Nov 2026 10:00:45 GMT', 45],
    ['Thu, 19 Nov 2026 09:59:00 GMT', 0], // date passée → 0
    ['pas une date', null],
    ['', null],
    [undefined, null],
  ])('%s → %s', (value, expected) => {
    expect(parseRetryAfter(value, now)).toBe(expected);
  });
});
