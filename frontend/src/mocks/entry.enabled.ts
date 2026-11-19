/**
 * Point d'entrée des mocks — DÉVELOPPEMENT UNIQUEMENT.
 * Sélectionné par l'alias `@mocks-entry` seulement si : serveur de développement (`vite`),
 * hors mode test, et VITE_ENABLE_MOCKS=true. Le module `virtual:msw` est fourni par le
 * plugin `msw/vite`, lui aussi branché uniquement dans ce cas.
 *
 * Isolation : toute requête API non simulée reçoit une réponse 501 fictive et n'atteint
 * jamais un backend réel (voir demoNetwork.ts). Les requêtes hors API passent normalement.
 */
export async function startMocking(): Promise<boolean> {
  // Sécurité supplémentaire : jamais en dehors du mode développement.
  if (!import.meta.env.DEV) {
    return false;
  }

  const [{ network }, { createDemoHandlers, DEMO_ON_UNHANDLED_FRAME }] = await Promise.all([
    import('virtual:msw'),
    import('./demoNetwork'),
  ]);

  network.configure({ handlers: createDemoHandlers(), onUnhandledFrame: DEMO_ON_UNHANDLED_FRAME });
  await network.enable();
  return true;
}
