/**
 * F2 — Démarrage en « échec fermé ».
 * Les scénarios d'échec utilisent le VRAI point d'entrée des mocks (entry.enabled.ts) ; seul
 * le module `virtual:msw` (fourni normalement par le plugin msw/vite du serveur de
 * développement) est remplacé, pour simuler un import impossible ou un échec de
 * `network.enable()`.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { startMocking as startWithoutMocks } from '@/mocks/entry.disabled';

import { bootstrap, type BootstrapDependencies, type RenderApp } from './bootstrap';
import { renderMockStartupFailure } from './mockStartupFailure';

let container: HTMLElement;
let renderApp: ReturnType<typeof vi.fn<RenderApp>>;
let loadApp: ReturnType<typeof vi.fn<() => Promise<RenderApp>>>;
let fetchSpy: ReturnType<typeof vi.spyOn>;
let xhrOpenSpy: ReturnType<typeof vi.spyOn>;

function dependencies(startMocking: () => Promise<boolean>): BootstrapDependencies {
  return { startMocking, loadApp, renderMockStartupFailure };
}

/** Charge le vrai entry.enabled.ts avec un module `virtual:msw` simulé. */
async function loadEnabledEntry(virtualMsw: () => Record<string, unknown>) {
  vi.resetModules();
  vi.doMock('virtual:msw', virtualMsw);
  const module = await import('@/mocks/entry.enabled');
  return module.startMocking;
}

beforeEach(() => {
  container = document.createElement('div');
  document.body.append(container);
  renderApp = vi.fn<RenderApp>();
  loadApp = vi.fn(() => Promise.resolve(renderApp));
  fetchSpy = vi.spyOn(globalThis, 'fetch');
  xhrOpenSpy = vi.spyOn(XMLHttpRequest.prototype, 'open');
  vi.spyOn(console, 'error').mockImplementation(() => undefined);
});

afterEach(() => {
  container.remove();
  vi.doUnmock('virtual:msw');
  vi.resetModules();
});

function expectNoNetworkCall() {
  expect(fetchSpy).not.toHaveBeenCalled();
  expect(xhrOpenSpy).not.toHaveBeenCalled();
}

function expectBlockedScreen() {
  const alert = container.querySelector('[role="alert"]');
  expect(alert).not.toBeNull();
  expect(alert).toHaveTextContent('Mode démonstration indisponible');
  expect(alert).toHaveTextContent("l'application a été bloquée");
  expect(alert).toHaveTextContent("elle n'a pas démarré en mode réel");
}

describe('bootstrap', () => {
  it('démarrage normal sans données fictives : application lancée en mode réel', async () => {
    await expect(bootstrap(container, dependencies(startWithoutMocks))).resolves.toBe('app');
    expect(loadApp).toHaveBeenCalledTimes(1);
    expect(renderApp).toHaveBeenCalledWith(container, { mocksActive: false });
  });

  it('démarrage réussi avec données fictives : application lancée en mode démonstration', async () => {
    const network = { configure: vi.fn(), enable: vi.fn(() => Promise.resolve()) };
    const startMocking = await loadEnabledEntry(() => ({ network }));

    await expect(bootstrap(container, dependencies(startMocking))).resolves.toBe('app');

    expect(network.configure).toHaveBeenCalledWith(
      expect.objectContaining({ onUnhandledFrame: 'bypass' }),
    );
    expect(network.enable).toHaveBeenCalledTimes(1);
    expect(renderApp).toHaveBeenCalledWith(container, { mocksActive: true });
  });

  it('échec de l’import du module MSW : application bloquée, aucun appel réseau', async () => {
    const startMocking = await loadEnabledEntry(() => {
      throw new Error('module virtual:msw introuvable');
    });

    await expect(bootstrap(container, dependencies(startMocking))).resolves.toBe('blocked');

    expect(loadApp).not.toHaveBeenCalled();
    expect(renderApp).not.toHaveBeenCalled();
    expectNoNetworkCall();
    expectBlockedScreen();
  });

  it('échec de network.enable() : application bloquée, aucun appel réseau', async () => {
    const network = {
      configure: vi.fn(),
      enable: vi.fn(() => Promise.reject(new Error('service worker refusé'))),
    };
    const startMocking = await loadEnabledEntry(() => ({ network }));

    await expect(bootstrap(container, dependencies(startMocking))).resolves.toBe('blocked');

    expect(network.enable).toHaveBeenCalledTimes(1);
    expect(loadApp).not.toHaveBeenCalled();
    expect(renderApp).not.toHaveBeenCalled();
    expectNoNetworkCall();
    expectBlockedScreen();
  });

  it('message d’erreur visible, compréhensible et sans interprétation HTML du détail', async () => {
    const startMocking = () => Promise.reject(new Error('<img src=x onerror=alert(1)>'));

    await bootstrap(container, dependencies(startMocking));

    expectBlockedScreen();
    expect(container).toHaveTextContent('Détail technique : <img src=x onerror=alert(1)>');
    expect(container.querySelector('img')).toBeNull();
    expect(container).toHaveTextContent('VITE_ENABLE_MOCKS=false');
  });

  it('élément racine absent : erreur explicite', async () => {
    await expect(bootstrap(null, dependencies(startWithoutMocks))).rejects.toThrow('#root');
  });
});
