import { describe, expect, it } from 'vitest';

import { ApiError } from './apiError';
import { createQueryClient } from './queryClient';

/** Politique de nouvelle tentative réellement configurée pour les requêtes. */
function retryPolicy(): (failureCount: number, error: unknown) => boolean {
  const retry = createQueryClient().getDefaultOptions().queries?.retry;
  if (typeof retry !== 'function') {
    throw new Error('politique de nouvelle tentative absente');
  }
  return retry as (failureCount: number, error: unknown) => boolean;
}

const error = (status: number | null, kind: ApiError['kind']) =>
  new ApiError({ status, code: 'X', kind, message: 'x' });

describe('queryClient — nouvelles tentatives (R7)', () => {
  it('429 : aucune nouvelle tentative automatique (Retry-After seulement affiché)', () => {
    expect(retryPolicy()(0, error(429, 'http'))).toBe(false);
  });

  it.each([400, 401, 403, 404, 409, 422])('%i : aucune nouvelle tentative', (status) => {
    expect(retryPolicy()(0, error(status, 'http'))).toBe(false);
  });

  it('5xx, coupure réseau et délai dépassé : 2 nouvelles tentatives au plus', () => {
    const retry = retryPolicy();
    for (const e of [error(500, 'http'), error(null, 'network'), error(null, 'timeout')]) {
      expect(retry(0, e)).toBe(true);
      expect(retry(1, e)).toBe(true);
      expect(retry(2, e)).toBe(false);
    }
  });

  it('mutations : jamais de nouvelle tentative', () => {
    expect(createQueryClient().getDefaultOptions().mutations?.retry).toBe(false);
  });
});
