// @vitest-environment node
/// <reference types="node" />
/**
 * R5 — Garde de compatibilité navigateurs (cible : Chrome 87, Edge 88, Firefox 78, Safari/iOS 14).
 *
 * `lib` est limité à ES2020 dans tsconfig.app.json : TypeScript refuse alors les API
 * JavaScript plus récentes que Vite ne fournit pas aux anciens navigateurs (aucun polyfill).
 * Ce test compile en mémoire de petits extraits avec la VRAIE configuration du projet.
 *
 * Limite assumée : la bibliothèque `DOM` de TypeScript n'est pas versionnée ; des API Web
 * récentes (ex. `structuredClone`) restent acceptées par le compilateur. Elles relèvent des
 * tests sur appareils réels (docs/architecture.md, ADR‑12).
 */
import { fileURLToPath } from 'node:url';

import ts from 'typescript';
import { describe, expect, it } from 'vitest';

const projectRoot = fileURLToPath(new URL('../../', import.meta.url));

function loadAppCompilerOptions(): ts.CompilerOptions {
  const configPath = ts.findConfigFile(
    projectRoot,
    (path) => ts.sys.fileExists(path),
    'tsconfig.app.json',
  );
  if (!configPath) {
    throw new Error('tsconfig.app.json introuvable');
  }
  const config: unknown = ts.readConfigFile(configPath, (path) => ts.sys.readFile(path)).config;
  const parsed = ts.parseJsonConfigFileContent(config, ts.sys, projectRoot);
  // Uniquement les options de compilation : les fichiers du projet ne sont pas chargés.
  return { ...parsed.options, types: [], noEmit: true };
}

/** Diagnostics TypeScript d'un extrait compilé seul, avec les options du projet. */
function diagnosticsFor(code: string): string[] {
  const options = loadAppCompilerOptions();
  // TypeScript manipule des chemins avec « / », y compris sous Windows.
  const normalize = (path: string) => path.replace(/\\/g, '/');
  const fileName = normalize(`${projectRoot}src/__compat_probe__.ts`);
  const host = ts.createCompilerHost(options);
  const originalGetSourceFile = host.getSourceFile.bind(host);
  host.getSourceFile = (name, languageVersion, ...rest) =>
    normalize(name) === fileName
      ? ts.createSourceFile(name, code, languageVersion)
      : originalGetSourceFile(name, languageVersion, ...rest);
  const originalFileExists = host.fileExists.bind(host);
  host.fileExists = (name) => normalize(name) === fileName || originalFileExists(name);

  const program = ts.createProgram([fileName], options, host);
  // Diagnostics de l'extrait ET diagnostics globaux (ex. fichier introuvable) : un banc de
  // test défaillant ne peut donc pas faire passer silencieusement les cas « accepte ».
  return ts
    .getPreEmitDiagnostics(program)
    .filter((diagnostic) => !diagnostic.file || normalize(diagnostic.file.fileName) === fileName)
    .map((diagnostic) => ts.flattenDiagnosticMessageText(diagnostic.messageText, '\n'));
}

describe('compatibilité navigateurs — lib TypeScript', () => {
  it('la configuration de l’application limite lib à ES2020 (+ DOM)', () => {
    const lib = (loadAppCompilerOptions().lib ?? []).map((entry) => entry.toLowerCase());
    expect(lib).toEqual(['lib.es2020.d.ts', 'lib.dom.d.ts', 'lib.dom.iterable.d.ts']);
  });

  it('témoin : le banc de compilation détecte bien une erreur de type', () => {
    expect(diagnosticsFor('export const x: number = "texte";')).not.toEqual([]);
    expect(diagnosticsFor('export const x: number = 1;')).toEqual([]);
  }, 30_000);

  it.each([
    ['Array.prototype.at (ES2022 — Safari 15.4)', 'export const x = [1, 2].at(-1);'],
    ['Object.hasOwn (ES2022 — Safari 15.4)', 'export const x = Object.hasOwn({}, "a");'],
    ['Promise.any (ES2021 — Firefox 79)', 'export const x = Promise.any([Promise.resolve(1)]);'],
    ['String.prototype.replaceAll (ES2021)', 'export const x = "a-b".replaceAll("-", "+");'],
    ['WeakRef (ES2021 — Safari 14.1, Firefox 79)', 'export const x = new WeakRef({});'],
    ['Array.prototype.findLast (ES2023)', 'export const x = [1, 2].findLast((n) => n > 0);'],
  ])('refuse %s', (_label, code) => {
    expect(diagnosticsFor(code)).not.toEqual([]);
  });

  it.each([
    ['Array.prototype.flatMap (ES2019)', 'export const x = [[1], [2]].flatMap((n) => n);'],
    ['Promise.allSettled (ES2020)', 'export const x = Promise.allSettled([Promise.resolve(1)]);'],
    ['globalThis (ES2020)', 'export const x = globalThis.location;'],
    ['String.prototype.padEnd (ES2017)', 'export const x = "1".padEnd(3, "0");'],
    [
      'Intl.NumberFormat#formatToParts',
      'export const x = new Intl.NumberFormat("fr-SN").formatToParts(1);',
    ],
  ])('accepte %s', (_label, code) => {
    expect(diagnosticsFor(code)).toEqual([]);
  });
});
