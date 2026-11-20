import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { delay, http, HttpResponse } from 'msw';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it, vi } from 'vitest';

import { AppProviders } from '@/app/AppProviders';
import { routes } from '@/app/routes';
import type { MeResponse } from '@/features/session/types';
import { meFixture } from '@/mocks/fixtures/me';
import { createQueryClient } from '@/shared/api/queryClient';
import { server } from '@/test/server';

import type { Customer } from './types';

vi.setConfig({ testTimeout: 15_000 });
const SLOW = { timeout: 5_000 };

const OWNER = ['CUSTOMER_READ', 'CUSTOMER_WRITE', 'CUSTOMER_ARCHIVE', 'CUSTOMER_BALANCE_READ'];
const EMPLOYEE = ['CUSTOMER_READ', 'CUSTOMER_WRITE'];
const READ_ONLY = ['CUSTOMER_READ'];

function customer(id: string, name: string | null, extra: Partial<Customer> = {}): Customer {
  return {
    id,
    name,
    phone: null,
    email: null,
    address: null,
    notes: null,
    status: 'ACTIVE',
    archivedAt: null,
    createdAt: '2026-11-19T10:00:00Z',
    updatedAt: '2026-11-19T10:00:00Z',
    version: 0,
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

/** Backend des clients SIMULÉ (tests uniquement), avec les mêmes règles que le serveur. */
function customersBackend(options: { permissions: readonly string[]; customers?: Customer[]; failList?: boolean }) {
  const store = [...(options.customers ?? [])];
  const requests: Recorded[] = [];
  let failList = options.failList ?? false;
  const me: MeResponse = { ...meFixture, permissions: options.permissions };
  const record = async (request: Request) => {
    const url = new URL(request.url);
    const body: unknown = request.method === 'GET' || request.method === 'DELETE' ? null : await request.json();
    requests.push({
      method: request.method,
      path: url.pathname,
      params: Object.fromEntries(url.searchParams.entries()),
      body,
      key: request.headers.get('Idempotency-Key'),
    });
    return { url, body: body as Record<string, string | number | null> | null };
  };
  server.use(
    http.get('/api/v1/me', () => HttpResponse.json(me)),
    http.get('/api/v1/sales', () =>
      HttpResponse.json({ content: [], page: { number: 0, size: 20, totalElements: 0, totalPages: 0 } }),
    ),
    http.get('/api/v1/customers', async ({ request }) => {
      const { url } = await record(request);
      if (failList) {
        failList = false;
        // Erreur non relancée automatiquement (les 5xx le sont par le client de requêtes).
        return HttpResponse.json({ status: 403, code: 'FORBIDDEN' }, { status: 403 });
      }
      await delay(50);
      const q = (url.searchParams.get('q') ?? '').toLowerCase();
      const status = url.searchParams.get('status') ?? 'ACTIVE';
      const filtered = store.filter(
        (c) =>
          (status === 'ALL' || c.status === status) &&
          (q === '' || (c.name ?? '').toLowerCase().includes(q) || (c.phone ?? '').includes(q)),
      );
      const page = Number(url.searchParams.get('page') ?? '0');
      const size = Number(url.searchParams.get('size') ?? '20');
      return HttpResponse.json({
        content: filtered.slice(page * size, page * size + size),
        page: { number: page, size, totalElements: filtered.length, totalPages: Math.ceil(filtered.length / size) },
      });
    }),
    http.get('/api/v1/customers/:id', async ({ request, params }) => {
      await record(request);
      const found = store.find((c) => c.id === params.id);
      return found ? HttpResponse.json(found) : HttpResponse.json({ status: 404, code: 'NOT_FOUND' }, { status: 404 });
    }),
    http.post('/api/v1/customers', async ({ request }) => {
      const { body } = await record(request);
      const created = customer(`c-${store.length + 1}`, (body?.name as string | null) ?? null, {
        phone: (body?.phone as string | null) ?? null,
        email: (body?.email as string | null) ?? null,
      });
      store.push(created);
      return HttpResponse.json(created, { status: 201 });
    }),
    http.put('/api/v1/customers/:id', async ({ request, params }) => {
      const { body } = await record(request);
      const index = store.findIndex((c) => c.id === params.id);
      const current = store[index] as Customer;
      if (body?.version !== current.version) {
        return HttpResponse.json({ status: 409, code: 'OPTIMISTIC_LOCK' }, { status: 409 });
      }
      store[index] = {
        ...current,
        name: (body.name as string | null) ?? null,
        phone: (body.phone as string | null) ?? null,
        version: current.version + 1,
      };
      return HttpResponse.json(store[index]);
    }),
    http.delete('/api/v1/customers/:id', async ({ request, params }) => {
      await record(request);
      const index = store.findIndex((c) => c.id === params.id);
      store[index] = { ...(store[index] as Customer), status: 'ARCHIVED', archivedAt: '2026-11-19T12:00:00Z' };
      return new HttpResponse(null, { status: 204 });
    }),
  );
  return { requests, store };
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

const last = (requests: Recorded[], method: string, path: string) =>
  [...requests].reverse().find((r) => r.method === method && r.path === path);

describe('liste des clients', () => {
  it('navigation, chargement, liste, coordonnées et client identifié par son seul téléphone', async () => {
    customersBackend({
      permissions: OWNER,
      customers: [
        customer('c-1', 'Aïssatou Ndiaye', { phone: '+221 77 123 45 67', email: 'aissatou@exemple.sn' }),
        customer('c-2', null, { phone: '76 222 33 44' }),
      ],
    });
    renderAt('/clients');
    expect(await screen.findByText('Chargement…', {}, SLOW)).toBeVisible();
    expect(await screen.findByRole('link', { name: /Aïssatou Ndiaye/ }, SLOW)).toHaveAttribute('href', '/clients/c-1');
    const nav = screen.getByRole('navigation', { name: 'Navigation principale' });
    expect(within(nav).getByRole('link', { name: 'Clients' })).toHaveAttribute('href', '/clients');
    expect(screen.getByText('aissatou@exemple.sn')).toBeVisible();
    expect(screen.getByRole('link', { name: /76 222 33 44/ })).toHaveAttribute('href', '/clients/c-2');
    expect(screen.getByRole('link', { name: 'Nouveau client' })).toHaveAttribute('href', '/clients/new');
  });

  it('recherche différée, filtre de statut et pagination envoyés au serveur', async () => {
    const backend = customersBackend({
      permissions: OWNER,
      customers: [
        ...Array.from({ length: 25 }, (_, i) => customer(`c-${i}`, `Cliente ${String(i).padStart(2, '0')}`)),
        customer('c-arch', 'Ancienne cliente', { status: 'ARCHIVED', archivedAt: '2026-11-18T10:00:00Z' }),
      ],
    });
    const user = userEvent.setup();
    renderAt('/clients');
    await screen.findByRole('link', { name: /Cliente 00/ }, SLOW);
    await user.click(screen.getByRole('button', { name: 'Page suivante' }));
    await waitFor(() => expect(last(backend.requests, 'GET', '/api/v1/customers')?.params.page).toBe('1'), SLOW);
    await user.type(screen.getByRole('searchbox'), 'Cliente 2');
    await waitFor(() => expect(last(backend.requests, 'GET', '/api/v1/customers')?.params.q).toBe('Cliente 2'), SLOW);
    expect(last(backend.requests, 'GET', '/api/v1/customers')?.params.page).toBe('0');
    // Filtre changé PENDANT le délai de la recherche : il ne doit pas être écrasé.
    await user.clear(screen.getByRole('searchbox'));
    await user.selectOptions(screen.getByLabelText('Statut'), 'ARCHIVED');
    expect(await screen.findByText('Archivé', {}, SLOW)).toBeVisible();
    expect(screen.getByRole('link', { name: /Ancienne cliente/ })).toBeVisible();
    expect(screen.getByLabelText('Statut')).toHaveValue('ARCHIVED');
    await waitFor(() => expect(last(backend.requests, 'GET', '/api/v1/customers')?.params).toMatchObject({ status: 'ARCHIVED' }), SLOW);
    expect(last(backend.requests, 'GET', '/api/v1/customers')?.params.q).toBeUndefined();
  });

  it('états vides : aucun client, puis aucun résultat avec effacement de la recherche', async () => {
    customersBackend({ permissions: OWNER });
    const user = userEvent.setup();
    renderAt('/clients');
    expect(await screen.findByText(/Aucun client pour le moment/, {}, SLOW)).toBeVisible();
    await user.type(screen.getByRole('searchbox'), 'Introuvable');
    expect(await screen.findByText('Aucun client ne correspond à votre recherche.', {}, SLOW)).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Effacer la recherche' }));
    expect(await screen.findByText(/Aucun client pour le moment/, {}, SLOW)).toBeVisible();
    expect(screen.getByRole('searchbox')).toHaveValue('');
  });

  it('erreur de chargement affichée avec possibilité de réessayer', async () => {
    customersBackend({ permissions: OWNER, customers: [customer('c-1', 'Awa Sow')], failList: true });
    const user = userEvent.setup();
    renderAt('/clients');
    expect(await screen.findByText("Les données n'ont pas pu être chargées.", {}, SLOW)).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Réessayer' }));
    expect(await screen.findByRole('link', { name: /Awa Sow/ }, SLOW)).toBeVisible();
  });

  it('lecture seule : aucun bouton de création', async () => {
    customersBackend({ permissions: READ_ONLY, customers: [customer('c-1', 'Awa Sow')] });
    renderAt('/clients');
    await screen.findByRole('link', { name: /Awa Sow/ }, SLOW);
    expect(screen.queryByRole('link', { name: 'Nouveau client' })).toBeNull();
  });
});

describe('création et modification', () => {
  it('validation (identité, téléphone, e-mail) puis création avec clé d’idempotence', async () => {
    const backend = customersBackend({ permissions: OWNER });
    const user = userEvent.setup();
    const router = renderAt('/clients/new');
    await screen.findByRole('heading', { name: 'Nouveau client' }, SLOW);

    await user.click(screen.getByRole('button', { name: 'Enregistrer' }));
    expect(screen.getByText('Indiquez un nom ou un numéro de téléphone.')).toBeVisible();
    expect(screen.getByLabelText('Nom complet ou raison sociale')).toHaveFocus();

    await user.type(screen.getByLabelText('Téléphone'), '77 12');
    await user.type(screen.getByLabelText(/Adresse e-mail/), 'pas-un-email');
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }));
    expect(screen.getByText(/Numéro non valide/)).toBeVisible();
    expect(screen.getByText(/Adresse e-mail non valide/)).toBeVisible();
    expect(backend.requests.some((r) => r.method === 'POST')).toBe(false);

    // Téléphone seul (format international) : suffisant.
    await user.clear(screen.getByLabelText('Téléphone'));
    await user.type(screen.getByLabelText('Téléphone'), '+221 77 123 45 67');
    await user.clear(screen.getByLabelText(/Adresse e-mail/));
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }));
    expect(await screen.findByText('Client « +221 77 123 45 67 » ajouté.', {}, SLOW)).toBeVisible();
    await waitFor(() => expect(router.state.location.pathname).toBe('/clients/c-1'));
    const post = last(backend.requests, 'POST', '/api/v1/customers');
    expect(post?.body).toEqual({ name: null, phone: '+221 77 123 45 67', email: null, address: null, notes: null });
    expect(post?.key).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  });

  it('modification avec la version, puis fiche à jour', async () => {
    const backend = customersBackend({
      permissions: OWNER,
      customers: [customer('c-1', 'Awa Sow', { phone: '77 000 00 00', version: 3 })],
    });
    const user = userEvent.setup();
    renderAt('/clients/c-1/edit');
    const name = await screen.findByLabelText('Nom complet ou raison sociale', {}, SLOW);
    expect(name).toHaveValue('Awa Sow');
    await user.clear(name);
    await user.type(name, 'Awa Sow Diallo');
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }));
    expect(await screen.findByText('Client « Awa Sow Diallo » modifié.', {}, SLOW)).toBeVisible();
    expect(await screen.findByRole('heading', { name: 'Awa Sow Diallo', level: 1 }, SLOW)).toBeVisible();
    expect(last(backend.requests, 'PUT', '/api/v1/customers/c-1')?.body).toMatchObject({
      name: 'Awa Sow Diallo',
      phone: '77 000 00 00',
      version: 3,
    });
  });
});

describe('fiche client', () => {
  it('consultation, liens d’appel et d’e-mail, historique commercial réel (vide)', async () => {
    customersBackend({
      permissions: OWNER,
      customers: [
        customer('c-1', 'Mariama Ba', {
          phone: '+221 77 123 45 67',
          email: 'mariama@exemple.sn',
          address: 'Médina, rue 11',
          notes: 'Préfère être appelée le matin',
        }),
      ],
    });
    renderAt('/clients/c-1');
    expect(await screen.findByRole('heading', { name: 'Mariama Ba', level: 1 }, SLOW)).toBeVisible();
    expect(screen.getByRole('link', { name: '+221 77 123 45 67' })).toHaveAttribute('href', 'tel:+221771234567');
    expect(screen.getByRole('link', { name: 'mariama@exemple.sn' })).toHaveAttribute('href', 'mailto:mariama@exemple.sn');
    expect(screen.getByText('Préfère être appelée le matin')).toBeVisible();
    expect(await screen.findByText('Aucune vente confirmée pour ce client.', {}, SLOW)).toBeVisible();
  });

  it('archivage après confirmation (propriétaire)', async () => {
    const backend = customersBackend({ permissions: OWNER, customers: [customer('c-1', 'Awa Sow')] });
    const user = userEvent.setup();
    const router = renderAt('/clients/c-1');
    await user.click(await screen.findByRole('button', { name: 'Archiver' }, SLOW));
    const dialog = screen.getByRole('alertdialog');
    expect(within(dialog).getByText(/« Awa Sow » ne sera plus proposé/)).toBeVisible();
    expect(backend.requests.some((r) => r.method === 'DELETE')).toBe(false);
    await user.click(within(dialog).getByRole('button', { name: 'Archiver' }));
    expect(await screen.findByText('Client « Awa Sow » archivé.', {}, SLOW)).toBeVisible();
    await waitFor(() => expect(router.state.location.pathname).toBe('/clients'));
    expect(last(backend.requests, 'DELETE', '/api/v1/customers/c-1')).toBeDefined();
  });

  it('employée : modification possible, archivage non proposé ; client archivé : aucune action', async () => {
    customersBackend({
      permissions: EMPLOYEE,
      customers: [
        customer('c-1', 'Awa Sow'),
        customer('c-2', 'Ancienne cliente', { status: 'ARCHIVED', archivedAt: '2026-11-18T10:00:00Z' }),
      ],
    });
    renderAt('/clients/c-1');
    expect(await screen.findByRole('link', { name: 'Modifier' }, SLOW)).toHaveAttribute('href', '/clients/c-1/edit');
    expect(screen.queryByRole('button', { name: 'Archiver' })).toBeNull();
  });

  it('client archivé : notice, aucune action', async () => {
    customersBackend({
      permissions: OWNER,
      customers: [customer('c-2', 'Ancienne cliente', { status: 'ARCHIVED', archivedAt: '2026-11-18T10:00:00Z' })],
    });
    renderAt('/clients/c-2');
    expect(await screen.findByText(/Ce client est archivé/, {}, SLOW)).toBeVisible();
    expect(screen.queryByRole('link', { name: 'Modifier' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Archiver' })).toBeNull();
  });

  it('client introuvable (ou d’une autre entreprise) : message clair', async () => {
    customersBackend({ permissions: OWNER });
    renderAt('/clients/inconnu');
    expect(await screen.findByText('Ce client est introuvable.', {}, SLOW)).toBeVisible();
  });
});
