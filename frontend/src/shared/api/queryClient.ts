import { QueryClient } from '@tanstack/react-query';

import { ApiError } from './apiError';

const MAX_RETRIES = 2;

/** Pas de nouvelle tentative sur les erreurs 4xx (droits, validation, ressource absente). */
function shouldRetry(failureCount: number, error: unknown): boolean {
  if (error instanceof ApiError && error.status !== null && error.status < 500) {
    return false;
  }
  return failureCount < MAX_RETRIES;
}

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: shouldRetry,
        staleTime: 30_000,
        refetchOnWindowFocus: false,
      },
      mutations: {
        retry: false,
      },
    },
  });
}
