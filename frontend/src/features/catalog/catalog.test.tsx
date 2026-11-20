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

import type { Category, Product } from './types';

// Parcours complets dans l'interface : plus lents quand toute la suite s'exécute en parallèle.
vi.setConfig({ testTimeout: 15_000 });

const OWNER_PERMISSIONS = ['BUSINESS_READ', 'CATALOG_READ', 'CATALOG_WRITE', 'COST_READ'];
const EMPLOYEE_PERMISSIONS = ['BUSINESS_READ', 'CATALOG_READ'];

function meWith(permissions: readonly string[]): MeResponse {
  return { ...meFixture, permissions };
}

const NOW = '2026-11-19T10:00:00Z';
/** Attente allongée : recherche différée (300 ms) + rendu, plus lents quand toute la suite tourne en parallèle. */
const SLOW = { timeout: 5_000 };

function category(id: string, name: string, extra: Partial<Category> = {}): Category {
  return {
    id,
    name,
    description: null,
    status: 'ACTIVE',
    activeProductCount: 0,
    createdAt: NOW,
    updatedAt: NOW,
    version: 0,
    ...extra,
  };
}

function product(id: string, name: string, extra: Partial<Product> = {}): Product {
  return {
    id,
    name,
    description: null,
    sku: null,
    barcode: null,
    unit: 'PIECE',
    category: null,
    purchasePrice: '700',
    salePrice: '1000',
    currency: 'XOF',
    status: 'ACTIVE',
    createdAt: NOW,
    updatedAt: NOW,
    version: 0,
    ...extra,
  };
}

/**
 * Backend du catalogue SIMULÉ pour les tests (en mémoire, propre à chaque test) : reproduit le
 * contrat (pagination, filtres, prix d'achat retiré sans COST_READ, erreurs de champ).
 */
function catalogBackend(options: {
  readonly permissions?: readonly string[];
  readonly products?: Product[];
  readonly categories?: Category[];
}) {
  const permissions = options.permissions ?? OWNER_PERMISSIONS;
  const products = [...(options.products ?? [])];
  const categories = [...(options.categories ?? [])];
  const requests: { method: string; path: string; params: Record<string, string>; body: unknown }[] = [];
  const canSeeCosts = permissions.includes('COST_READ');
  const visible = (item: Product): Product => {
    if (canSeeCosts) return item;
    // Comme le serveur : la propriété est ABSENTE (pas seulement vide).
    return Object.fromEntries(
      Object.entries(item).filter(([key]) => key !== 'purchasePrice'),
    ) as unknown as Product;
  };
  const record = async (request: Request) => {
    const url = new URL(request.url);
    const body: unknown =
      request.method === 'GET' || request.method === 'DELETE' ? null : await request.json();
    requests.push({
      method: request.method,
      path: url.pathname,
      params: Object.fromEntries(url.searchParams.entries()),
      body,
    });
    return { url, body: body as Record<string, unknown> | null };
  };
  const page = <T,>(items: T[], url: URL) => {
    const number = Number(url.searchParams.get('page') ?? '0');
    const size = Number(url.searchParams.get('size') ?? '20');
    return {
      content: items.slice(number * size, number * size + size),
      page: { number, size, totalElements: items.length, totalPages: Math.ceil(items.length / size) },
    };
  };

  server.use(
    http.get('/api/v1/me', () => HttpResponse.json(meWith(permissions))),
    http.get('/api/v1/categories', async ({ request }) => {
      const { url } = await record(request);
      const status = url.searchParams.get('status') ?? 'ACTIVE';
      return HttpResponse.json(page(categories.filter((c) => status === 'ALL' || c.status === status), url));
    }),
    http.post('/api/v1/categories', async ({ request }) => {
      const { body } = await record(request);
      const name = String(body?.name);
      if (categories.some((c) => c.status === 'ACTIVE' && c.name.toLowerCase() === name.toLowerCase())) {
        return HttpResponse.json(
          { status: 400, code: 'VALIDATION_ERROR', errors: [{ field: 'name', code: 'ALREADY_EXISTS' }] },
          { status: 400 },
        );
      }
      const created = category(`c-${categories.length + 1}`, name, { description: (body?.description as string) ?? null });
      categories.push(created);
      return HttpResponse.json(created, { status: 201 });
    }),
    http.put('/api/v1/categories/:id', async ({ request, params }) => {
      const { body } = await record(request);
      const index = categories.findIndex((c) => c.id === params.id);
      const current = categories[index] as Category;
      const updated = { ...current, name: String(body?.name), version: current.version + 1 };
      categories[index] = updated;
      return HttpResponse.json(updated);
    }),
    http.delete('/api/v1/categories/:id', async ({ request, params }) => {
      await record(request);
      const index = categories.findIndex((c) => c.id === params.id);
      categories[index] = { ...(categories[index] as Category), status: 'ARCHIVED' };
      return new HttpResponse(null, { status: 204 });
    }),
    http.get('/api/v1/products', async ({ request }) => {
      const { url } = await record(request);
      const q = (url.searchParams.get('q') ?? '').toLowerCase();
      const status = url.searchParams.get('status') ?? 'ACTIVE';
      const categoryId = url.searchParams.get('categoryId');
      const items = products
        .filter((p) => status === 'ALL' || p.status === status)
        .filter((p) => !categoryId || p.category?.id === categoryId)
        .filter((p) => q === '' || p.name.toLowerCase().includes(q) || (p.sku ?? '').toLowerCase().includes(q))
        .map(visible);
      return HttpResponse.json(page(items, url));
    }),
    http.get('/api/v1/products/:id', async ({ request, params }) => {
      await record(request);
      const found = products.find((p) => p.id === params.id);
      return found
        ? HttpResponse.json(visible(found))
        : HttpResponse.json({ status: 404, code: 'NOT_FOUND' }, { status: 404 });
    }),
    http.post('/api/v1/products', async ({ request }) => {
      const { body } = await record(request);
      if (body?.sku && products.some((p) => p.status === 'ACTIVE' && p.sku === body.sku)) {
        return HttpResponse.json(
          { status: 400, code: 'VALIDATION_ERROR', errors: [{ field: 'sku', code: 'ALREADY_EXISTS' }] },
          { status: 400 },
        );
      }
      const created = product(`p-${products.length + 1}`, String(body?.name), {
        salePrice: String(body?.salePrice),
        sku: (body?.sku as string | null) ?? null,
        ...(typeof body?.purchasePrice === 'string' ? { purchasePrice: body.purchasePrice } : {}),
      });
      products.push(created);
      return HttpResponse.json(visible(created), { status: 201 });
    }),
    http.put('/api/v1/products/:id', async ({ request, params }) => {
      const { body } = await record(request);
      const index = products.findIndex((p) => p.id === params.id);
      const current = products[index] as Product;
      const updated = { ...current, name: String(body?.name), salePrice: String(body?.salePrice), version: current.version + 1 };
      products[index] = updated;
      return HttpResponse.json(visible(updated));
    }),
    http.delete('/api/v1/products/:id', async ({ request, params }) => {
      await record(request);
      const index = products.findIndex((p) => p.id === params.id);
      products[index] = { ...(products[index] as Product), status: 'ARCHIVED' };
      return new HttpResponse(null, { status: 204 });
    }),
  );
  return { requests, products, categories };
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

const lastRequest = (requests: ReturnType<typeof catalogBackend>['requests'], method: string, path: string) =>
  [...requests].reverse().find((r) => r.method === method && r.path === path);

describe('navigation du catalogue', () => {
  it('affiche Produits et Catégories avec CATALOG_READ', async () => {
    catalogBackend({});
    renderAt('/');
    const nav = await screen.findByRole('navigation', { name: 'Navigation principale' });
    expect(within(nav).getByRole('link', { name: 'Produits' })).toHaveAttribute('href', '/products');
    expect(within(nav).getByRole('link', { name: 'Catégories' })).toHaveAttribute('href', '/categories');
  });

  it('masque le catalogue sans permission de lecture', async () => {
    catalogBackend({ permissions: ['BUSINESS_READ'] });
    renderAt('/');
    const nav = await screen.findByRole('navigation', { name: 'Navigation principale' });
    expect(within(nav).queryByRole('link', { name: 'Produits' })).toBeNull();
  });
});

describe('page Produits', () => {
  it('affiche le catalogue avec les montants formatés depuis les chaînes du serveur', async () => {
    catalogBackend({
      products: [product('p-1', 'Savon noir', { salePrice: '1500', purchasePrice: '900', sku: 'SAV-1' })],
    });
    renderAt('/products');
    expect(await screen.findByRole('link', { name: 'Savon noir' })).toHaveAttribute('href', '/products/p-1');
    expect(screen.getByText(/1\s?500/)).toBeVisible();
    expect(screen.getByText(/Prix d'achat : 900/)).toBeVisible();
  });

  it('état de chargement puis état vide avec invitation à créer', async () => {
    catalogBackend({});
    renderAt('/products');
    expect(await screen.findByRole('status')).toHaveTextContent('Chargement');
    expect(await screen.findByText('Aucun produit pour le moment')).toBeVisible();
    expect(screen.getAllByRole('link', { name: 'Ajouter un produit' }).length).toBeGreaterThan(0);
  });

  it('erreur du serveur : message traduit et nouvel essai', async () => {
    catalogBackend({});
    server.use(
      http.get('/api/v1/products', () =>
        HttpResponse.json({ status: 403, code: 'FORBIDDEN' }, { status: 403 }),
      ),
    );
    renderAt('/products');
    expect(await screen.findByText("Les données n'ont pas pu être chargées.")).toBeVisible();
    expect(screen.getByRole('button', { name: 'Réessayer' })).toBeVisible();
  });

  it('recherche, filtres, tri et pagination sont envoyés au serveur', async () => {
    const backend = catalogBackend({
      categories: [category('c-1', 'Boissons')],
      products: Array.from({ length: 25 }, (_, i) => product(`p-${i}`, `Article ${String(i).padStart(2, '0')}`)),
    });
    const user = userEvent.setup();
    renderAt('/products');
    await screen.findByRole('link', { name: 'Article 00' }, SLOW);

    await user.type(screen.getByRole('searchbox'), 'bis');
    await waitFor(() => expect(lastRequest(backend.requests, 'GET', '/api/v1/products')?.params.q).toBe('bis'), SLOW);

    await user.clear(screen.getByRole('searchbox'));
    // Résultat sans recherche déjà en cache (encore frais) : de nouveau affiché.
    expect(await screen.findByRole('link', { name: 'Article 00' }, SLOW)).toBeVisible();
    await user.selectOptions(screen.getByLabelText('Catégorie'), 'c-1');
    await waitFor(() => expect(lastRequest(backend.requests, 'GET', '/api/v1/products')?.params.categoryId).toBe('c-1'), SLOW);
    await user.selectOptions(screen.getByLabelText('Catégorie'), '');
    await user.selectOptions(screen.getByLabelText('Statut'), 'ARCHIVED');
    await waitFor(() => expect(lastRequest(backend.requests, 'GET', '/api/v1/products')?.params.status).toBe('ARCHIVED'), SLOW);
    await user.selectOptions(screen.getByLabelText('Statut'), 'ACTIVE');
    await user.selectOptions(screen.getByLabelText('Trier par'), 'salePrice,desc');
    await waitFor(() => expect(lastRequest(backend.requests, 'GET', '/api/v1/products')?.params.sort).toBe('salePrice,desc'), SLOW);

    expect(await screen.findByText(/Page 1 sur 2/, {}, SLOW)).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Page suivante' }));
    await waitFor(() => expect(lastRequest(backend.requests, 'GET', '/api/v1/products')?.params.page).toBe('1'), SLOW);
    expect(lastRequest(backend.requests, 'GET', '/api/v1/products')?.params.size).toBe('20');
  });

  it('archivage après confirmation seulement', async () => {
    const backend = catalogBackend({ products: [product('p-1', 'Panier tressé')] });
    const user = userEvent.setup();
    renderAt('/products');
    await screen.findByRole('link', { name: 'Panier tressé' });

    await user.click(screen.getByRole('button', { name: 'Archiver « Panier tressé »' }));
    const dialog = screen.getByRole('alertdialog', { name: 'Archiver ce produit ?' });
    await user.click(within(dialog).getByRole('button', { name: 'Annuler' }));
    expect(backend.requests.some((r) => r.method === 'DELETE')).toBe(false);

    await user.click(screen.getByRole('button', { name: 'Archiver « Panier tressé »' }));
    await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Archiver' }));
    expect(await screen.findByText('Le produit « Panier tressé » a été archivé.')).toBeVisible();
    expect(lastRequest(backend.requests, 'DELETE', '/api/v1/products/p-1')).toBeDefined();
  });
});

describe('formulaire produit', () => {
  it('création : validation locale, envoi des montants en chaînes, notification et fiche', async () => {
    const backend = catalogBackend({ categories: [category('c-1', 'Épicerie')] });
    const user = userEvent.setup();
    const router = renderAt('/products/new');
    await screen.findByRole('heading', { name: 'Nouveau produit' });

    await user.click(screen.getByRole('button', { name: 'Enregistrer' }));
    expect(screen.getAllByText('Ce champ est obligatoire.')).toHaveLength(2);
    expect(backend.requests.some((r) => r.method === 'POST')).toBe(false);

    await user.type(screen.getByLabelText('Nom du produit'), 'Riz parfumé 5 kg');
    await user.type(screen.getByLabelText('Prix de vente'), '3500,50');
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }));
    expect(screen.getByText('Trop de décimales pour ce montant.')).toBeVisible(); // XOF : aucune décimale

    await user.clear(screen.getByLabelText('Prix de vente'));
    await user.type(screen.getByLabelText('Prix de vente'), '3 500');
    await user.type(screen.getByLabelText(/Prix d'achat de référence/), '2750,5');
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }));
    // Prix d'achat : même règle que le prix de vente (XOF : aucune décimale).
    expect(screen.getByLabelText(/Prix d'achat de référence/)).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByText('Trop de décimales pour ce montant.')).toBeVisible();
    expect(backend.requests.some((r) => r.method === 'POST')).toBe(false);
    await user.clear(screen.getByLabelText(/Prix d'achat de référence/));
    await user.type(screen.getByLabelText(/Prix d'achat de référence/), '2 750');
    await user.selectOptions(screen.getByLabelText(/Catégorie/), 'c-1');
    await user.selectOptions(screen.getByLabelText('Unité de mesure'), 'PACK');
    await user.type(screen.getByLabelText(/Référence interne/), 'RIZ-5');
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }));

    expect(await screen.findByText('Le produit « Riz parfumé 5 kg » a été ajouté.')).toBeVisible();
    expect(lastRequest(backend.requests, 'POST', '/api/v1/products')?.body).toEqual({
      name: 'Riz parfumé 5 kg',
      description: null,
      sku: 'RIZ-5',
      barcode: null,
      categoryId: 'c-1',
      unit: 'PACK',
      purchasePrice: '2750',
      salePrice: '3500',
    });
    await waitFor(() => expect(router.state.location.pathname).toBe('/products/p-1'));
  });

  it('erreur de champ renvoyée par le serveur (SKU déjà utilisé) affichée sous le champ', async () => {
    catalogBackend({ products: [product('p-1', 'Existant', { sku: 'DUP-1' })] });
    const user = userEvent.setup();
    renderAt('/products/new');
    await screen.findByRole('heading', { name: 'Nouveau produit' });
    await user.type(screen.getByLabelText('Nom du produit'), 'Doublon');
    await user.type(screen.getByLabelText('Prix de vente'), '100');
    await user.type(screen.getByLabelText(/Référence interne/), 'DUP-1');
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }));

    expect(await screen.findByText('Cette valeur est déjà utilisée.')).toBeVisible();
    expect(screen.getByLabelText(/Référence interne/)).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByText("Le produit n'a pas pu être enregistré.")).toBeVisible();
  });

  it('modification : valeurs actuelles, version envoyée', async () => {
    const backend = catalogBackend({ products: [product('p-1', 'Jus de bissap', { version: 3, salePrice: '1000' })] });
    const user = userEvent.setup();
    renderAt('/products/p-1/edit');
    const name = await screen.findByLabelText('Nom du produit');
    expect(name).toHaveValue('Jus de bissap');
    await user.clear(screen.getByLabelText('Prix de vente'));
    await user.type(screen.getByLabelText('Prix de vente'), '1200');
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }));

    expect(await screen.findByText('Le produit « Jus de bissap » a été modifié.')).toBeVisible();
    expect(lastRequest(backend.requests, 'PUT', '/api/v1/products/p-1')?.body).toMatchObject({
      salePrice: '1200',
      version: 3,
    });
  });
});

describe('confidentialité des coûts (rôle sans COST_READ)', () => {
  it('ni prix d’achat ni actions d’écriture ; la création est refusée à l’affichage', async () => {
    catalogBackend({ permissions: EMPLOYEE_PERMISSIONS, products: [product('p-1', 'Crème', { purchasePrice: '1250' })] });
    renderAt('/products');
    await screen.findByRole('link', { name: 'Crème' });
    expect(screen.queryByText(/Prix d'achat/)).toBeNull();
    expect(screen.queryByText(/1\s?250/)).toBeNull();
    expect(screen.queryByRole('link', { name: 'Ajouter un produit' })).toBeNull();
    expect(screen.queryByRole('button', { name: /Archiver/ })).toBeNull();
  });

  it('fiche produit sans prix d’achat ; formulaire inaccessible', async () => {
    catalogBackend({ permissions: EMPLOYEE_PERMISSIONS, products: [product('p-1', 'Crème', { purchasePrice: '1250' })] });
    renderAt('/products/p-1');
    expect(await screen.findByRole('heading', { name: 'Crème' })).toBeVisible();
    expect(screen.queryByText(/Prix d'achat/)).toBeNull();
    expect(screen.queryByRole('link', { name: 'Modifier' })).toBeNull();
  });

  it('accès direct au formulaire de création : message de droits insuffisants', async () => {
    catalogBackend({ permissions: EMPLOYEE_PERMISSIONS });
    renderAt('/products/new');
    expect(await screen.findByRole('alert')).toHaveTextContent("Vous n'avez pas les droits nécessaires");
    expect(screen.queryByLabelText('Nom du produit')).toBeNull();
  });
});

describe('page Catégories', () => {
  it('état vide, création, doublon refusé, modification et archivage confirmé', async () => {
    const backend = catalogBackend({});
    const user = userEvent.setup();
    renderAt('/categories');
    expect(await screen.findByText('Aucune catégorie pour le moment')).toBeVisible();

    await user.click(screen.getByRole('button', { name: 'Ajouter une catégorie' }));
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }));
    expect(screen.getByText('Ce champ est obligatoire.')).toBeVisible();
    await user.type(screen.getByLabelText('Nom de la catégorie'), 'Artisanat');
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }));
    expect(await screen.findByText('La catégorie « Artisanat » a été créée.')).toBeVisible();
    expect(await screen.findByRole('heading', { name: 'Artisanat' })).toBeVisible();
    expect(screen.getByText('0 produit actif')).toBeVisible();

    await user.click(screen.getByRole('button', { name: 'Ajouter une catégorie' }));
    await user.type(screen.getByLabelText('Nom de la catégorie'), 'artisanat');
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }));
    expect(await screen.findByText('Cette valeur est déjà utilisée.')).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Annuler' }));

    await user.click(screen.getByRole('button', { name: 'Modifier' }));
    const nameInput = screen.getByLabelText('Nom de la catégorie');
    await user.clear(nameInput);
    await user.type(nameInput, 'Artisanat local');
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }));
    expect(await screen.findByText('La catégorie « Artisanat local » a été modifiée.')).toBeVisible();
    expect(lastRequest(backend.requests, 'PUT', '/api/v1/categories/c-1')?.body).toEqual({
      name: 'Artisanat local',
      description: null,
      version: 0,
    });

    await user.click(screen.getByRole('button', { name: 'Archiver' }));
    await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Archiver' }));
    expect(await screen.findByText('La catégorie « Artisanat local » a été archivée.')).toBeVisible();
    expect(lastRequest(backend.requests, 'DELETE', '/api/v1/categories/c-1')).toBeDefined();
  });

  it('affiche le nombre réel de produits fourni par le serveur et filtre par statut', async () => {
    const backend = catalogBackend({
      categories: [
        category('c-1', 'Boissons', { activeProductCount: 3 }),
        category('c-2', 'Ancienne', { status: 'ARCHIVED' }),
      ],
    });
    const user = userEvent.setup();
    renderAt('/categories');
    expect(await screen.findByText('3 produits actifs')).toBeVisible();
    expect(screen.queryByText('Ancienne')).toBeNull();
    await user.selectOptions(screen.getByLabelText('Statut'), 'ARCHIVED');
    expect(await screen.findByRole('heading', { name: 'Ancienne' })).toBeVisible();
    expect(lastRequest(backend.requests, 'GET', '/api/v1/categories')?.params.status).toBe('ARCHIVED');
  });
});
