import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it, vi } from 'vitest';

import { AppProviders } from '@/app/AppProviders';
import { routes } from '@/app/routes';
import type { MeResponse } from '@/features/session/types';
import { meFixture } from '@/mocks/fixtures/me';
import { createQueryClient } from '@/shared/api/queryClient';
import { server } from '@/test/server';

vi.setConfig({ testTimeout: 20_000 });
const SLOW = { timeout: 6_000 };
const OWNER = ['SALE_CREATE', 'SALE_READ_ALL', 'SALE_DISCOUNT_UNLIMITED', 'CATALOG_READ', 'CUSTOMER_READ', 'STOCK_READ'];

const quote = (total: string) => ({
  lines: [
    {
      productId: 'p-1', productName: 'Savon noir', sku: null, unit: 'PIECE', quantity: '2', unitPrice: '1000',
      lineDiscount: '0', globalDiscountShare: '0', totalExclTax: total, taxAmount: '0', totalInclTax: total,
    },
  ],
  currency: 'XOF', globalDiscount: '0', vatEnabled: false, vatRate: '18', priceEntryMode: 'TTC',
  grossAmount: total, discountAmount: '0', totalExclTax: total, taxAmount: '0', totalInclTax: total,
  discountWithinLimit: true,
});

function backend() {
  const requests: { method: string; path: string; body: unknown; key: string | null }[] = [];
  const me: MeResponse = { ...meFixture, permissions: OWNER };
  const confirmed = {
    id: 's-1', reference: 'V-000001', status: 'CONFIRMED', customerId: null, customerName: null, currency: 'XOF',
    globalDiscount: '0', draftLines: [], quote: quote('2000'), paymentStatus: 'UNPAID', paidAmount: '0',
    grossMargin: null, costsComplete: null, authorName: 'Administratrice Démo', createdAt: '2026-11-19T10:00:00Z',
    confirmedAt: '2026-11-19T10:00:00Z', version: 1,
  };
  server.use(
    http.get('/api/v1/me', () => HttpResponse.json(me)),
    http.get('/api/v1/customers', () => HttpResponse.json({ content: [], page: { number: 0, size: 5, totalElements: 0, totalPages: 0 } })),
    http.get('/api/v1/products', () =>
      HttpResponse.json({
        content: [{ id: 'p-1', name: 'Savon noir', unit: 'PIECE', salePrice: '1000', currency: 'XOF', status: 'ACTIVE' }],
        page: { number: 0, size: 8, totalElements: 1, totalPages: 1 },
      }),
    ),
    // Quantités en stock affichées dans le catalogue de caisse (indicatives).
    http.get('/api/v1/stock', () =>
      HttpResponse.json({
        content: [{ productId: 'p-1', productName: 'Savon noir', sku: null, category: null, unit: 'PIECE', productStatus: 'ACTIVE',
          initialized: true, quantity: '7', minimumQuantity: '0', status: 'IN_STOCK', updatedAt: null }],
        page: { number: 0, size: 12, totalElements: 1, totalPages: 1 },
      }),
    ),
    http.post('/api/v1/sales/quote', async ({ request }) => {
      const body = (await request.json()) as { lines: { quantity: string }[] };
      return HttpResponse.json(quote(String(1000 * Number(body.lines[0]?.quantity ?? '1'))));
    }),
    http.post('/api/v1/sales', async ({ request }) => {
      requests.push({ method: 'POST', path: '/sales', body: await request.json(), key: request.headers.get('Idempotency-Key') });
      return HttpResponse.json({ ...confirmed, status: 'DRAFT', reference: null, paymentStatus: null }, { status: 201 });
    }),
    http.post('/api/v1/sales/:id/confirm', ({ request }) => {
      requests.push({ method: 'POST', path: '/confirm', body: null, key: request.headers.get('Idempotency-Key') });
      return HttpResponse.json(confirmed);
    }),
    http.get('/api/v1/sales/:id', () => HttpResponse.json(confirmed)),
  );
  return requests;
}

describe('vente', () => {
  it('panier, montants calculés par le serveur, confirmation explicite, vente non réglée', async () => {
    const requests = backend();
    const user = userEvent.setup();
    const router = createMemoryRouter(routes, { initialEntries: ['/sales/new'] });
    render(
      <AppProviders queryClient={createQueryClient()} runtime={{ mocksActive: false }}>
        <RouterProvider router={router} />
      </AppProviders>,
    );
    await user.type(await screen.findByPlaceholderText(/Rechercher un produit/, {}, SLOW), 'savon');
    // Catalogue de caisse : quantité en stock indicative (lue sur /stock, jamais calculée).
    expect(await screen.findByText(/En stock : 7/, {}, SLOW)).toBeVisible();
    await user.click(await screen.findByRole('button', { name: 'Ajouter · Savon noir' }, SLOW));
    const quantity = screen.getByLabelText(/Quantité/);
    // Boutons + / − : ajustent la saisie (le serveur recalcule les montants).
    await user.click(screen.getByRole('button', { name: 'Ajouter une unité de Savon noir' }));
    expect(quantity).toHaveValue('2');
    await user.click(screen.getByRole('button', { name: 'Retirer une unité de Savon noir' }));
    expect(quantity).toHaveValue('1');
    await user.clear(quantity);
    await user.type(quantity, '2');
    // Total affiché = total renvoyé par le serveur (aucun calcul local).
    expect(await screen.findByText('Total à payer', {}, SLOW)).toBeVisible();
    await waitFor(() => expect(screen.getAllByText(/2\s000\sF\s?CFA/).length).toBeGreaterThan(0), SLOW);
    expect(requests).toHaveLength(0);

    await user.click(screen.getByRole('button', { name: 'Confirmer la vente' }));
    const dialog = screen.getByRole('alertdialog');
    expect(within(dialog).getByText(/Le stock sera diminué/)).toBeVisible();
    await user.click(within(dialog).getByRole('button', { name: 'Confirmer la vente' }));
    expect(await screen.findByText('Vente V-000001 confirmée.', {}, SLOW)).toBeVisible();
    await waitFor(() => expect(router.state.location.pathname).toBe('/sales/s-1'));
    expect(await screen.findByText('Non réglée', {}, SLOW)).toBeVisible();
    expect(requests.map((r) => r.path)).toEqual(['/sales', '/confirm']);
    expect(requests[0]?.body).toEqual({ customerId: null, lines: [{ productId: 'p-1', quantity: '2', discount: null }], globalDiscount: null });
    expect(requests[0]?.key).not.toBe(requests[1]?.key);
    expect(requests[1]?.key).toMatch(/^[0-9a-f-]{36}$/);
  });
});
