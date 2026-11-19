/**
 * Remplaçant de `virtual:msw` pour les TESTS UNIQUEMENT (alias défini dans vite.config.ts
 * en mode "test"). Le vrai module est fourni par le plugin msw/vite du serveur de
 * développement, absent de Vitest. Les tests qui exercent entry.enabled.ts le remplacent
 * par `vi.doMock('virtual:msw', …)`. Sans remplacement, `network` est indéfini et
 * `startMocking()` échoue (échec fermé).
 */
export const network = undefined;
