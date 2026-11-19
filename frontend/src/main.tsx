/**
 * Point d'entrée. La logique de démarrage (échec fermé du mode démonstration) est dans
 * app/bootstrap.ts, testée séparément.
 * - `@mocks-entry` pointe vers un module vide sauf serveur de développement +
 *   VITE_ENABLE_MOCKS=true (voir vite.config.ts).
 * - Pas d'`await` de premier niveau : non pris en charge par Safari 14 (cible navigateurs).
 */
import { startMocking } from '@mocks-entry';

import { bootstrap } from './app/bootstrap';
import { renderMockStartupFailure } from './app/mockStartupFailure';
import './styles/index.css';

void bootstrap(document.getElementById('root'), {
  startMocking,
  loadApp: () => import('./app/renderApp').then((module) => module.renderApp),
  renderMockStartupFailure,
});
