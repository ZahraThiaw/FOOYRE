import type { RouteObject } from 'react-router';

import { NotFoundPage } from '@/features/errors/pages/NotFoundPage';
import { RouteErrorPage } from '@/features/errors/pages/RouteErrorPage';
import { HomePage } from '@/features/home/pages/HomePage';
import { AppLayout } from '@/layouts/AppLayout';

/**
 * Routes de l'application. Les modules métier (catalogue, ventes, finances…) seront
 * ajoutés dans les phases suivantes, chacun depuis son dossier `features/`.
 */
export const routes: RouteObject[] = [
  {
    path: '/',
    element: <AppLayout />,
    errorElement: <RouteErrorPage />,
    children: [
      { index: true, element: <HomePage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];
