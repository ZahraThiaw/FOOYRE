/**
 * Réponse FICTIVE de GET /me. Aucune personne ni entreprise réelle.
 * Le marqueur FIXTURE_MARKER permet de vérifier qu'aucune donnée fictive n'est présente
 * dans le build de production (scripts/verify-build.mjs).
 */
import type { MeResponse } from '@/features/session/types';

export const FIXTURE_MARKER = 'FOOYRE_DONNEES_FICTIVES';

export const meFixture: MeResponse & { readonly fixture: typeof FIXTURE_MARKER } = {
  fixture: FIXTURE_MARKER,
  id: '00000000-0000-4000-8000-000000000001',
  fullName: 'Utilisatrice de démonstration',
  email: 'demo@example.invalid',
  locale: 'fr',
  platformAdmin: false,
  activeBusiness: {
    id: '00000000-0000-4000-8000-000000000101',
    name: 'Boutique de démonstration (fictive)',
    currency: 'XOF',
  },
  activeMembership: {
    id: '00000000-0000-4000-8000-000000000201',
    role: 'BUSINESS_OWNER',
    employeeMaxDiscountRate: null,
  },
  permissions: ['BUSINESS_READ'],
  businesses: [
    {
      id: '00000000-0000-4000-8000-000000000101',
      name: 'Boutique de démonstration (fictive)',
      role: 'BUSINESS_OWNER',
    },
  ],
};
