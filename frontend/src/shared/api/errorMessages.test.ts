import { describe, expect, it } from 'vitest';

import apiErrorsFr from '@/shared/i18n/locales/fr/apiErrors.json';

import { ApiError } from './apiError';
import {
  describeApiError,
  fieldErrorMessageKey,
  KNOWN_ERROR_CODES,
  KNOWN_FIELD_ERROR_CODES,
} from './errorMessages';

const httpError = (status: number, code = 'UNKNOWN_ERROR', extra: Partial<ApiError> = {}) =>
  new ApiError({ status, code, kind: 'http', message: 'texte technique', ...extra });

describe('describeApiError — statuts HTTP (R7)', () => {
  it.each([
    [400, 'apiErrors:status.badRequest'],
    [401, 'apiErrors:status.unauthorized'],
    [403, 'apiErrors:status.forbidden'],
    [404, 'apiErrors:status.notFound'],
    [409, 'apiErrors:status.conflict'],
    [422, 'apiErrors:status.unprocessable'],
    [429, 'apiErrors:status.tooManyRequests'],
    [500, 'apiErrors:status.server'],
    [503, 'apiErrors:status.server'],
    [418, 'apiErrors:status.badRequest'],
  ])('%i sans code connu → %s', (status, key) => {
    expect(describeApiError(httpError(status)).messageKey).toBe(key);
  });

  it('un code connu prime sur le statut', () => {
    expect(describeApiError(httpError(409, 'INSUFFICIENT_STOCK')).messageKey).toBe(
      'apiErrors:codes.INSUFFICIENT_STOCK',
    );
    expect(describeApiError(httpError(404, 'NOT_FOUND')).messageKey).toBe(
      'apiErrors:codes.NOT_FOUND',
    );
  });

  it('un code inconnu retombe sur le statut, jamais sur le texte du serveur', () => {
    const description = describeApiError(httpError(422, 'NOUVEAU_CODE_FUTUR'));
    expect(description.messageKey).toBe('apiErrors:status.unprocessable');
    expect(JSON.stringify(description)).not.toContain('texte technique');
  });

  it('coupure réseau, délai dépassé, erreur inconnue', () => {
    const base = { status: null, message: 'x' };
    expect(
      describeApiError(new ApiError({ ...base, code: 'NETWORK_ERROR', kind: 'network' }))
        .messageKey,
    ).toBe('apiErrors:network');
    expect(
      describeApiError(new ApiError({ ...base, code: 'TIMEOUT', kind: 'timeout' })).messageKey,
    ).toBe('apiErrors:timeout');
    expect(describeApiError(new Error('x')).messageKey).toBe('apiErrors:unknown');
  });

  it('référence traceId uniquement pour les erreurs serveur (5xx)', () => {
    expect(describeApiError(httpError(500, 'INTERNAL_ERROR', { traceId: 't-1' })).traceId).toBe(
      't-1',
    );
    expect(describeApiError(httpError(404, 'NOT_FOUND', { traceId: 't-2' })).traceId).toBeNull();
  });

  it('Retry-After uniquement pour 429', () => {
    expect(
      describeApiError(httpError(429, 'RATE_LIMITED', { retryAfterSeconds: 20 })).retryAfterSeconds,
    ).toBe(20);
    expect(
      describeApiError(httpError(503, 'AI_UNAVAILABLE', { retryAfterSeconds: 20 }))
        .retryAfterSeconds,
    ).toBeNull();
  });
});

describe('fieldErrorMessageKey', () => {
  it.each(KNOWN_FIELD_ERROR_CODES.map((code) => [code]))('code %s traduit', (code) => {
    expect(fieldErrorMessageKey({ field: 'f', code, message: null })).toBe(
      `apiErrors:fields.${code}`,
    );
  });

  it('code absent ou inconnu → message générique, jamais le message du serveur', () => {
    expect(fieldErrorMessageKey({ field: 'f', code: null, message: 'texte serveur' })).toBe(
      'apiErrors:fields.INVALID_VALUE',
    );
    expect(fieldErrorMessageKey({ field: 'f', code: 'AUTRE', message: null })).toBe(
      'apiErrors:fields.INVALID_VALUE',
    );
  });
});

describe('catalogue apiErrors — synchronisé avec les codes connus', () => {
  it('chaque code connu a une traduction non vide, et inversement', () => {
    expect(Object.keys(apiErrorsFr.codes).sort()).toEqual([...KNOWN_ERROR_CODES].sort());
    for (const text of Object.values(apiErrorsFr.codes)) {
      expect(text.trim()).not.toBe('');
    }
  });

  it('chaque code de champ connu a une traduction, et inversement', () => {
    expect(Object.keys(apiErrorsFr.fields).sort()).toEqual([...KNOWN_FIELD_ERROR_CODES].sort());
  });
});
