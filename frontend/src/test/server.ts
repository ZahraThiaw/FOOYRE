/** Serveur MSW pour les tests (Node + jsdom). Utilise les mêmes handlers fictifs. */
import { setupServer } from 'msw/node';

import { handlers } from '@/mocks/handlers';

export const server = setupServer(...handlers);
