import { describe, expect, it } from 'vitest';

import i18n, { DEFAULT_LOCALE, SUPPORTED_LOCALES } from './index';
import commonFr from './locales/fr/common.json';

function collectKeys(value: unknown, prefix = ''): string[] {
  if (typeof value !== 'object' || value === null) {
    return [prefix];
  }
  return Object.entries(value).flatMap(([key, child]) =>
    collectKeys(child, prefix ? `${prefix}.${key}` : key),
  );
}

describe('i18n', () => {
  it('démarre en français, seule langue prise en charge au lancement', () => {
    expect(i18n.language).toBe(DEFAULT_LOCALE);
    expect(SUPPORTED_LOCALES).toEqual(['fr']);
  });

  it('charge toutes les clés du catalogue français (aucune valeur vide)', () => {
    for (const key of collectKeys(commonFr)) {
      const value: unknown = i18n.getResource(DEFAULT_LOCALE, 'common', key);
      expect(typeof value, key).toBe('string');
      expect(value, key).not.toBe('');
      expect(value, key).not.toBe(key);
    }
  });

  it('interpole les paramètres', () => {
    expect(i18n.t('home.session.signedInAs', { name: 'Awa' })).toBe('Connecté·e en tant que Awa');
  });
});
