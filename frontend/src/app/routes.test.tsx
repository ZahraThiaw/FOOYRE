import { render, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it } from 'vitest';

import { meFixture } from '@/mocks/fixtures/me';
import { createQueryClient } from '@/shared/api/queryClient';
import { server } from '@/test/server';

import { AppProviders } from './AppProviders';
import { routes } from './routes';

function renderAt(path: string, mocksActive = false) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  return render(
    <AppProviders queryClient={createQueryClient()} runtime={{ mocksActive }}>
      <RouterProvider router={router} />
    </AppProviders>,
  );
}

describe('routes', () => {
  it('affiche la page d’accueil provisoire et le profil /me', async () => {
    renderAt('/');
    expect(screen.getByRole('heading', { level: 1, name: 'Bienvenue sur FOOYRE' })).toBeVisible();
    expect(await screen.findByText(`Connecté·e en tant que ${meFixture.fullName}`)).toBeVisible();
  });

  it('affiche un message traduit et neutre si /me est indisponible (404)', async () => {
    server.use(http.get('/api/v1/me', () => new HttpResponse(null, { status: 404 })));
    renderAt('/');
    expect(await screen.findByText("Votre profil n'a pas pu être chargé.")).toBeVisible();
    expect(screen.getByRole('alert')).toHaveTextContent("L'élément demandé est introuvable.");
  });

  it('session expirée (401) : message dédié', async () => {
    server.use(
      http.get('/api/v1/me', () =>
        HttpResponse.json({ status: 401, code: 'SESSION_REVOKED' }, { status: 401 }),
      ),
    );
    renderAt('/');
    expect(await screen.findByRole('alert')).toHaveTextContent(
      "Votre session a expiré ou vous n'êtes pas connecté·e.",
    );
  });

  it('erreur serveur (500) : message générique et référence, jamais le texte technique', async () => {
    server.use(
      http.get('/api/v1/me', () =>
        HttpResponse.json(
          {
            title: 'NullPointerException in MeController',
            status: 500,
            code: 'INTERNAL_ERROR',
            traceId: 'trace-500',
          },
          { status: 500 },
        ),
      ),
    );
    renderAt('/');
    // La configuration réelle refait 2 tentatives sur les 5xx avant d'afficher l'erreur.
    const alert = await screen.findByRole('alert', {}, { timeout: 10_000 });
    expect(alert).toHaveTextContent('Une erreur est survenue sur le serveur.');
    expect(alert).toHaveTextContent('Référence à communiquer au support : trace-500');
    expect(screen.queryByText(/NullPointerException/)).toBeNull();
  }, 15_000);

  it('aucune entreprise active : invitation à choisir parmi les entreprises', async () => {
    server.use(
      http.get('/api/v1/me', () =>
        HttpResponse.json({
          ...meFixture,
          activeBusiness: null,
          activeMembership: null,
          businesses: [
            { id: 'b-1', name: 'Boutique A (fictive)', role: 'BUSINESS_OWNER' },
            { id: 'b-2', name: 'Boutique B (fictive)', role: 'EMPLOYEE' },
          ],
        }),
      ),
    );
    renderAt('/');
    expect(await screen.findByText('Aucune entreprise sélectionnée')).toBeVisible();
    expect(screen.getByText('Boutique A (fictive)')).toBeVisible();
    expect(screen.getByText('Boutique B (fictive)')).toBeVisible();
  });

  it('affiche la page 404 pour une route inconnue', () => {
    renderAt('/route-inexistante');
    expect(screen.getByRole('heading', { level: 1, name: 'Page introuvable' })).toBeVisible();
    expect(screen.getByRole('link', { name: "Retour à l'accueil" })).toHaveAttribute('href', '/');
  });

  it('n’affiche le bandeau « données fictives » que lorsque les mocks sont actifs', () => {
    const { unmount } = renderAt('/', false);
    expect(screen.queryByRole('status')).toBeNull();
    unmount();

    renderAt('/', true);
    expect(screen.getByRole('status')).toHaveTextContent('Données de démonstration — fictives');
  });
});
