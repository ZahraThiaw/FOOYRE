import { render, screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it, vi } from 'vitest';

import { AppProviders } from '@/app/AppProviders';
import { routes } from '@/app/routes';
import type { MeResponse } from '@/features/session/types';
import { meFixture } from '@/mocks/fixtures/me';
import { createQueryClient } from '@/shared/api/queryClient';
import { server } from '@/test/server';

vi.setConfig({ testTimeout: 15_000 });
const SLOW = { timeout: 5_000 };

const OWNER = ['DASHBOARD_FULL', 'COST_READ', 'STOCK_READ', 'SALE_READ_ALL', 'SALE_CREATE', 'CUSTOMER_READ', 'CATALOG_WRITE'];
const EMPLOYEE = ['DASHBOARD_OWN_DAY', 'STOCK_READ', 'SALE_CREATE'];

const metrics = (revenue: string, margin: string | null) => ({
  salesCount: 3, revenueInclTax: revenue, revenueExclTax: revenue, collections: '0', grossMargin: margin, marginReliable: margin !== null, currency: 'XOF',
});

function backend(permissions: readonly string[], financials: boolean) {
  const me: MeResponse = { ...meFixture, permissions };
  server.use(
    http.get('/api/v1/me', () => HttpResponse.json(me)),
    http.get('/api/v1/dashboard', () =>
      HttpResponse.json({
        today: financials ? metrics('45000', '12000') : null,
        month: financials ? metrics('1280000', '412000') : null,
        salesCountToday: 2, salesCountMonth: 38, activeCustomers: 14, outOfStock: 1, lowStock: 2, trackedProducts: 9,
        recentSales: [], financialsVisible: financials, paymentsConnected: false,
      }),
    ),
    http.get('/api/v1/stock/summary', () =>
      HttpResponse.json({ trackedProducts: 9, notInitializedProducts: 1, inStock: 6, lowStock: 2, outOfStock: 1 }),
    ),
    http.get('/api/v1/stock/alerts', () =>
      HttpResponse.json({
        content: [{ productId: 'p-1', productName: 'Robe wax', sku: null, category: null, unit: 'PIECE', productStatus: 'ACTIVE',
          initialized: true, quantity: '0', minimumQuantity: '2', status: 'OUT_OF_STOCK', updatedAt: null }],
        page: { number: 0, size: 5, totalElements: 1, totalPages: 1 },
      }),
    ),
    http.get('/api/v1/stock/movements', () =>
      HttpResponse.json({ content: [], page: { number: 0, size: 5, totalElements: 0, totalPages: 0 } }),
    ),
  );
}

function renderHome() {
  const router = createMemoryRouter(routes, { initialEntries: ['/'] });
  render(
    <AppProviders queryClient={createQueryClient()} runtime={{ mocksActive: false }}>
      <RouterProvider router={router} />
    </AppProviders>,
  );
}

describe('tableau de bord', () => {
  it('propriétaire : chiffres du serveur, marge brute libellée, Wave non connecté, analyses indisponibles signalées', async () => {
    backend(OWNER, true);
    renderHome();
    expect(await screen.findByText(/45\s000\sF\s?CFA/, {}, SLOW)).toBeVisible();
    expect(screen.getByText(/1\s280\s000\sF\s?CFA/)).toBeVisible();
    expect(screen.getByText('Marge brute du mois')).toBeVisible();
    expect(screen.getByText(/Ce n'est pas un bénéfice/)).toBeVisible();
    expect(screen.getByText(/paiement Wave non connecté/)).toBeVisible();
    // Aucune courbe ni classement inventés : états explicites.
    expect(screen.getByRole('region', { name: 'Évolution des ventes' })).toHaveTextContent(/Aucun chiffre n'est estimé/);
    expect(screen.getByRole('region', { name: 'Produits les plus vendus' })).toHaveTextContent(/Aucun classement approximatif/);
    // Alertes réelles de stock.
    const watch = screen.getByRole('region', { name: 'À surveiller' });
    expect(await within(watch).findByRole('link', { name: 'Robe wax' }, SLOW)).toHaveAttribute('href', '/stock/p-1');
    // Raccourcis selon les permissions.
    const shortcuts = screen.getByRole('navigation', { name: 'Actions rapides' });
    expect(within(shortcuts).getByRole('link', { name: 'Nouvelle vente' })).toHaveAttribute('href', '/sales/new');
    expect(within(shortcuts).queryByRole('link', { name: 'Nouveau client' })).toBeNull();
  });

  it('employée : volumes uniquement, aucun montant ni analyse financière', async () => {
    backend(EMPLOYEE, false);
    renderHome();
    expect(await screen.findByText('Ventes du jour', {}, SLOW)).toBeVisible();
    expect(screen.getByText('38')).toBeVisible();
    expect(screen.queryByText(/F\s?CFA/)).toBeNull();
    expect(screen.queryByText('Marge brute du mois')).toBeNull();
    expect(screen.queryByRole('region', { name: 'Évolution des ventes' })).toBeNull();
    expect(screen.getByText(/sans montants/)).toBeVisible();
  });
});
