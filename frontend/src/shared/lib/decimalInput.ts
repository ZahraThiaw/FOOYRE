/**
 * Saisie de montants SANS conversion en `number` (CLAUDE.md §4, ADR‑06) : la valeur reste une
 * chaîne décimale, contrôlée par expression régulière puis envoyée telle quelle au serveur, qui
 * reste seul juge (même règles : décimales de la devise, ≥ 0).
 */
import { DEFAULT_LOCALE } from './formatMoney';

const MAX_INTEGER_DIGITS = 15;

export type DecimalInputError = 'required' | 'format' | 'negative' | 'tooManyDecimals' | 'tooLarge';

/** Saisie normalisée : espaces (séparateurs de milliers) retirés, virgule décimale → point. */
export function normalizeDecimal(raw: string): string {
  return raw.replace(/[\s\u00a0\u202f]/g, '').replace(',', '.');
}

/** Nombre de décimales de la devise (ISO 4217, ex. 0 pour XOF), d'après `Intl`. */
export function currencyFractionDigits(currency: string, locale: string = DEFAULT_LOCALE): number {
  return (
    new Intl.NumberFormat(locale, { style: 'currency', currency }).resolvedOptions()
      .maximumFractionDigits ?? 0
  );
}

/**
 * Valide une saisie décimale positive ou nulle. Renvoie `null` si elle est valide.
 * @param maxFractionDigits décimales significatives autorisées (zéros finaux ignorés)
 */
export function validateDecimal(
  raw: string,
  maxFractionDigits: number,
  required: boolean,
): DecimalInputError | null {
  const value = normalizeDecimal(raw);
  if (value === '') {
    return required ? 'required' : null;
  }
  if (value.startsWith('-')) {
    return 'negative';
  }
  const match = /^(\d+)(?:\.(\d+))?$/.exec(value);
  if (!match) {
    return 'format';
  }
  const integer = (match[1] ?? '').replace(/^0+(?=\d)/, '');
  const fraction = (match[2] ?? '').replace(/0+$/, '');
  if (integer.length > MAX_INTEGER_DIGITS) {
    return 'tooLarge';
  }
  if (fraction.length > maxFractionDigits) {
    return 'tooManyDecimals';
  }
  return null;
}
