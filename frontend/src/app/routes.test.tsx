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
    // MVP‑1 : route protégée, le profil est chargé avant l’affichage de l’accueil.
    expect(
      await screen.findByRole('heading', { level: 1, name: `Bienvenue, ${meFixture.fullName}` }),
    ).toBeVisible();
    expect(screen.getAllByText(meFixture.activeBusiness?.name ?? '').length).toBeGreaterThan(0);
  });

  it('affiche un message traduit et neutre si /me est indisponible (404)', async () => {
    server.use(http.get('/api/v1/me', () => new HttpResponse(null, { status: 404 })));
    renderAt('/');
    expect(await screen.findByText("Votre profil n'a pas pu être chargé.")).toBeVisible();
    expect(screen.getByRole('alert')).toHaveTextContent("L'élément demandé est introuvable.");
  });

  it('session absente (401) : redirection vers la page de connexion', async () => {
    server.use(
      http.get('/api/v1/me', () =>
        HttpResponse.json({ status: 401, code: 'SESSION_REVOKED' }, { status: 401 }),
      ),
    );
    renderAt('/');
    // MVP‑1 : la route protégée redirige vers /login au lieu d’afficher un message.
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Connexion à votre espace' }),
    ).toBeVisible();
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

  it('affiche la page 404 pour une route inconnue', async () => {
    renderAt('/route-inexistante');
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Page introuvable' }),
    ).toBeVisible();
    expect(screen.getByRole('link', { name: "Retour à l'accueil" })).toHaveAttribute('href', '/');
  });

  it('n’affiche le bandeau « données fictives » que lorsque les mocks sont actifs', async () => {
    // MVP‑1 : le bandeau fait partie de l’espace connecté (affiché après chargement du profil).
    const { unmount } = renderAt('/', false);
    await screen.findByRole('heading', { level: 1 });
    expect(screen.queryByText(/Données de démonstration — fictives/)).toBeNull();
    unmount();

    renderAt('/', true);
    await screen.findByRole('heading', { level: 1 });
    expect(screen.getByText(/Données de démonstration — fictives/)).toBeVisible();
  });
});
