import js from '@eslint/js';
import i18next from 'eslint-plugin-i18next';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import globals from 'globals';
import tseslint from 'typescript-eslint';

/** Interdit tout stockage persistant de jetons ou de données sensibles (ADR‑04, ADR‑14). */
const storageRestrictions = {
  'no-restricted-globals': [
    'error',
    {
      name: 'localStorage',
      message: 'Interdit : aucun jeton ni donnée sensible dans localStorage (ADR‑04).',
    },
    {
      name: 'sessionStorage',
      message: 'Interdit : aucun jeton ni donnée sensible dans sessionStorage (ADR‑04).',
    },
  ],
  'no-restricted-properties': [
    'error',
    { object: 'window', property: 'localStorage', message: 'Interdit (ADR‑04).' },
    { object: 'window', property: 'sessionStorage', message: 'Interdit (ADR‑04).' },
    { object: 'globalThis', property: 'localStorage', message: 'Interdit (ADR‑04).' },
    { object: 'globalThis', property: 'sessionStorage', message: 'Interdit (ADR‑04).' },
  ],
};

/**
 * Attributs JSX dont la valeur est lue ou affichée par l'utilisateur (ou par un lecteur
 * d'écran) : une chaîne littérale y est interdite. Les autres attributs (className, role,
 * to, href, type, id, key, aria-hidden, data-*…) sont techniques et ne sont pas contrôlés.
 */
const USER_VISIBLE_JSX_ATTRIBUTES = [
  'alt',
  'aria-label',
  'aria-description',
  'aria-placeholder',
  'aria-roledescription',
  'aria-valuetext',
  'label',
  'placeholder',
  'title',
];

/** Propriétés DOM qui affichent du texte (utilisées hors React, ex. écran de démarrage). */
const USER_VISIBLE_DOM_PROPERTIES =
  'textContent|innerText|innerHTML|outerHTML|placeholder|title|alt|label|ariaLabel|ariaDescription|ariaPlaceholder|ariaRoleDescription|ariaValueText';

// ─── Briques des sélecteurs (syntaxe esquery utilisée par `no-restricted-syntax`) ───────────
/** Une chaîne « de texte » contient au moins une lettre (ponctuation, espaces et '' exclus). */
const HAS_LETTER = '/[A-Za-zÀ-ÖØ-öø-ÿ]/';
const TEXT_LITERAL = `Literal[value=${HAS_LETTER}]`;
const TEXT_TEMPLATE_PART = `TemplateLiteral > TemplateElement[value.raw=${HAS_LETTER}]`;
const VISIBLE_ATTRIBUTES = USER_VISIBLE_JSX_ATTRIBUTES.join('|');

/** Affectation à une propriété DOM visible : `el.textContent = …` ou `el['textContent'] = …`. */
const VISIBLE_PROPERTY_ASSIGNMENT = `AssignmentExpression[left.type='MemberExpression']:matches([left.property.name=/^(${USER_VISIBLE_DOM_PROPERTIES})$/], [left.property.value=/^(${USER_VISIBLE_DOM_PROPERTIES})$/])`;

/** Appel de méthode, en notation pointée ou entre crochets : `el.m(…)` ou `el['m'](…)`. */
const methodCall = (names) =>
  `CallExpression:matches([callee.property.name=/^(${names})$/], [callee.property.value=/^(${names})$/])`;

/** Argument n° `index` contenant du texte : chaîne avec lettre, ou gabarit. */
const textArgument = (index) =>
  `:matches([arguments.${index}.value=${HAS_LETTER}], [arguments.${index}.type='TemplateLiteral'])`;

/** Argument n° `index` désignant un attribut visible : `'title'` ou `` `title` ``. */
const visibleAttributeArgument = (index) =>
  `:matches([arguments.${index}.value=/^(${VISIBLE_ATTRIBUTES})$/], [arguments.${index}.quasis.0.value.raw=/^(${VISIBLE_ATTRIBUTES})$/])`;

const I18N_MESSAGE =
  'Texte d’interface en dur : utiliser une clé de traduction (i18n.t / useTranslation), ADR‑13.';

/**
 * Textes d'interface hors JSX (fichiers .ts et .tsx) : interdit d'écrire du texte en dur dans
 * une propriété ou un attribut DOM visible, de créer un nœud texte en dur, ou d'utiliser
 * alert/confirm/prompt. Seules les formes syntaxiques ci‑dessous sont détectées (pas d'analyse
 * des variables, alias ou appels indirects) : voir CLAUDE.md §11.
 * Les messages techniques (logs, `new Error(...)`, codes, clés, routes) ne sont pas visés.
 */
const userVisibleDomTextRestrictions = {
  'no-restricted-syntax': [
    'error',
    {
      // Valeur directe, gabarit, ternaire / `||` / `??`, concaténation (texte suivi d'au plus 2 `+`).
      selector: [
        `${VISIBLE_PROPERTY_ASSIGNMENT} > ${TEXT_LITERAL}`,
        `${VISIBLE_PROPERTY_ASSIGNMENT} > ${TEXT_TEMPLATE_PART}`,
        `${VISIBLE_PROPERTY_ASSIGNMENT} > :matches(ConditionalExpression, LogicalExpression) > ${TEXT_LITERAL}`,
        `${VISIBLE_PROPERTY_ASSIGNMENT} > :matches(ConditionalExpression, LogicalExpression) > ${TEXT_TEMPLATE_PART}`,
        `${VISIBLE_PROPERTY_ASSIGNMENT} > BinaryExpression[operator='+'] > ${TEXT_LITERAL}`,
        `${VISIBLE_PROPERTY_ASSIGNMENT} > BinaryExpression[operator='+'] > BinaryExpression[operator='+'] > ${TEXT_LITERAL}`,
      ].join(', '),
      message: I18N_MESSAGE,
    },
    {
      // setAttribute / setAttributeNS, notation pointée ou entre crochets, nom en chaîne ou gabarit.
      selector: [
        `${methodCall('setAttribute')}${visibleAttributeArgument(0)}${textArgument(1)}`,
        `${methodCall('setAttributeNS')}${visibleAttributeArgument(1)}${textArgument(2)}`,
      ].join(', '),
      message:
        'Attribut visible en dur : utiliser une clé de traduction (i18n.t / useTranslation), ADR‑13.',
    },
    {
      // Création de texte dans le DOM. Faux positif connu : FormData / URLSearchParams / Headers
      // `.append('nom', …)` → utiliser `.set(…)`, ou justifier une exception.
      selector: [
        `${methodCall('createTextNode')}${textArgument(0)}`,
        `${methodCall('insertAdjacentText')}${textArgument(1)}`,
        `${methodCall('append|prepend|replaceChildren')} > ${TEXT_LITERAL}`,
        `${methodCall('append|prepend|replaceChildren')} > ${TEXT_TEMPLATE_PART}`,
      ].join(', '),
      message: I18N_MESSAGE,
    },
    {
      selector: 'CallExpression[callee.name=/^(alert|confirm|prompt)$/]',
      message: 'alert/confirm/prompt interdits : utiliser un composant d’interface traduit.',
    },
    {
      selector:
        'CallExpression[callee.object.name=/^(window|globalThis)$/][callee.property.name=/^(alert|confirm|prompt)$/]',
      message: 'alert/confirm/prompt interdits : utiliser un composant d’interface traduit.',
    },
  ],
};

export default tseslint.config(
  { ignores: ['dist', 'coverage', 'node_modules'] },

  // Code applicatif (navigateur)
  {
    files: ['src/**/*.{ts,tsx}'],
    extends: [js.configs.recommended, ...tseslint.configs.recommendedTypeChecked],
    languageOptions: {
      ecmaVersion: 2022,
      globals: globals.browser,
      parserOptions: { projectService: true, tsconfigRootDir: import.meta.dirname },
    },
    plugins: { 'react-hooks': reactHooks, 'react-refresh': reactRefresh },
    rules: {
      ...reactHooks.configs.flat.recommended.rules,
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
      ...storageRestrictions,
    },
  },

  // Aucun texte d'interface codé en dur (ADR‑13) — hors tests et données fictives.
  // 1. JSX (.tsx) : texte, chaînes et gabarits dans les expressions JSX ({'…'}, {`…`}, ternaires) et
  //    attributs visibles par l'utilisateur.
  {
    files: ['src/**/*.tsx'],
    ignores: ['src/**/*.test.tsx', 'src/mocks/**', 'src/test/**'],
    plugins: { i18next },
    rules: {
      'i18next/no-literal-string': [
        'error',
        {
          mode: 'jsx-only',
          'jsx-attributes': { include: USER_VISIBLE_JSX_ATTRIBUTES },
          // Gabarits (`…`) dans le JSX : contrôlés avec les mêmes exclusions que les chaînes.
          'should-validate-template': true,
        },
      ],
    },
  },
  // 2. Hors JSX (.ts et .tsx) : texte écrit directement dans le DOM, alert/confirm/prompt.
  {
    files: ['src/**/*.{ts,tsx}'],
    ignores: ['src/**/*.test.{ts,tsx}', 'src/mocks/**', 'src/test/**'],
    rules: userVisibleDomTextRestrictions,
  },

  // Fichiers de configuration et scripts (Node)
  {
    files: ['*.{js,ts}', 'scripts/**/*.mjs'],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    languageOptions: { globals: globals.node },
  },
);
