// @vitest-environment node
/// <reference types="node" />
/**
 * R6 — Couverture réelle des règles « aucun texte d'interface en dur » (ADR‑13).
 * Exécute la VRAIE configuration ESLint du projet (eslint.config.js) sur des extraits de code,
 * en les attribuant à des fichiers existants de l'application (requis par l'analyse typée).
 * Vérifie ce qui DOIT être signalé et ce qui ne doit PAS l'être (faux positifs).
 */
import { fileURLToPath } from 'node:url';

import { ESLint } from 'eslint';
import { beforeAll, describe, expect, it } from 'vitest';

const projectRoot = fileURLToPath(new URL('../../', import.meta.url));
const TSX_FILE = `${projectRoot}src/features/home/pages/HomePage.tsx`;
const TS_FILE = `${projectRoot}src/app/mockStartupFailure.ts`;
const I18N_RULES = new Set(['i18next/no-literal-string', 'no-restricted-syntax']);

let eslint: ESLint;

beforeAll(() => {
  eslint = new ESLint({ cwd: projectRoot });
});

/** Règles « texte en dur » déclenchées par l'extrait (les autres règles sont ignorées ici). */
async function i18nViolations(code: string, filePath: string): Promise<string[]> {
  const [result] = await eslint.lintText(code, { filePath });
  return (result?.messages ?? [])
    .filter((message) => message.ruleId !== null && I18N_RULES.has(message.ruleId))
    .map((message) => `${message.ruleId ?? ''}: ${message.message}`);
}

describe('i18n — JSX (.tsx) : textes signalés', { timeout: 60_000 }, () => {
  it.each([
    ['texte JSX', 'export const Carte = () => <p>Bonjour</p>;'],
    ['attribut placeholder', 'export const Carte = () => <input placeholder="Votre nom" />;'],
    ['attribut alt', 'export const Carte = () => <img alt="Logo FOOYRE" src="/logo.png" />;'],
    [
      'attribut aria-label',
      'export const Carte = () => <button type="button" aria-label="Fermer" />;',
    ],
    [
      'attribut title d’un composant',
      'const Icon = (p: { title: string }) => p.title; export const Carte = () => <Icon title="Paramètres" />;',
    ],
    [
      'attribut label d’un composant',
      'const Field = (p: { label: string }) => p.label; export const Carte = () => <Field label="Montant" />;',
    ],
    ['chaîne dans une expression JSX', "export const Carte = () => <p>{'Erreur réseau'}</p>;"],
    [
      'chaînes d’un ternaire dans le JSX',
      "export const Carte = ({ ok }: { ok: boolean }) => <p>{ok ? 'Payée' : 'Impayée'}</p>;",
    ],
  ])('%s', async (_label, code) => {
    const violations = await i18nViolations(code, TSX_FILE);
    expect(violations.length, violations.join('\n')).toBeGreaterThan(0);
    expect(violations.every((v) => v.startsWith('i18next/no-literal-string'))).toBe(true);
  });
});

describe('i18n — JSX (.tsx) : valeurs techniques non signalées', { timeout: 60_000 }, () => {
  it.each([
    [
      'className, role, id, data-*',
      'export const Carte = () => <div className="flex items-center" role="status" id="main" data-testid="bandeau" />;',
    ],
    [
      'liens (href, to)',
      'const Link = (p: { to: string }) => p.to; export const Carte = () => <><a href="#main" /><Link to="/ventes" /></>;',
    ],
    [
      'aria-hidden sur une icône',
      'const Icon = (p: { "aria-hidden": string }) => null; export const Carte = () => <Icon aria-hidden="true" />;',
    ],
    [
      'attributs techniques d’un champ',
      'export const Carte = () => <input type="email" name="email" autoComplete="email" inputMode="email" />;',
    ],
    [
      'texte traduit',
      "declare const t: (key: string) => string; export const Carte = () => <p>{t('home.title')}</p>;",
    ],
    [
      'gestionnaire d’événement avec une route',
      'declare const navigate: (to: string) => void; export const Carte = () => <button type="button" onClick={() => navigate(\'/ventes\')} />;',
    ],
    [
      'ponctuation seule',
      "export const Carte = ({ a, b }: { a: string; b: string }) => <p>{a}{' : '}{b}</p>;",
    ],
  ])('%s', async (_label, code) => {
    expect(await i18nViolations(code, TSX_FILE)).toEqual([]);
  });
});

describe('i18n — hors JSX (.ts) : textes écrits dans le DOM signalés', { timeout: 60_000 }, () => {
  it.each([
    ['textContent', "export const f = (el: HTMLElement) => { el.textContent = 'Bonjour'; };"],
    ['title (gabarit)', 'export const f = (el: HTMLElement) => { el.title = `Titre`; };'],
    ['document.title', "export const f = () => { document.title = 'FOOYRE – Ventes'; };"],
    [
      'setAttribute(aria-label)',
      "export const f = (el: HTMLElement) => { el.setAttribute('aria-label', 'Fermer'); };",
    ],
    ['alert', "export const f = () => { alert('Attention'); };"],
    ['window.confirm', "export const f = () => window.confirm('Confirmer ?');"],
  ])('%s', async (_label, code) => {
    const violations = await i18nViolations(code, TS_FILE);
    expect(violations.length, violations.join('\n')).toBeGreaterThan(0);
    expect(violations.every((v) => v.startsWith('no-restricted-syntax'))).toBe(true);
  });
});

describe('i18n — hors JSX (.ts) : valeurs techniques non signalées', { timeout: 60_000 }, () => {
  it.each([
    [
      'texte traduit dans le DOM',
      "declare const i18n: { t: (k: string) => string }; export const f = (el: HTMLElement) => { el.textContent = i18n.t('errors.mockStartup.title'); };",
    ],
    [
      'classes CSS et rôle',
      "export const f = (el: HTMLElement) => { el.className = 'p-4'; el.setAttribute('role', 'alert'); };",
    ],
    [
      'message d’erreur technique',
      "export const f = () => { throw new Error('Montant invalide'); };",
    ],
    [
      'journal de développement',
      "export const f = () => { console.error('[FOOYRE] échec du démarrage'); };",
    ],
    [
      'clés, routes et codes',
      "export const keys = ['session', 'me']; export const route = '/ventes'; export const code = 'MOCK_NOT_IMPLEMENTED';",
    ],
  ])('%s', async (_label, code) => {
    expect(await i18nViolations(code, TS_FILE)).toEqual([]);
  });
});

// ─── Lot 2.1 ─────────────────────────────────────────────────────────────────────────────────

describe('i18n — gabarits JSX (should-validate-template)', { timeout: 60_000 }, () => {
  it.each([
    ['gabarit dans le texte JSX', 'export const Carte = () => <p>{`Bonjour`}</p>;'],
    [
      'gabarit dans un attribut visible',
      'export const Carte = () => <input placeholder={`Votre nom`} />;',
    ],
    [
      'gabarit mêlant valeur et texte',
      'export const Carte = ({ n }: { n: number }) => <p>{`${n} articles`}</p>;',
    ],
  ])('signalé : %s', async (_label, code) => {
    const violations = await i18nViolations(code, TSX_FILE);
    expect(violations.length, violations.join('\n')).toBeGreaterThan(0);
    expect(violations.every((v) => v.startsWith('i18next/no-literal-string'))).toBe(true);
  });

  it.each([
    [
      'gabarit dans className',
      'export const Carte = ({ a }: { a: boolean }) => <div className={`p-4 ${a ? "x" : "y"}`} />;',
    ],
    [
      'gabarits dans key et to',
      'const Link = (p: { to: string }) => p.to; export const Carte = ({ id }: { id: string }) => <Link key={`ligne-${id}`} to={`/ventes/${id}`} />;',
    ],
    [
      'gabarit de ponctuation seule',
      'export const Carte = ({ a, b }: { a: string; b: string }) => <p>{`${a} : ${b}`}</p>;',
    ],
    [
      'gabarit de clé passé à t()',
      'declare const t: (k: string) => string; export const Carte = ({ id }: { id: string }) => <p>{t(`statut.${id}`)}</p>;',
    ],
  ])('non signalé : %s', async (_label, code) => {
    expect(await i18nViolations(code, TSX_FILE)).toEqual([]);
  });
});

describe('i18n — DOM : formes syntaxiques équivalentes (lot 2.1)', { timeout: 60_000 }, () => {
  it.each([
    [
      'propriété entre crochets',
      "export const f = (el: HTMLElement) => { el['textContent'] = 'Bonjour'; };",
    ],
    [
      'affectation conditionnelle',
      "export const f = (el: HTMLElement, ok: boolean) => { el.textContent = ok ? 'Oui' : 'Non'; };",
    ],
    [
      'valeur par défaut (||)',
      "export const f = (el: HTMLElement, m: string) => { el.title = m || 'Aide'; };",
    ],
    [
      'concaténation',
      "export const f = (el: HTMLElement, n: string) => { el.textContent = 'Bonjour ' + n; };",
    ],
    [
      'concaténation à deux niveaux',
      "export const f = (el: HTMLElement, n: string) => { el.textContent = n + ' et ' + n; };",
    ],
    ['createTextNode', "export const f = () => document.createTextNode('Bonjour');"],
    [
      'insertAdjacentText',
      "export const f = (el: HTMLElement) => { el.insertAdjacentText('beforeend', 'Bonjour'); };",
    ],
    ['append (chaîne)', "export const f = (el: HTMLElement) => { el.append('Bonjour'); };"],
    ['prepend (gabarit)', 'export const f = (el: HTMLElement) => { el.prepend(`Bonjour`); };'],
    [
      'setAttribute entre crochets',
      "export const f = (el: HTMLElement) => { el['setAttribute']('title', 'Aide'); };",
    ],
    [
      'setAttribute avec nom en gabarit',
      "export const f = (el: HTMLElement) => { el.setAttribute(`aria-label`, 'Fermer'); };",
    ],
    [
      'setAttributeNS',
      "export const f = (el: HTMLElement) => { el.setAttributeNS(null, 'title', 'Aide'); };",
    ],
  ])('signalé : %s', async (_label, code) => {
    const violations = await i18nViolations(code, TS_FILE);
    expect(violations.length, violations.join('\n')).toBeGreaterThan(0);
    expect(violations.every((v) => v.startsWith('no-restricted-syntax'))).toBe(true);
  });

  it.each([
    ['vidage d’un texte', "export const f = (el: HTMLElement) => { el.textContent = ''; };"],
    [
      'ternaire de textes traduits (chaîne technique dans la condition)',
      "declare const t: (k: string) => string; export const f = (el: HTMLElement, m: string) => { el.textContent = m === 'edit' ? t('a.b') : t('a.c'); };",
    ],
    ['append d’un nœud', 'export const f = (el: HTMLElement, n: Node) => { el.append(n); };'],
    ['URLSearchParams.set', "export const f = (p: URLSearchParams) => { p.set('page', '0'); };"],
    [
      'setAttribute technique (notation pointée et crochets)',
      "export const f = (el: HTMLElement) => { el.setAttribute('role', 'alert'); el['setAttribute']('data-etat', 'ouvert'); };",
    ],
    [
      'setAttributeNS technique',
      "export const f = (el: Element) => { el.setAttributeNS('http://www.w3.org/1999/xlink', 'xlink:href', '#icone'); };",
    ],
    [
      'createTextNode vide ou ponctuation',
      "export const f = () => [document.createTextNode(''), document.createTextNode(' : ')];",
    ],
  ])('non signalé : %s', async (_label, code) => {
    expect(await i18nViolations(code, TS_FILE)).toEqual([]);
  });

  it('faux positif connu et documenté : FormData.append avec un nom de champ', async () => {
    const violations = await i18nViolations(
      "export const f = (d: FormData, v: string) => { d.append('nom', v); };",
      TS_FILE,
    );
    expect(violations.length).toBeGreaterThan(0);
  });
});
