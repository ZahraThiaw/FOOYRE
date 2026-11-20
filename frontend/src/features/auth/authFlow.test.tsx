import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it, vi } from 'vitest';

import { AppProviders } from '@/app/AppProviders';
import { routes } from '@/app/routes';
import type { MeResponse } from '@/features/session/types';
import { meFixture } from '@/mocks/fixtures/me';
import { createQueryClient } from '@/shared/api/queryClient';
import { clearAccessToken, getAccessToken, setAccessToken } from '@/shared/auth/accessTokenStore';
import { configureSessionRefresh } from '@/shared/auth/sessionRefresh';
import { server } from '@/test/server';

import { login, logout, refreshSession } from './api';

// Parcours complets dans l'interface : plus lents quand toute la suite s'exécute en parallèle.
vi.setConfig({ testTimeout: 15_000 });

/** Profil RÉEL attendu du compte de démonstration (forme du contrat /me). */
const demoMe: MeResponse = {
  ...meFixture,
  fullName: 'Administrateur Démo',
  email: 'demo@fooyre.local',
  activeBusiness: { id: 'b-demo', name: 'FOOYRE Commerce Démo', currency: 'XOF' },
  activeMembership: { id: 'm-demo', role: 'BUSINESS_OWNER', employeeMaxDiscountRate: null },
  businesses: [{ id: 'b-demo', name: 'FOOYRE Commerce Démo', role: 'BUSINESS_OWNER' }],
};

const unauthorized = () =>
  HttpResponse.json({ status: 401, code: 'SESSION_REVOKED' }, { status: 401 });

/**
 * Backend simulé avec un vrai contrôle du jeton : `/me` n'aboutit qu'avec le jeton émis par la
 * connexion ou le renouvellement.
 */
function simulatedBackend(options: { readonly refreshWorks?: boolean } = {}) {
  const calls = { login: [] as unknown[], logout: 0, refresh: 0 };
  let validToken: string | null = null;
  server.use(
    http.post('/api/v1/auth/login', async ({ request }) => {
      const body = (await request.json()) as { email: string; password: string };
      calls.login.push(body);
      if (body.email !== 'demo@fooyre.local' || body.password !== 'bon-mot-de-passe') {
        return HttpResponse.json({ status: 401, code: 'INVALID_CREDENTIALS' }, { status: 401 });
      }
      validToken = 'jeton-connexion';
      return HttpResponse.json({ accessToken: validToken, expiresIn: 900 });
    }),
    http.post('/api/v1/auth/refresh', () => {
      calls.refresh += 1;
      if (!options.refreshWorks) return unauthorized();
      validToken = 'jeton-renouvele';
      return HttpResponse.json({ accessToken: validToken, expiresIn: 900 });
    }),
    http.post('/api/v1/auth/logout', () => {
      calls.logout += 1;
      validToken = null;
      return new HttpResponse(null, { status: 204 });
    }),
    http.get('/api/v1/me', ({ request }) =>
      validToken !== null && request.headers.get('Authorization') === `Bearer ${validToken}`
        ? HttpResponse.json(demoMe)
        : unauthorized(),
    ),
  );
  return {
    calls,
    expireSession: () => {
      validToken = null;
    },
  };
}

function renderAt(path: string) {
  configureSessionRefresh(refreshSession);
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  render(
    <AppProviders queryClient={createQueryClient()} runtime={{ mocksActive: false }}>
      <RouterProvider router={router} />
    </AppProviders>,
  );
  return router;
}

const loginHeading = () => screen.findByRole('heading', { level: 1, name: 'Connexion à votre espace' });

describe('page de connexion', () => {
  it('affiche le formulaire : e-mail, mot de passe, bouton, logo officiel FOOYRE', async () => {
    renderAt('/login');
    expect(await loginHeading()).toBeVisible();
    expect(screen.getByLabelText('Adresse e-mail')).toHaveAttribute('type', 'email');
    expect(screen.getByLabelText('Mot de passe')).toHaveAttribute('type', 'password');
    expect(screen.getByRole('button', { name: 'Se connecter' })).toBeEnabled();
    // Logo OFFICIEL fourni (jamais un logo généré) : fichier public, texte alternatif, proportions conservées.
    const logo = document.querySelector('[data-slot="brand-logo"] img');
    expect(logo).toHaveAttribute('src', '/images/fooyre-logo-512.png');
    expect(logo).toHaveAttribute('alt', 'FOOYRE');
    expect(logo).toHaveClass('object-contain');
    expect(document.querySelectorAll('img')).toHaveLength(1);
  });

  it('affiche ou masque le mot de passe', async () => {
    const user = userEvent.setup();
    renderAt('/login');
    await loginHeading();
    const password = screen.getByLabelText('Mot de passe');
    await user.click(screen.getByRole('button', { name: 'Afficher le mot de passe' }));
    expect(password).toHaveAttribute('type', 'text');
    await user.click(screen.getByRole('button', { name: 'Masquer le mot de passe' }));
    expect(password).toHaveAttribute('type', 'password');
  });

  it('valide les champs sans appeler le serveur', async () => {
    const backend = simulatedBackend();
    const user = userEvent.setup();
    renderAt('/login');
    await loginHeading();

    await user.click(screen.getByRole('button', { name: 'Se connecter' }));
    expect(screen.getByText('Saisissez votre adresse e-mail.')).toBeVisible();
    expect(screen.getByText('Saisissez votre mot de passe.')).toBeVisible();
    expect(screen.getByLabelText('Adresse e-mail')).toHaveAttribute('aria-invalid', 'true');

    await user.type(screen.getByLabelText('Adresse e-mail'), 'pas-une-adresse');
    await user.click(screen.getByRole('button', { name: 'Se connecter' }));
    expect(screen.getByText(/Saisissez une adresse e-mail valide/)).toBeVisible();
    expect(backend.calls.login).toHaveLength(0);
  });

  it('connexion réussie : affiche le vrai nom, l’entreprise et le rôle', async () => {
    const backend = simulatedBackend();
    const user = userEvent.setup();
    const router = renderAt('/login');
    await loginHeading();

    await user.type(screen.getByLabelText('Adresse e-mail'), ' demo@fooyre.local ');
    await user.type(screen.getByLabelText('Mot de passe'), 'bon-mot-de-passe');
    await user.click(screen.getByRole('button', { name: 'Se connecter' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Bienvenue, Administrateur Démo' })).toBeVisible();
    expect(screen.getAllByText('FOOYRE Commerce Démo').length).toBeGreaterThan(0);
    expect(screen.getByText('Propriétaire')).toBeVisible();
    expect(router.state.location.pathname).toBe('/');
    expect(backend.calls.login).toEqual([{ email: 'demo@fooyre.local', password: 'bon-mot-de-passe' }]);
    expect(getAccessToken()).toBe('jeton-connexion');
  });

  it('mauvais identifiants : message neutre, aucun jeton, mot de passe effacé', async () => {
    simulatedBackend();
    const user = userEvent.setup();
    renderAt('/login');
    await loginHeading();

    await user.type(screen.getByLabelText('Adresse e-mail'), 'demo@fooyre.local');
    await user.type(screen.getByLabelText('Mot de passe'), 'mauvais');
    await user.click(screen.getByRole('button', { name: 'Se connecter' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Adresse e-mail ou mot de passe incorrect.');
    expect(screen.getByLabelText('Mot de passe')).toHaveValue('');
    expect(getAccessToken()).toBeNull();
  });

  it('indicateur de chargement pendant la connexion (bouton désactivé)', async () => {
    let release!: () => void;
    const pending = new Promise<void>((resolve) => {
      release = resolve;
    });
    server.use(
      http.post('/api/v1/auth/login', async () => {
        await pending;
        return HttpResponse.json({ status: 401, code: 'INVALID_CREDENTIALS' }, { status: 401 });
      }),
    );
    const user = userEvent.setup();
    renderAt('/login');
    await loginHeading();
    await user.type(screen.getByLabelText('Adresse e-mail'), 'demo@fooyre.local');
    await user.type(screen.getByLabelText('Mot de passe'), 'x');
    await user.click(screen.getByRole('button', { name: 'Se connecter' }));

    expect(await screen.findByRole('button', { name: 'Connexion en cours…' })).toBeDisabled();
    release();
    expect(await screen.findByRole('button', { name: 'Se connecter' })).toBeEnabled();
  });

  it('erreur serveur : message générique traduit, jamais le texte technique', async () => {
    server.use(
      http.post('/api/v1/auth/login', () =>
        HttpResponse.json(
          { title: 'SQLException détaillée', status: 500, code: 'INTERNAL_ERROR', traceId: 't-1' },
          { status: 500 },
        ),
      ),
    );
    const user = userEvent.setup();
    renderAt('/login');
    await loginHeading();
    await user.type(screen.getByLabelText('Adresse e-mail'), 'demo@fooyre.local');
    await user.type(screen.getByLabelText('Mot de passe'), 'x');
    await user.click(screen.getByRole('button', { name: 'Se connecter' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Une erreur est survenue sur le serveur.');
    expect(screen.queryByText(/SQLException/)).toBeNull();
  });
});

describe('session et routes protégées', () => {
  it('route protégée sans session : redirection vers /login, sans message d’expiration', async () => {
    simulatedBackend();
    const router = renderAt('/');
    expect(await loginHeading()).toBeVisible();
    expect(router.state.location.pathname).toBe('/login');
    expect(screen.queryByText(/Votre session a expiré/)).toBeNull();
  });

  it('retour à la page demandée après connexion', async () => {
    simulatedBackend();
    const user = userEvent.setup();
    const router = renderAt('/une-page-privee');
    await loginHeading();
    await user.type(screen.getByLabelText('Adresse e-mail'), 'demo@fooyre.local');
    await user.type(screen.getByLabelText('Mot de passe'), 'bon-mot-de-passe');
    await user.click(screen.getByRole('button', { name: 'Se connecter' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/une-page-privee'));
  });

  it('chargement de la session au démarrage : restaurée par le cookie (actualisation de la page)', async () => {
    const backend = simulatedBackend({ refreshWorks: true });
    renderAt('/');
    expect(await screen.findByRole('heading', { level: 1, name: 'Bienvenue, Administrateur Démo' })).toBeVisible();
    expect(backend.calls.refresh).toBe(1);
    expect(getAccessToken()).toBe('jeton-renouvele');
  });

  it('affiche un indicateur pendant le chargement du profil', async () => {
    simulatedBackend({ refreshWorks: true });
    setAccessToken('jeton-renouvele');
    renderAt('/');
    expect(screen.getByRole('status')).toHaveTextContent('Chargement de votre profil…');
    await screen.findByRole('heading', { level: 1, name: 'Bienvenue, Administrateur Démo' });
  });

  it('expiration de session en cours d’utilisation : retour à la connexion avec un message', async () => {
    const backend = simulatedBackend();
    const user = userEvent.setup();
    const router = renderAt('/login');
    await loginHeading();
    await user.type(screen.getByLabelText('Adresse e-mail'), 'demo@fooyre.local');
    await user.type(screen.getByLabelText('Mot de passe'), 'bon-mot-de-passe');
    await user.click(screen.getByRole('button', { name: 'Se connecter' }));
    await screen.findByRole('heading', { level: 1, name: 'Bienvenue, Administrateur Démo' });

    // Le serveur révoque la session ; le jeton en mémoire est effacé (renouvellement refusé).
    backend.expireSession();
    clearAccessToken();

    expect(await screen.findByText('Votre session a expiré. Veuillez vous reconnecter.')).toBeVisible();
    expect(router.state.location.pathname).toBe('/login');
  });

  it('403 au chargement du profil : message, aucun contenu protégé', async () => {
    server.use(
      http.get('/api/v1/me', () => HttpResponse.json({ status: 403, code: 'FORBIDDEN' }, { status: 403 })),
    );
    renderAt('/');
    expect(await screen.findByRole('alert')).toHaveTextContent("Votre compte n'a pas accès à cette application.");
    expect(screen.queryByRole('navigation')).toBeNull();
  });
});

describe('déconnexion', () => {
  it('révoque la session, efface le jeton et ramène à la connexion ; l’espace n’est plus accessible', async () => {
    const backend = simulatedBackend();
    const user = userEvent.setup();
    const router = renderAt('/login');
    await loginHeading();
    await user.type(screen.getByLabelText('Adresse e-mail'), 'demo@fooyre.local');
    await user.type(screen.getByLabelText('Mot de passe'), 'bon-mot-de-passe');
    await user.click(screen.getByRole('button', { name: 'Se connecter' }));
    await screen.findByRole('heading', { level: 1, name: 'Bienvenue, Administrateur Démo' });

    await user.click(screen.getByRole('button', { name: 'Se déconnecter' }));

    expect(await screen.findByText('Vous avez été déconnecté·e.')).toBeVisible();
    expect(backend.calls.logout).toBe(1);
    expect(getAccessToken()).toBeNull();
    expect(router.state.location.pathname).toBe('/login');

    // Retour sur une route protégée : pas d'accès sans nouvelle connexion.
    await router.navigate('/');
    await waitFor(() => expect(router.state.location.pathname).toBe('/login'));
    expect(await loginHeading()).toBeVisible();
    expect(screen.queryByText('Bienvenue, Administrateur Démo')).toBeNull();
  });
});

describe('api d’authentification', () => {
  it('login : envoie X-Requested-With, garde le jeton en mémoire uniquement et vide le cache', async () => {
    let requestedWith: string | null = null;
    server.use(
      http.post('/api/v1/auth/login', ({ request }) => {
        requestedWith = request.headers.get('X-Requested-With');
        return HttpResponse.json({ accessToken: 'jeton', expiresIn: 900 });
      }),
    );
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    const queryClient = createQueryClient();
    queryClient.setQueryData(['ancienne', 'session'], ['donnée']);

    await login(queryClient, { email: 'demo@fooyre.local', password: 'x' });

    expect(requestedWith).toBe('fooyre');
    expect(getAccessToken()).toBe('jeton');
    expect(queryClient.getQueryData(['ancienne', 'session'])).toBeUndefined();
    expect(setItem).not.toHaveBeenCalled();
  });

  it('refreshSession : nouveau jeton, ou null si la session ne peut être rétablie', async () => {
    simulatedBackend({ refreshWorks: true });
    await expect(refreshSession()).resolves.toBe('jeton-renouvele');
    simulatedBackend({ refreshWorks: false });
    await expect(refreshSession()).resolves.toBeNull();
  });

  it('logout : session locale fermée même si le serveur est injoignable', async () => {
    server.use(http.post('/api/v1/auth/logout', () => HttpResponse.error()));
    setAccessToken('jeton');
    const queryClient = createQueryClient();
    queryClient.setQueryData(['x'], 1);
    await logout(queryClient);
    expect(getAccessToken()).toBeNull();
    expect(queryClient.getQueryData(['x'])).toBeUndefined();
  });
});
