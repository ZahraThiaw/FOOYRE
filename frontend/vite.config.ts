import { fileURLToPath, URL } from 'node:url';

import react from '@vitejs/plugin-react';
import { msw } from 'msw/vite';
import { loadEnv } from 'vite';
import { defineConfig } from 'vitest/config';

/**
 * Cible navigateurs provisoire (docs/architecture.md, ADR‑12).
 * Vite ne lit PAS la liste `browserslist` de package.json (utilisée par Autoprefixer) :
 * les deux listes doivent rester alignées manuellement.
 */
const BROWSER_TARGETS = ['chrome87', 'edge88', 'firefox78', 'safari14', 'ios14'];

/** Backend local (développement uniquement). Valeur non sensible. */
const DEV_API_PROXY_TARGET = 'http://localhost:8080';

const src = (path: string) => fileURLToPath(new URL(`./src/${path}`, import.meta.url));

export default defineConfig(({ command, mode }) => {
  // Seules les variables préfixées VITE_ (publiques) sont lues, depuis frontend/.env*.
  const env = loadEnv(mode, fileURLToPath(new URL('.', import.meta.url)), 'VITE_');
  const mocksRequested = env.VITE_ENABLE_MOCKS === 'true';

  // Verrou n° 1 : un build ne peut jamais embarquer les données fictives, quel que soit le mode.
  if (command === 'build' && mocksRequested) {
    throw new Error(
      '[FOOYRE] VITE_ENABLE_MOCKS=true est interdit pour un build. ' +
        'Les données fictives (MSW) sont réservées au serveur de développement et aux tests.',
    );
  }

  // Verrou n° 2 : MSW n'est branché que sur le serveur de développement, sur demande explicite.
  // Les tests (mode "test") utilisent msw/node directement, sans le plugin.
  const mocksEnabled = command === 'serve' && mode !== 'test' && mocksRequested;

  return {
    plugins: [react(), ...(mocksEnabled ? [msw()] : [])],
    resolve: {
      alias: {
        // Verrou n° 3 : sans mocks, le point d'entrée des mocks est un module vide ;
        // `virtual:msw`, les handlers et les fixtures ne font alors pas partie du graphe de modules.
        '@mocks-entry': src(mocksEnabled ? 'mocks/entry.enabled.ts' : 'mocks/entry.disabled.ts'),
        // Tests uniquement : `virtual:msw` (fourni par le plugin msw/vite en développement)
        // est remplacé pour pouvoir tester entry.enabled.ts. Sans effet en dev et en build.
        ...(mode === 'test' ? { 'virtual:msw': src('test/virtualMswStub.ts') } : {}),
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    build: {
      target: BROWSER_TARGETS,
      cssTarget: BROWSER_TARGETS,
      sourcemap: false,
    },
    server: {
      proxy: {
        '/api': { target: DEV_API_PROXY_TARGET, changeOrigin: false },
      },
    },
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      include: ['src/**/*.test.{ts,tsx}'],
      restoreMocks: true,
    },
  };
});
