import '@testing-library/jest-dom/vitest';
import '@/shared/i18n';

import { cleanup } from '@testing-library/react';
import { afterAll, afterEach, beforeAll } from 'vitest';

import { clearAccessToken } from '@/shared/auth/accessTokenStore';
import { configureSessionRefresh } from '@/shared/auth/sessionRefresh';

import { server } from './server';

// Toute requête non simulée fait échouer le test : aucun appel réseau réel.
beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));

afterEach(() => {
  cleanup();
  server.resetHandlers();
  clearAccessToken();
  configureSessionRefresh(null);
});

afterAll(() => server.close());
