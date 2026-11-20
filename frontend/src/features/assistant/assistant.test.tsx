import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { AppProviders } from '@/app/AppProviders';
import { routes } from '@/app/routes';
import type { MeResponse } from '@/features/session/types';
import { meFixture } from '@/mocks/fixtures/me';
import { createQueryClient } from '@/shared/api/queryClient';
import { server } from '@/test/server';

vi.setConfig({ testTimeout: 20_000 });
const SLOW = { timeout: 6_000 };
const OWNER = ['AI_USE', 'AI_CONSENT_MANAGE', 'SALE_CREATE', 'SALE_READ_ALL'];

type Status = { globallyEnabled: boolean; configured: boolean; businessConsent: boolean; canManageConsent: boolean };

function backend(status: Status) {
  const requests: { path: string; body: unknown; key: string | null }[] = [];
  const me: MeResponse = { ...meFixture, permissions: OWNER };
  server.use(
    http.get('/api/v1/me', () => HttpResponse.json(me)),
    http.get('/api/v1/assistant/status', () => HttpResponse.json({ ...status, termsVersion: '2026-11-19' })),
    http.post('/api/v1/assistant/messages', async ({ request }) => {
      requests.push({ path: 'messages', body: await request.json(), key: null });
      return HttpResponse.json({
        answer: 'Je prépare une vente de 2 Savon noir.',
        insufficientData: false,
        proposal: {
          type: 'PREPARE_SALE', customerId: null, customerName: null, query: null, newCustomerName: null, newCustomerPhone: null,
          lines: [{ productId: 'p-1', productName: 'Savon noir', unit: 'PIECE', quantity: '2' }],
        },
      });
    }),
    http.post('/api/v1/sales', async ({ request }) => {
      requests.push({ path: 'sales', body: await request.json(), key: request.headers.get('Idempotency-Key') });
      return HttpResponse.json({ id: 's-9', status: 'DRAFT' }, { status: 201 });
    }),
    http.get('/api/v1/sales/:id', () => HttpResponse.json({ status: 404, code: 'NOT_FOUND' }, { status: 404 })),
  );
  return requests;
}

function renderAssistant() {
  const router = createMemoryRouter(routes, { initialEntries: ['/assistant'] });
  render(
    <AppProviders queryClient={createQueryClient()} runtime={{ mocksActive: false }}>
      <RouterProvider router={router} />
    </AppProviders>,
  );
  return router;
}

/** Reconnaissance vocale SIMULÉE (aucun micro dans les tests). */
class FakeRecognition {
  static last: FakeRecognition | null = null;
  lang = '';
  interimResults = false;
  continuous = false;
  maxAlternatives = 1;
  onresult: ((event: { results: unknown }) => void) | null = null;
  onerror: ((event: { error: string }) => void) | null = null;
  onend: (() => void) | null = null;
  constructor() {
    FakeRecognition.last = this;
  }
  start() {}
  stop() {
    this.onend?.();
  }
  abort() {}
}

afterEach(() => {
  delete (globalThis as { webkitSpeechRecognition?: unknown }).webkitSpeechRecognition;
});

describe('assistante IA', () => {
  it('connexion non configurée : indiquée clairement, aucun envoi possible, dictée non prise en charge signalée', async () => {
    backend({ globallyEnabled: true, configured: false, businessConsent: false, canManageConsent: true });
    renderAssistant();
    expect(await screen.findByText(/La connexion au service d'IA n'est pas configurée/, {}, SLOW)).toBeVisible();
    expect(screen.getByRole('button', { name: 'Envoyer' })).toBeDisabled();
    expect(screen.getAllByText(/La dictée vocale n'est pas disponible dans ce navigateur/).length).toBeGreaterThan(0);
    expect(screen.getByText(/peut envoyer l'audio à un service distant/)).toBeVisible();
  });

  it('dictée : texte transcrit dans le champ, refus du micro expliqué', async () => {
    (globalThis as { webkitSpeechRecognition?: unknown }).webkitSpeechRecognition = FakeRecognition;
    backend({ globallyEnabled: true, configured: true, businessConsent: true, canManageConsent: true });
    const user = userEvent.setup();
    renderAssistant();
    await user.click(await screen.findByRole('button', { name: 'Dicter ma question' }, SLOW));
    expect(screen.getByText('Écoute en cours… parlez maintenant.')).toBeVisible();
    expect(FakeRecognition.last?.lang).toBe('fr-FR');
    act(() => {
      FakeRecognition.last?.onresult?.({
        results: [Object.assign([{ transcript: 'Quels sont les produits dont le stock est faible ?' }], { isFinal: true })],
      });
      FakeRecognition.last?.onend?.();
    });
    expect(screen.getByLabelText('Votre question')).toHaveValue('Quels sont les produits dont le stock est faible ?');

    await user.click(screen.getByRole('button', { name: 'Dicter ma question' }));
    act(() => {
      FakeRecognition.last?.onerror?.({ error: 'not-allowed' });
      FakeRecognition.last?.onend?.();
    });
    expect(screen.getByText(/L'accès au microphone a été refusé/)).toBeVisible();
  });

  it('proposition de brouillon : rien n’est créé sans confirmation, puis création par la route normale', async () => {
    const requests = backend({ globallyEnabled: true, configured: true, businessConsent: true, canManageConsent: true });
    const user = userEvent.setup();
    const router = renderAssistant();
    await user.type(await screen.findByLabelText('Votre question', {}, SLOW), 'Prépare une vente de deux savons');
    await user.click(screen.getByRole('button', { name: 'Envoyer' }));
    expect(await screen.findByText('Je prépare une vente de 2 Savon noir.', {}, SLOW)).toBeVisible();
    expect(screen.getByText('Proposition de brouillon de vente')).toBeVisible();
    expect(requests.filter((r) => r.path === 'sales')).toHaveLength(0);
    await user.click(screen.getByRole('button', { name: 'Créer le brouillon' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/sales/s-9'), SLOW);
    const sale = requests.find((r) => r.path === 'sales');
    expect(sale?.body).toEqual({ customerId: null, lines: [{ productId: 'p-1', quantity: '2', discount: null }], globalDiscount: null });
    expect(sale?.key).toMatch(/^[0-9a-f-]{36}$/);
  });
});
