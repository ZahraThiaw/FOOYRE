/**
 * Démarrage de l'application, en « échec fermé » pour le mode démonstration (F2).
 *
 * 1. `startMocking()` :
 *    - sans données fictives (`@mocks-entry` → entry.disabled.ts) : renvoie `false`, ne peut
 *      pas échouer ;
 *    - avec VITE_ENABLE_MOCKS=true (entry.enabled.ts) : renvoie `true`, ou REJETTE si MSW
 *      n'a pas pu être initialisé (import impossible, service worker refusé…).
 * 2. Si les données fictives ont été demandées et ont échoué : l'application N'EST PAS
 *    chargée (aucun module applicatif, aucun appel API) ; un message d'erreur bloquant est
 *    affiché à la place. On ne bascule jamais silencieusement en mode réel.
 * 3. Sinon : chargement et rendu de l'application.
 */
import type { RuntimeInfo } from './runtimeContext';

export type RenderApp = (container: HTMLElement, runtime: RuntimeInfo) => void;

export interface BootstrapDependencies {
  readonly startMocking: () => Promise<boolean>;
  readonly loadApp: () => Promise<RenderApp>;
  readonly renderMockStartupFailure: (container: HTMLElement, error: unknown) => Promise<void>;
}

export type BootstrapOutcome = 'app' | 'blocked';

export async function bootstrap(
  container: HTMLElement | null,
  dependencies: BootstrapDependencies,
): Promise<BootstrapOutcome> {
  if (!container) {
    throw new Error('[FOOYRE] Élément #root introuvable.');
  }

  let mocksActive: boolean;
  try {
    mocksActive = await dependencies.startMocking();
  } catch (error: unknown) {
    await dependencies.renderMockStartupFailure(container, error);
    return 'blocked';
  }

  const renderApp = await dependencies.loadApp();
  renderApp(container, { mocksActive });
  return 'app';
}
