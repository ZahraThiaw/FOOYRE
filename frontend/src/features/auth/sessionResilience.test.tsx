import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { AppProviders } from '@/app/AppProviders';
import { routes } from '@/app/routes';
import type { MeResponse } from '@/features/session/types';
import { meFixture } from '@/mocks/fixtures/me';
import { createQueryClient } from '@/shared/api/queryClient';
import { getAccessToken, setAccessToken } from '@/shared/auth/accessTokenStore';
import {
  configureSessionRefresh,
  refreshAccessToken,
  suspendSessionRefresh,
} from '@/shared/auth/sessionRefresh';
import { server } from '@/test/server';

import { logout, refreshSession, SESSION_REFRESH_LOCK } from './api';

// Parcours complets dans l'interface : plus lents quand toute la suite s'exécute en parallèle.
vi.setConfig({ testTimeout: 15_000 });

/**
 * MVP‑1.1 — déconnexion quand le serveur est indisponible, suspension du renouvellement pendant
 * la déconnexion, coordination des renouvellements entre onglets (Web Locks).
 */

const demoMe: MeResponse = {
  ...meFixture,
  fullName: 'Administrateur Démo',
  activeBusiness: { id: 'b', name: 'FOOYRE Commerce Démo', currency: 'XOF' },
  activeMembership: { id: 'm', role: 'BUSINESS_OWNER', employeeMaxDiscountRate: null },
};

const originalLocks = Object.getOwnPropertyDescriptor(navigator, 'locks');

afterEach(() => {
  if (originalLocks) {
    Object.defineProperty(navigator, 'locks', originalLocks);
  } else {
    Reflect.deleteProperty(navigator, 'locks');
  }
});

/** Backend simulé : /me exige le jeton émis ; /auth/logout configurable. */
function backend(logoutResponse: () => Response) {
  const calls = { refresh: 0, logout: 0 };
  let valid: string | null = null;
  server.use(
    http.post('/api/v1/auth/login', () => {
      valid = 'jeton-connexion';
      return HttpResponse.json({ accessToken: valid, expiresIn: 900 });
    }),
    http.post('/api/v1/auth/refresh', () => {
      calls.refresh += 1;
      return HttpResponse.json({ status: 401, code: 'SESSION_REVOKED' }, { status: 401 });
    }),
    http.post('/api/v1/auth/logout', () => {
      calls.logout += 1;
      return logoutResponse();
    }),
    http.get('/api/v1/me', ({ request }) =>
      valid !== null && request.headers.get('Authorization') === `Bearer ${valid}`
        ? HttpResponse.json(demoMe)
        : HttpResponse.json({ status: 401, code: 'SESSION_REVOKED' }, { status: 401 }),
    ),
  );
  return calls;
}

async function loginThroughTheUi() {
  configureSessionRefresh(refreshSession);
  const user = userEvent.setup();
  const router = createMemoryRouter(routes, { initialEntries: ['/login'] });
  render(
    <AppProviders queryClient={createQueryClient()} runtime={{ mocksActive: false }}>
      <RouterProvider router={router} />
    </AppProviders>,
  );
  await screen.findByRole('heading', { level: 1, name: 'Connexion à votre espace' });
  await user.type(screen.getByLabelText('Adresse e-mail'), 'demo@fooyre.local');
  await user.type(screen.getByLabelText('Mot de passe'), 'bon-mot-de-passe');
  await user.click(screen.getByRole('button', { name: 'Se connecter' }));
  await screen.findByRole('heading', { level: 1, name: 'Bienvenue, Administrateur Démo' });
  return { user, router };
}

const LOCAL_ONLY = /Le serveur n'a pas pu confirmer la fermeture de votre session/;

describe('déconnexion (serveur disponible ou non)', () => {
  it('logout normal : confirmation du serveur, message standard', async () => {
    const calls = backend(() => new HttpResponse(null, { status: 204 }));
    const { user, router } = await loginThroughTheUi();
    await user.click(screen.getByRole('button', { name: 'Se déconnecter' }));

    expect(await screen.findByText('Vous avez été déconnecté·e.')).toBeVisible();
    expect(screen.queryByText(LOCAL_ONLY)).toBeNull();
    expect(router.state.location.pathname).toBe('/login');
    expect(getAccessToken()).toBeNull();
    expect(calls.logout).toBe(1);
  });

  it.each([
    ['backend indisponible (502 du proxy)', () => new HttpResponse(null, { status: 502 })],
    ['erreur réseau', () => HttpResponse.error()],
    ['session déjà expirée côté serveur (401)', () =>
      HttpResponse.json({ status: 401, code: 'SESSION_REVOKED' }, { status: 401 })],
  ])('%s : session locale fermée, message sans prétendre à une révocation', async (_label, response) => {
    backend(response);
    const { user, router } = await loginThroughTheUi();
    await user.click(screen.getByRole('button', { name: 'Se déconnecter' }));

    expect(await screen.findByText(LOCAL_ONLY)).toBeVisible();
    expect(screen.queryByText('Vous avez été déconnecté·e.')).toBeNull();
    expect(router.state.location.pathname).toBe('/login');
    expect(getAccessToken()).toBeNull();
  });

  it('nouvelle connexion possible après une déconnexion seulement locale', async () => {
    backend(() => HttpResponse.error());
    const { user } = await loginThroughTheUi();
    await user.click(screen.getByRole('button', { name: 'Se déconnecter' }));
    await screen.findByText(LOCAL_ONLY);

    await user.type(screen.getByLabelText('Adresse e-mail'), 'demo@fooyre.local');
    await user.type(screen.getByLabelText('Mot de passe'), 'bon-mot-de-passe');
    await user.click(screen.getByRole('button', { name: 'Se connecter' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Bienvenue, Administrateur Démo' })).toBeVisible();
  });

  it('pendant la déconnexion, aucun renouvellement ne rouvre la session', async () => {
    let releaseLogout!: () => void;
    const pending = new Promise<void>((resolve) => {
      releaseLogout = resolve;
    });
    const refresh = vi.fn(() => Promise.resolve('jeton-rouvert'));
    configureSessionRefresh(refresh);
    server.use(
      http.post('/api/v1/auth/logout', async () => {
        await pending;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    setAccessToken('jeton');
    const done = logout(createQueryClient());

    expect(getAccessToken()).toBeNull(); // effacé immédiatement, avant la réponse du serveur
    await expect(refreshAccessToken()).resolves.toBeNull();
    expect(refresh).not.toHaveBeenCalled();

    releaseLogout();
    await expect(done).resolves.toEqual({ serverConfirmed: true });
    await expect(refreshAccessToken()).resolves.toBe('jeton-rouvert'); // suspension levée
  });

  it('aucun jeton dans localStorage ni sessionStorage, quel que soit le parcours', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    backend(() => HttpResponse.error());
    const { user } = await loginThroughTheUi();
    await user.click(screen.getByRole('button', { name: 'Se déconnecter' }));
    await screen.findByText(LOCAL_ONLY);
    expect(setItem).not.toHaveBeenCalled();
    // Lecture volontaire, uniquement pour vérifier que rien n'y a été écrit.
    // eslint-disable-next-line no-restricted-properties
    expect(window.localStorage.length + window.sessionStorage.length).toBe(0);
  });
});

describe('suspension du renouvellement', () => {
  it('les suspensions s’empilent et chaque levée n’agit qu’une fois', async () => {
    configureSessionRefresh(() => Promise.resolve('jeton'));
    const first = suspendSessionRefresh();
    const second = suspendSessionRefresh();
    first();
    first();
    await expect(refreshAccessToken()).resolves.toBeNull();
    second();
    await expect(refreshAccessToken()).resolves.toBe('jeton');
  });
});

describe('coordination entre onglets (Web Locks)', () => {
  /** Faux LockManager : exécute les demandes une par une, dans l'ordre. */
  function installFakeLocks() {
    const names: string[] = [];
    let queue = Promise.resolve();
    const request = vi.fn((name: string, _options: unknown, callback: () => Promise<unknown>) => {
      names.push(name);
      const run = queue.then(callback);
      queue = run.then(
        () => undefined,
        () => undefined,
      );
      return run;
    });
    Object.defineProperty(navigator, 'locks', { configurable: true, value: { request } });
    return { names, request };
  }

  it('les renouvellements passent par un verrou exclusif partagé et sont sérialisés', async () => {
    const { names } = installFakeLocks();
    let active = 0;
    let maxActive = 0;
    let n = 0;
    server.use(
      http.post('/api/v1/auth/refresh', async () => {
        active += 1;
        maxActive = Math.max(maxActive, active);
        await new Promise((resolve) => setTimeout(resolve, 20));
        active -= 1;
        n += 1;
        return HttpResponse.json({ accessToken: `jeton-${n}`, expiresIn: 900 });
      }),
    );

    // Deux « onglets » demandent un renouvellement au même moment.
    const results = await Promise.all([refreshSession(), refreshSession()]);

    expect(names).toEqual([SESSION_REFRESH_LOCK, SESSION_REFRESH_LOCK]);
    expect(maxActive).toBe(1);
    expect(results).toEqual(['jeton-1', 'jeton-2']);
  });

  it('sans Web Locks (ex. Safari 14) : renouvellement direct, inchangé', async () => {
    Object.defineProperty(navigator, 'locks', { configurable: true, value: undefined });
    server.use(http.post('/api/v1/auth/refresh', () => HttpResponse.json({ accessToken: 'direct', expiresIn: 900 })));
    await expect(refreshSession()).resolves.toBe('direct');
  });

  it('verrou indisponible : repli sans coordination, jamais d’échec bloquant', async () => {
    Object.defineProperty(navigator, 'locks', {
      configurable: true,
      value: { request: () => Promise.reject(new Error('SecurityError')) },
    });
    server.use(http.post('/api/v1/auth/refresh', () => HttpResponse.json({ accessToken: 'repli', expiresIn: 900 })));
    await expect(refreshSession()).resolves.toBe('repli');
  });

  it('refus du serveur : null, sous verrou comme sans verrou', async () => {
    installFakeLocks();
    server.use(
      http.post('/api/v1/auth/refresh', () =>
        HttpResponse.json({ status: 401, code: 'SESSION_REVOKED' }, { status: 401 }),
      ),
    );
    await expect(refreshSession()).resolves.toBeNull();
    await waitFor(() => expect(getAccessToken()).toBeNull());
  });
});
