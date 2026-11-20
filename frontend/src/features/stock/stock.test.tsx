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

import type { StockItem, StockMovement } from './types';

// Parcours complets dans l'interface : plus lents quand toute la suite s'exécute en parallèle.
vi.setConfig({ testTimeout: 15_000 });
const SLOW = { timeout: 5_000 };

const OWNER = ['STOCK_READ', 'STOCK_ADJUST', 'COST_READ', 'CATALOG_READ', 'BUSINESS_SETTINGS_READ', 'BUSINESS_SETTINGS_UPDATE'];
const MANAGER = ['STOCK_READ', 'STOCK_ADJUST', 'COST_READ', 'CATALOG_READ', 'BUSINESS_SETTINGS_READ'];
const EMPLOYEE = ['STOCK_READ', 'CATALOG_READ'];

function item(id: string, name: string, extra: Partial<StockItem> = {}): StockItem {
  return {
    productId: id,
    productName: name,
    sku: null,
    category: null,
    unit: 'PIECE',
    productStatus: 'ACTIVE',
    initialized: true,
    quantity: '20',
    minimumQuantity: '0',
    status: 'IN_STOCK',
    updatedAt: '2026-11-19T10:00:00Z',
    ...extra,
  };
}

interface Recorded {
  readonly method: string;
  readonly path: string;
  readonly params: Record<string, string>;
  readonly body: unknown;
  readonly key: string | null;
}

/** Backend du stock SIMULÉ (tests uniquement) : applique les mêmes règles que le serveur réel. */
function stockBackend(options: {
  readonly permissions: readonly string[];
  readonly items?: StockItem[];
  readonly allowNegativeStock?: boolean;
  readonly failNextOperationWithNetworkError?: boolean;
}) {
  const items = [...(options.items ?? [])];
  const movements: StockMovement[] = [];
  const requests: Recorded[] = [];
  let allowNegative = options.allowNegativeStock ?? false;
  let failNext = options.failNextOperationWithNetworkError ?? false;
  const settings = () => ({
    allowNegativeStock: allowNegative,
    vatEnabled: false,
    vatRate: '18.00',
    priceEntryMode: 'TTC',
    employeeMaxDiscountRate: '0.00',
  });
  const me: MeResponse = { ...meFixture, permissions: options.permissions };
  const record = async (request: Request) => {
    const url = new URL(request.url);
    const body: unknown = request.method === 'GET' ? null : await request.json();
    requests.push({
      method: request.method,
      path: url.pathname,
      params: Object.fromEntries(url.searchParams.entries()),
      body,
      key: request.headers.get('Idempotency-Key'),
    });
    return { url, body: body as Record<string, string> | null };
  };
  const page = <T,>(content: T[], url: URL) => {
    const number = Number(url.searchParams.get('page') ?? '0');
    const size = Number(url.searchParams.get('size') ?? '20');
    return {
      content: content.slice(number * size, number * size + size),
      page: { number, size, totalElements: content.length, totalPages: Math.ceil(content.length / size) },
    };
  };
  const apply = (productId: string, type: StockMovement['type'], quantity: string) => {
    const index = items.findIndex((i) => i.productId === productId);
    const current = items[index] as StockItem;
    const before = Number(current.quantity ?? '0'); // tests : petites valeurs entières uniquement
    const delta = Number(quantity) * (type === 'EXIT' || type === 'ADJUSTMENT_OUT' ? -1 : 1);
    const after = before + delta;
    if (delta < 0 && after < 0 && !allowNegative) return null;
    const minimum = Number(current.minimumQuantity ?? '0');
    const updated: StockItem = {
      ...current,
      initialized: true,
      quantity: String(after),
      minimumQuantity: current.minimumQuantity ?? '0',
      status: after <= 0 ? 'OUT_OF_STOCK' : after <= minimum ? 'LOW_STOCK' : 'IN_STOCK',
    };
    items[index] = updated;
    const movement: StockMovement = {
      id: `m-${movements.length + 1}`,
      productId,
      productName: current.productName,
      unit: current.unit,
      type,
      quantity,
      quantityBefore: String(before),
      quantityAfter: String(after),
      reason: null,
      reference: null,
      author: { id: 'u-1', fullName: 'Administratrice Démo' },
      createdAt: '2026-11-19T11:00:00Z',
    };
    movements.unshift(movement);
    return { movement, stock: updated };
  };
  const operation = (type: (body: Record<string, string>) => StockMovement['type']) =>
    async ({ request, params }: { request: Request; params: Record<string, string | readonly string[] | undefined> }) => {
      const { body } = await record(request);
      if (failNext) {
        failNext = false;
        return HttpResponse.error();
      }
      const applied = apply(String(params.productId), type(body ?? {}), String(body?.quantity));
      const result =
        applied && body?.unitCost && options.permissions.includes('COST_READ')
          ? { ...applied, movement: { ...applied.movement, unitCost: body.unitCost } }
          : applied;
      if (result && result !== applied) movements[0] = result.movement;
      return result
        ? HttpResponse.json(result, { status: 201 })
        : HttpResponse.json({ status: 409, code: 'INSUFFICIENT_STOCK' }, { status: 409 });
    };

  server.use(
    http.get('/api/v1/me', () => HttpResponse.json(me)),
    http.get('/api/v1/categories', () =>
      HttpResponse.json({ content: [], page: { number: 0, size: 100, totalElements: 0, totalPages: 0 } }),
    ),
    http.get('/api/v1/stock/summary', async ({ request }) => {
      await record(request);
      const tracked = items.filter((i) => i.initialized);
      return HttpResponse.json({
        trackedProducts: tracked.length,
        notInitializedProducts: items.length - tracked.length,
        inStock: tracked.filter((i) => i.status === 'IN_STOCK').length,
        lowStock: tracked.filter((i) => i.status === 'LOW_STOCK').length,
        outOfStock: tracked.filter((i) => i.status === 'OUT_OF_STOCK').length,
        ...(options.permissions.includes('COST_READ')
          ? { estimatedValue: { amount: '17400', currency: 'XOF', productsWithoutReferencePrice: 1 } }
          : {}),
      });
    }),
    http.get('/api/v1/stock/movements', async ({ request }) => {
      const { url } = await record(request);
      return HttpResponse.json(page(movements, url));
    }),
    http.get('/api/v1/stock', async ({ request }) => {
      const { url } = await record(request);
      const status = url.searchParams.get('status') ?? 'ALL';
      const filtered = items.filter((i) =>
        status === 'ALL' ? true : status === 'ALERT' ? i.status === 'LOW_STOCK' || i.status === 'OUT_OF_STOCK' : i.status === status,
      );
      return HttpResponse.json(page(filtered, url));
    }),
    http.get('/api/v1/stock/:productId/movements', async ({ request, params }) => {
      const { url } = await record(request);
      return HttpResponse.json(page(movements.filter((m) => m.productId === params.productId), url));
    }),
    http.get('/api/v1/stock/:productId', async ({ request, params }) => {
      await record(request);
      const found = items.find((i) => i.productId === params.productId);
      return found ? HttpResponse.json(found) : HttpResponse.json({ status: 404, code: 'NOT_FOUND' }, { status: 404 });
    }),
    http.post('/api/v1/stock/:productId/initialize', operation(() => 'INITIAL')),
    http.post('/api/v1/stock/:productId/entries', operation(() => 'ENTRY')),
    http.post('/api/v1/stock/:productId/exits', operation(() => 'EXIT')),
    http.post(
      '/api/v1/stock/:productId/adjustments',
      operation((body) => (body.direction === 'IN' ? 'ADJUSTMENT_IN' : 'ADJUSTMENT_OUT')),
    ),
    http.put('/api/v1/stock/:productId/minimum-quantity', async ({ request, params }) => {
      const { body } = await record(request);
      const index = items.findIndex((i) => i.productId === params.productId);
      const current = items[index] as StockItem;
      const minimum = String(body?.minimumQuantity);
      const quantity = Number(current.quantity ?? '0');
      items[index] = {
        ...current,
        minimumQuantity: minimum,
        status: quantity <= 0 ? 'OUT_OF_STOCK' : quantity <= Number(minimum) ? 'LOW_STOCK' : 'IN_STOCK',
      };
      return HttpResponse.json(items[index]);
    }),
    // Paramètres complets de l'entreprise (la page Paramètres lit aussi la TVA).
    http.get('/api/v1/business/settings', async ({ request }) => {
      await record(request);
      return HttpResponse.json(settings());
    }),
    http.put('/api/v1/business/settings', async ({ request }) => {
      const { body } = await record(request);
      allowNegative = Boolean((body as unknown as { allowNegativeStock: boolean }).allowNegativeStock);
      return HttpResponse.json(settings());
    }),
  );
  return { requests, items, movements };
}

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  render(
    <AppProviders queryClient={createQueryClient()} runtime={{ mocksActive: false }}>
      <RouterProvider router={router} />
    </AppProviders>,
  );
  return router;
}

const last = (requests: Recorded[], method: string, suffix: string) =>
  [...requests].reverse().find((r) => r.method === method && r.path.endsWith(suffix));

describe('page Stock', () => {
  it('navigation, indicateurs, liste, états lisibles et valeur estimée (COST_READ)', async () => {
    stockBackend({
      permissions: OWNER,
      items: [
        item('p-1', 'Savon noir', { quantity: '22', minimumQuantity: '10' }),
        item('p-2', 'Riz brisé', { unit: 'KILOGRAM', quantity: '7.5', minimumQuantity: '10', status: 'LOW_STOCK' }),
        item('p-3', 'Bougie', { initialized: false, quantity: null, minimumQuantity: null, status: 'NOT_INITIALIZED' }),
      ],
    });
    renderAt('/stock');
    expect(await screen.findByRole('link', { name: 'Savon noir' }, SLOW)).toBeVisible();
    const nav = screen.getByRole('navigation', { name: 'Navigation principale' });
    expect(within(nav).getByRole('link', { name: 'Stock' })).toHaveAttribute('href', '/stock');
    expect(screen.getByText('7,5 kg')).toBeVisible();
    expect(screen.getByText('Stock faible', { selector: 'span' })).toBeVisible(); // libellé, pas seulement une couleur
    expect(screen.getByText('Non suivi', { selector: 'span' })).toBeVisible();
    expect(await screen.findByText('Valeur estimée du stock')).toBeVisible();
    expect(screen.getByText(/ni le coût réel des articles en stock, ni une valeur comptable/)).toBeVisible();
    expect(screen.getByText('1 produit actif n\'est pas encore suivi.')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Initialiser le stock' })).toBeVisible();
  });

  it('employée : consultation sans valeur estimée ni actions ni paramètres', async () => {
    stockBackend({ permissions: EMPLOYEE, items: [item('p-1', 'Savon noir')] });
    renderAt('/stock');
    expect(await screen.findByRole('link', { name: 'Savon noir' }, SLOW)).toBeVisible();
    await screen.findByText('Produits suivis');
    expect(screen.queryByText('Valeur estimée du stock')).toBeNull();
    expect(screen.queryByRole('button', { name: /Entrée|Sortie|Ajuster|Initialiser/ })).toBeNull();
    expect(screen.queryByText('Paramètres du stock')).toBeNull();
  });

  it('filtres, indicateur cliquable et pagination envoyés au serveur', async () => {
    const backend = stockBackend({
      permissions: OWNER,
      items: Array.from({ length: 25 }, (_, i) => item(`p-${i}`, `Article ${String(i).padStart(2, '0')}`)),
    });
    const user = userEvent.setup();
    renderAt('/stock');
    await screen.findByRole('link', { name: 'Article 00' }, SLOW);
    await user.selectOptions(screen.getByLabelText('État du stock'), 'ALERT');
    await waitFor(() => expect(last(backend.requests, 'GET', '/api/v1/stock')?.params.status).toBe('ALERT'), SLOW);
    await user.click(screen.getByRole('button', { name: /Produits suivis/ }));
    await waitFor(() => expect(last(backend.requests, 'GET', '/api/v1/stock')?.params.status).toBe('TRACKED'), SLOW);
    await user.selectOptions(screen.getByLabelText('État du stock'), 'ALL');
    await user.selectOptions(screen.getByLabelText('Trier par'), 'quantity,asc');
    await waitFor(() => expect(last(backend.requests, 'GET', '/api/v1/stock')?.params.sort).toBe('quantity,asc'), SLOW);
    await user.click(await screen.findByRole('button', { name: 'Page suivante' }, SLOW));
    await waitFor(() => expect(last(backend.requests, 'GET', '/api/v1/stock')?.params.page).toBe('1'), SLOW);
  });
});

describe('opérations de stock', () => {
  it('initialisation : validation, confirmation, clé d’idempotence, quantité envoyée en chaîne', async () => {
    const backend = stockBackend({
      permissions: OWNER,
      items: [item('p-1', 'Bougie', { initialized: false, quantity: null, minimumQuantity: null, status: 'NOT_INITIALIZED' })],
    });
    const user = userEvent.setup();
    renderAt('/stock');
    await user.click(await screen.findByRole('button', { name: 'Initialiser le stock' }));
    const dialog = screen.getByRole('dialog', { name: 'Initialiser le stock' });

    await user.type(within(dialog).getByLabelText(/Quantité initiale/), '2,5');
    await user.click(within(dialog).getByRole('button', { name: 'Vérifier' }));
    expect(within(dialog).getByText('Trop de décimales pour cette unité.')).toBeVisible(); // pièces : entiers
    await user.clear(within(dialog).getByLabelText(/Quantité initiale/));
    await user.type(within(dialog).getByLabelText(/Quantité initiale/), '0');
    await user.click(within(dialog).getByRole('button', { name: 'Vérifier' }));
    expect(within(dialog).getByText('La quantité doit être supérieure à zéro.')).toBeVisible();

    await user.clear(within(dialog).getByLabelText(/Quantité initiale/));
    await user.type(within(dialog).getByLabelText(/Quantité initiale/), '20');
    await user.click(within(dialog).getByRole('button', { name: 'Vérifier' }));
    expect(screen.getByRole('dialog', { name: "Confirmer l'opération" })).toBeVisible();
    expect(backend.requests.some((r) => r.method === 'POST')).toBe(false);
    await user.click(screen.getByRole('button', { name: 'Confirmer' }));

    expect(await screen.findByText('Stock initialisé : 20 pièce(s).')).toBeVisible();
    const sent = last(backend.requests, 'POST', '/initialize');
    expect(sent?.body).toEqual({ quantity: '20', minimumQuantity: '0', note: null });
    expect(sent?.key).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  });

  it('sortie : quantité disponible, aperçu exact, refus expliqué si le stock deviendrait négatif', async () => {
    const backend = stockBackend({ permissions: OWNER, items: [item('p-1', 'Savon noir', { quantity: '7' })] });
    const user = userEvent.setup();
    renderAt('/stock');
    await user.click(await screen.findByRole('button', { name: 'Sortie · Savon noir' }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('Quantité disponible : 7 pièce(s)')).toBeVisible();

    await user.click(within(dialog).getByRole('button', { name: 'Vérifier' }));
    expect(within(dialog).getByText("Indiquez le motif de l'opération.")).toBeVisible();

    await user.type(within(dialog).getByLabelText(/Quantité/), '9');
    await user.type(within(dialog).getByLabelText('Motif'), 'Casse');
    expect(await within(dialog).findByText(/Stock insuffisant : la quantité deviendrait négative \(-2 pièce\(s\)\)/)).toBeVisible();
    expect(within(dialog).getByRole('button', { name: 'Vérifier' })).toBeDisabled();

    await user.clear(within(dialog).getByLabelText(/Quantité/));
    await user.type(within(dialog).getByLabelText(/Quantité/), '5');
    expect(within(dialog).getByText(/Actuel : 7 pièce\(s\) · Variation : −5 pièce\(s\) · Nouvelle quantité : 2 pièce\(s\)/)).toBeVisible();
    await user.click(within(dialog).getByRole('button', { name: 'Vérifier' }));
    await user.click(screen.getByRole('button', { name: 'Confirmer' }));
    expect(await screen.findByText('Sortie enregistrée. Nouvelle quantité : 2 pièce(s).')).toBeVisible();
    expect(last(backend.requests, 'POST', '/exits')?.body).toEqual({ quantity: '5', reason: 'Casse', reference: null });
  });

  it('refus du serveur (stock insuffisant) affiché, et même clé réutilisée après une coupure réseau', async () => {
    const backend = stockBackend({
      permissions: MANAGER,
      items: [item('p-1', 'Huile', { unit: 'LITRE', quantity: '12.5' })],
      failNextOperationWithNetworkError: true,
    });
    const user = userEvent.setup();
    renderAt('/stock');
    await user.click(await screen.findByRole('button', { name: 'Entrée · Huile' }));
    const dialog = screen.getByRole('dialog');
    await user.type(within(dialog).getByLabelText(/Quantité/), '0,75');
    await user.type(within(dialog).getByLabelText('Motif'), 'Réception');
    expect(within(dialog).getByText(/Nouvelle quantité : 13,25 L/)).toBeVisible(); // calcul exact, sans flottant
    await user.click(within(dialog).getByRole('button', { name: 'Vérifier' }));
    await user.click(screen.getByRole('button', { name: 'Confirmer' }));
    expect(await screen.findByText(/Connexion impossible/)).toBeVisible();

    await user.click(screen.getByRole('button', { name: 'Vérifier' }));
    await user.click(screen.getByRole('button', { name: 'Confirmer' }));
    expect(await screen.findByText('Entrée enregistrée. Nouvelle quantité : 13,25 L.')).toBeVisible();
    const posts = backend.requests.filter((r) => r.method === 'POST');
    expect(posts).toHaveLength(2);
    expect(posts[0]?.key).toBe(posts[1]?.key);
    expect(posts[1]?.body).toEqual({ quantity: '0.75', reason: 'Réception', reference: null });
  });

  it('ajustement à la baisse avec motif, puis seuil minimal faisant apparaître l’alerte', async () => {
    const backend = stockBackend({ permissions: OWNER, items: [item('p-1', 'Savon noir', { quantity: '25' })] });
    const user = userEvent.setup();
    renderAt('/stock');
    await user.click(await screen.findByRole('button', { name: 'Ajuster · Savon noir' }));
    let dialog = screen.getByRole('dialog');
    await user.type(within(dialog).getByLabelText(/Quantité/), '3');
    await user.type(within(dialog).getByLabelText('Motif'), 'Erreur de comptage');
    expect(within(dialog).getByText(/Nouvelle quantité : 22 pièce\(s\)/)).toBeVisible();
    await user.click(within(dialog).getByRole('button', { name: 'Vérifier' }));
    await user.click(screen.getByRole('button', { name: 'Confirmer' }));
    expect(await screen.findByText('Ajustement enregistré. Nouvelle quantité : 22 pièce(s).')).toBeVisible();
    expect(last(backend.requests, 'POST', '/adjustments')?.body).toEqual({
      direction: 'OUT',
      quantity: '3',
      reason: 'Erreur de comptage',
    });

    await user.click(await screen.findByRole('button', { name: 'Seuil · Savon noir' }, SLOW));
    dialog = screen.getByRole('dialog');
    await user.clear(within(dialog).getByLabelText(/Seuil minimal/));
    await user.type(within(dialog).getByLabelText(/Seuil minimal/), '25');
    await user.click(within(dialog).getByRole('button', { name: 'Vérifier' }));
    await user.click(screen.getByRole('button', { name: 'Confirmer' }));
    expect(await screen.findByText('Seuil minimal enregistré.')).toBeVisible();
    expect(await screen.findByText('Stock faible', { selector: 'span' }, SLOW)).toBeVisible();
  });
});

describe('historique et paramètre', () => {
  it('historique global en lecture seule, avec auteur et quantités avant/après', async () => {
    const backend = stockBackend({ permissions: EMPLOYEE, items: [item('p-1', 'Savon noir', { quantity: '7' })] });
    backend.movements.push({
      id: 'm-1',
      productId: 'p-1',
      productName: 'Savon noir',
      unit: 'PIECE',
      type: 'EXIT',
      quantity: '15',
      quantityBefore: '22',
      quantityAfter: '7',
      reason: 'Commande groupée',
      reference: 'CMD-42',
      author: { id: 'u-1', fullName: 'Administratrice Démo' },
      createdAt: '2026-11-19T11:00:00Z',
    });
    renderAt('/stock/history');
    expect(await screen.findByRole('heading', { name: 'Historique des mouvements', level: 1 })).toBeVisible();
    expect(await screen.findAllByText('Commande groupée')).not.toHaveLength(0);
    expect(screen.getAllByText('Administratrice Démo').length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: /Supprimer|Modifier/ })).toBeNull();
  });

  it('propriétaire : activation du stock négatif (dans Paramètres) après confirmation', async () => {
    const backend = stockBackend({ permissions: OWNER, items: [item('p-1', 'Savon noir')] });
    const user = userEvent.setup();
    renderAt('/settings');
    const toggle = await screen.findByRole('switch', { name: 'Autoriser le stock négatif' }, SLOW);
    expect(toggle).toHaveAttribute('aria-checked', 'false');
    await user.click(toggle);
    await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Confirmer' }));
    expect(await screen.findByText('Paramètre enregistré.')).toBeVisible();
    expect(last(backend.requests, 'PUT', '/business/settings')?.body).toEqual({ allowNegativeStock: true });
  });

  it('le réglage du stock négatif n’est plus sur la page Stock : lien vers les Paramètres', async () => {
    stockBackend({ permissions: OWNER, items: [item('p-1', 'Savon noir')] });
    renderAt('/stock');
    expect(await screen.findByRole('link', { name: 'Ouvrir les paramètres' }, SLOW)).toHaveAttribute('href', '/settings');
    expect(screen.queryByRole('switch')).toBeNull();
  });

  it('gestionnaire : paramètre visible dans Paramètres mais non modifiable', async () => {
    stockBackend({ permissions: MANAGER, items: [item('p-1', 'Savon noir')] });
    renderAt('/settings');
    expect(await screen.findByRole('heading', { name: 'Paramètres du stock' }, SLOW)).toBeVisible();
    expect(screen.getByText('Ce paramètre ne peut être modifié que par le rôle Propriétaire.')).toBeVisible();
    expect(screen.queryByRole('switch')).toBeNull();
  });
});

describe('coût unitaire des entrées (COST_READ)', () => {
  it('saisie facultative, précision de la devise, envoyée en chaîne et visible dans l’historique', async () => {
    const backend = stockBackend({ permissions: OWNER, items: [item('p-1', 'Savon noir', { quantity: '7' })] });
    const user = userEvent.setup();
    renderAt('/stock');
    await user.click(await screen.findByRole('button', { name: 'Entrée · Savon noir' }));
    const dialog = screen.getByRole('dialog');
    const cost = within(dialog).getByLabelText(/Coût unitaire d'achat \(XOF\)/);
    expect(within(dialog).getByText(/Il ne modifie pas le prix d'achat de référence du produit/)).toBeVisible();
    await user.type(within(dialog).getByLabelText(/^Quantité/), '10');
    await user.type(within(dialog).getByLabelText('Motif'), 'Réception');
    await user.type(cost, '650,5');
    await user.click(within(dialog).getByRole('button', { name: 'Vérifier' }));
    expect(within(dialog).getByText('En XOF, 0 décimale au maximum.')).toBeVisible();
    await user.clear(cost);
    await user.type(cost, '0');
    await user.click(within(dialog).getByRole('button', { name: 'Vérifier' }));
    expect(within(dialog).getByText(/Le coût unitaire doit être supérieur à zéro/)).toBeVisible();
    await user.clear(cost);
    await user.type(cost, '650');
    await user.click(within(dialog).getByRole('button', { name: 'Vérifier' }));
    expect(within(screen.getByRole('dialog')).getByText(/650\sF\s?CFA/)).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Confirmer' }));
    expect(await screen.findByText('Entrée enregistrée. Nouvelle quantité : 17 pièce(s).')).toBeVisible();
    expect(last(backend.requests, 'POST', '/entries')?.body).toEqual({
      quantity: '10',
      reason: 'Réception',
      reference: null,
      unitCost: '650',
    });

    // Entrée sans coût : toujours possible, aucun champ envoyé.
    await user.click(await screen.findByRole('button', { name: 'Entrée · Savon noir' }, SLOW));
    const second = screen.getByRole('dialog');
    await user.type(within(second).getByLabelText(/^Quantité/), '1');
    await user.type(within(second).getByLabelText('Motif'), 'Don');
    await user.click(within(second).getByRole('button', { name: 'Vérifier' }));
    await user.click(screen.getByRole('button', { name: 'Confirmer' }));
    expect(await screen.findByText('Entrée enregistrée. Nouvelle quantité : 18 pièce(s).')).toBeVisible();
    expect(last(backend.requests, 'POST', '/entries')?.body).toEqual({ quantity: '1', reason: 'Don', reference: null });

    // Historique : colonne « Coût unitaire », valeur pour l'entrée valorisée, tiret sinon.
    await user.click(screen.getByRole('link', { name: 'Historique des mouvements' }));
    expect(await screen.findByRole('columnheader', { name: 'Coût unitaire' }, SLOW)).toBeVisible();
    expect(screen.getAllByText(/650\sF\s?CFA/).length).toBeGreaterThan(0);
  });

  it('sortie et ajustement : aucun champ de coût', async () => {
    stockBackend({ permissions: OWNER, items: [item('p-1', 'Savon noir')] });
    const user = userEvent.setup();
    renderAt('/stock');
    await user.click(await screen.findByRole('button', { name: 'Sortie · Savon noir' }));
    expect(within(screen.getByRole('dialog')).queryByLabelText(/Coût unitaire/)).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Annuler' }));
    await user.click(screen.getByRole('button', { name: 'Ajuster · Savon noir' }));
    expect(within(screen.getByRole('dialog')).queryByLabelText(/Coût unitaire/)).toBeNull();
  });

  it('sans COST_READ : ni colonne ni valeur de coût dans l’historique', async () => {
    const backend = stockBackend({ permissions: EMPLOYEE, items: [item('p-1', 'Savon noir', { quantity: '17' })] });
    backend.movements.push({
      id: 'm-1',
      productId: 'p-1',
      productName: 'Savon noir',
      unit: 'PIECE',
      type: 'ENTRY',
      quantity: '10',
      quantityBefore: '7',
      quantityAfter: '17',
      reason: 'Réception',
      reference: null,
      author: { id: 'u-1', fullName: 'Administratrice Démo' },
      createdAt: '2026-11-19T11:00:00Z',
    });
    renderAt('/stock/history');
    expect(await screen.findAllByText('Réception')).not.toHaveLength(0);
    expect(screen.queryByRole('columnheader', { name: 'Coût unitaire' })).toBeNull();
    expect(screen.queryByText(/Coût unitaire/)).toBeNull();
    expect(screen.queryByText(/F\s?CFA/)).toBeNull();
  });
});
