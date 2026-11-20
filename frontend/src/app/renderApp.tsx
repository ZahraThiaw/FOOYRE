import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';

import { refreshSession } from '@/features/auth/api';
import { createQueryClient } from '@/shared/api/queryClient';
import { configureSessionRefresh } from '@/shared/auth/sessionRefresh';

import { AppProviders } from './AppProviders';
import { routes } from './routes';
import type { RuntimeInfo } from './runtimeContext';

export function renderApp(container: HTMLElement, runtime: RuntimeInfo): void {
  // Renouvellement de session par le cookie HttpOnly (ADR‑04) : utilisé par httpClient sur 401.
  configureSessionRefresh(refreshSession);

  const router = createBrowserRouter(routes);
  const queryClient = createQueryClient();

  createRoot(container).render(
    <StrictMode>
      <AppProviders queryClient={queryClient} runtime={runtime}>
        <RouterProvider router={router} />
      </AppProviders>
    </StrictMode>,
  );
}
