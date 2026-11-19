/**
 * Internationalisation (ADR‑13, D16).
 * Français seul au lancement. Les langues nationales (wolof, pulaar, sérère…) s'ajouteront
 * par de nouveaux dossiers `locales/<code>/` après évaluation ; aucune n'est annoncée ici.
 *
 * Options alignées sur les comportements par défaut d'i18next 27 (fallbackLng explicite,
 * returnEmptyString: false) pour faciliter la future migration.
 */
import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';

import apiErrorsFr from './locales/fr/apiErrors.json';
import commonFr from './locales/fr/common.json';
import sessionFr from './locales/fr/session.json';

export const SUPPORTED_LOCALES = ['fr'] as const;
export type SupportedLocale = (typeof SUPPORTED_LOCALES)[number];
export const DEFAULT_LOCALE: SupportedLocale = 'fr';
export const DEFAULT_NAMESPACE = 'common';
/** Espaces de noms : `common` (interface), `apiErrors` (erreurs API, R7), `session` (R8). */
export const NAMESPACES = ['common', 'apiErrors', 'session'] as const;

export const resources = {
  fr: { common: commonFr, apiErrors: apiErrorsFr, session: sessionFr },
} as const;

void i18n.use(initReactI18next).init({
  resources,
  lng: DEFAULT_LOCALE,
  fallbackLng: DEFAULT_LOCALE,
  supportedLngs: [...SUPPORTED_LOCALES],
  defaultNS: DEFAULT_NAMESPACE,
  ns: [...NAMESPACES],
  returnEmptyString: false,
  interpolation: { escapeValue: false }, // React échappe déjà le contenu.
  react: { useSuspense: false },
});

export default i18n;
