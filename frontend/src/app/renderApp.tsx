import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';

import { createQueryClient } from '@/shared/api/queryClient';

import { AppProviders } from './AppProviders';
import { routes } from './routes';
import type { RuntimeInfo } from './runtimeContext';

export function renderApp(container: HTMLElement, runtime: RuntimeInfo): void {
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
