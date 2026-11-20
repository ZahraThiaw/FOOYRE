/**
 * Contrôle post‑build : le dossier dist/ ne doit contenir AUCUN élément de données fictives
 * (service worker MSW, module virtual:msw, handlers, fixtures) ni de secret évident.
 * Échoue (code de sortie 1) en cas de détection. Lancé automatiquement par `npm run build`.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const distDir = fileURLToPath(new URL('../dist', import.meta.url));

const FORBIDDEN_FILE_NAMES = [/mockServiceWorker/i];
const FORBIDDEN_CONTENT = [
  { label: 'service worker MSW', pattern: /mockServiceWorker/ },
  { label: 'module virtual:msw', pattern: /virtual:msw/ },
  { label: 'API MSW (setupWorker / HttpResponse)', pattern: /setupWorker|HttpResponse/ },
  { label: 'paquet @mswjs', pattern: /@mswjs/ },
  { label: 'marqueur de données fictives', pattern: /FOOYRE_DONNEES_FICTIVES/ },
  { label: 'données fictives /me', pattern: /Utilisatrice de démonstration|example\.invalid/ },
  { label: 'clé Anthropic', pattern: /sk-ant-/ },
  // URL locale avec port ou chemin (ex. API de développement http://127.0.0.1:8080/…) : interdite
  // en production. Les bibliothèques (React Router, Axios) utilisent « http://localhost » nu comme
  // base de repli pour analyser des URL : ce motif ne la vise pas.
  { label: 'URL locale (localhost)', pattern: /https?:\/\/(?:localhost|127\.0\.0\.1|0\.0\.0\.0|\[::1\])(?::\d+|\/)/ },
];
const TEXT_EXTENSIONS = /\.(html|js|mjs|css|json|map|txt|svg|webmanifest)$/i;

function listFiles(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? listFiles(path) : [path];
  });
}

let files;
try {
  files = listFiles(distDir);
} catch {
  console.error('[verify-build] Dossier dist/ introuvable : lancer « vite build » au préalable.');
  process.exit(1);
}

const problems = [];
for (const file of files) {
  const rel = relative(distDir, file);
  for (const pattern of FORBIDDEN_FILE_NAMES) {
    if (pattern.test(rel)) problems.push(`${rel} : fichier interdit`);
  }
  if (TEXT_EXTENSIONS.test(file)) {
    const content = readFileSync(file, 'utf8');
    for (const { label, pattern } of FORBIDDEN_CONTENT) {
      if (pattern.test(content)) problems.push(`${rel} : ${label}`);
    }
  }
}

if (problems.length > 0) {
  console.error('[verify-build] ÉCHEC — éléments interdits trouvés dans dist/ :');
  for (const problem of problems) console.error(`  - ${problem}`);
  process.exit(1);
}

console.log(
  `[verify-build] OK — ${files.length} fichier(s) contrôlé(s), aucune donnée fictive ni secret détecté.`,
);
