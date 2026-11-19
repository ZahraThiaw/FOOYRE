/**
 * Point d'entrée des mocks utilisé par défaut (build de production, tests, développement
 * sans VITE_ENABLE_MOCKS=true). Ne référence ni MSW, ni handlers, ni fixtures : ils ne
 * peuvent donc pas se retrouver dans le bundle. Sélectionné par l'alias `@mocks-entry`
 * de vite.config.ts.
 */
export function startMocking(): Promise<boolean> {
  return Promise.resolve(false);
}
