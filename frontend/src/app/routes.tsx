import type { RouteObject } from 'react-router';

import { LoginPage } from '@/features/auth/pages/LoginPage';
import { CategoriesPage } from '@/features/catalog/pages/CategoriesPage';
import { ProductDetailPage } from '@/features/catalog/pages/ProductDetailPage';
import { ProductFormPage } from '@/features/catalog/pages/ProductFormPage';
import { ProductsPage } from '@/features/catalog/pages/ProductsPage';
import { NotFoundPage } from '@/features/errors/pages/NotFoundPage';
import { RouteErrorPage } from '@/features/errors/pages/RouteErrorPage';
import { HomePage } from '@/features/home/pages/HomePage';
import { RequirePermission } from '@/features/session/components/RequirePermission';
import { AssistantPage } from '@/features/assistant/AssistantPage';
import { CustomerDetailPage } from '@/features/customers/pages/CustomerDetailPage';
import { NewSalePage, SaleDetailPage, SalesPage } from '@/features/sales/pages/SalesPages';
import { SettingsPage } from '@/features/settings/SettingsPage';
import { CustomerFormPage } from '@/features/customers/pages/CustomerFormPage';
import { CustomersPage } from '@/features/customers/pages/CustomersPage';
import { RequireSession } from '@/features/session/components/RequireSession';
import { StockHistoryPage } from '@/features/stock/pages/StockHistoryPage';
import { StockPage } from '@/features/stock/pages/StockPage';
import { StockProductPage } from '@/features/stock/pages/StockProductPage';
import { AppLayout } from '@/layouts/AppLayout';

/**
 * Routes de l'application.
 * - `/login` : seule page publique.
 * - Tout le reste est privé (RequireSession) et affiché dans AppLayout. Les modules métier
 *   (catalogue, ventes, finances…) seront ajoutés ici dans les phases suivantes, chacun depuis
 *   son dossier `features/`, avec son entrée dans `app/navigation.ts`.
 */
export const routes: RouteObject[] = [
  { path: '/login', element: <LoginPage />, errorElement: <RouteErrorPage /> },
  {
    path: '/',
    element: <RequireSession />,
    errorElement: <RouteErrorPage />,
    children: [
      {
        element: <AppLayout />,
        children: [
          { index: true, element: <HomePage /> },
          {
            path: 'products',
            element: (
              <RequirePermission permission="CATALOG_READ">
                <ProductsPage />
              </RequirePermission>
            ),
          },
          {
            path: 'products/new',
            element: (
              <RequirePermission permission="CATALOG_WRITE">
                <ProductFormPage />
              </RequirePermission>
            ),
          },
          {
            path: 'products/:id',
            element: (
              <RequirePermission permission="CATALOG_READ">
                <ProductDetailPage />
              </RequirePermission>
            ),
          },
          {
            path: 'products/:id/edit',
            element: (
              <RequirePermission permission="CATALOG_WRITE">
                <ProductFormPage />
              </RequirePermission>
            ),
          },
          {
            path: 'sales',
            element: (
              <RequirePermission permission="SALE_READ_ALL">
                <SalesPage />
              </RequirePermission>
            ),
          },
          {
            path: 'sales/new',
            element: (
              <RequirePermission permission="SALE_CREATE">
                <NewSalePage />
              </RequirePermission>
            ),
          },
          {
            path: 'sales/:id',
            element: (
              <RequirePermission permission="SALE_READ_ALL">
                <SaleDetailPage />
              </RequirePermission>
            ),
          },
          {
            path: 'assistant',
            element: (
              <RequirePermission permission="AI_USE">
                <AssistantPage />
              </RequirePermission>
            ),
          },
          {
            path: 'settings',
            element: (
              <RequirePermission permission="BUSINESS_SETTINGS_READ">
                <SettingsPage />
              </RequirePermission>
            ),
          },
          {
            path: 'clients',
            element: (
              <RequirePermission permission="CUSTOMER_READ">
                <CustomersPage />
              </RequirePermission>
            ),
          },
          {
            path: 'clients/new',
            element: (
              <RequirePermission permission="CUSTOMER_WRITE">
                <CustomerFormPage />
              </RequirePermission>
            ),
          },
          {
            path: 'clients/:id',
            element: (
              <RequirePermission permission="CUSTOMER_READ">
                <CustomerDetailPage />
              </RequirePermission>
            ),
          },
          {
            path: 'clients/:id/edit',
            element: (
              <RequirePermission permission="CUSTOMER_WRITE">
                <CustomerFormPage />
              </RequirePermission>
            ),
          },
          {
            path: 'stock',
            element: (
              <RequirePermission permission="STOCK_READ">
                <StockPage />
              </RequirePermission>
            ),
          },
          {
            path: 'stock/history',
            element: (
              <RequirePermission permission="STOCK_READ">
                <StockHistoryPage />
              </RequirePermission>
            ),
          },
          {
            path: 'stock/:productId',
            element: (
              <RequirePermission permission="STOCK_READ">
                <StockProductPage />
              </RequirePermission>
            ),
          },
          {
            path: 'categories',
            element: (
              <RequirePermission permission="CATALOG_READ">
                <CategoriesPage />
              </RequirePermission>
            ),
          },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
    ],
  },
];
