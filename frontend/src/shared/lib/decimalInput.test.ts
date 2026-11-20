import { describe, expect, it } from 'vitest';

import { currencyFractionDigits, normalizeDecimal, validateDecimal } from './decimalInput';

describe('saisie de montants (sans conversion en number)', () => {
  it('normalise espaces de milliers et virgule décimale', () => {
    expect(normalizeDecimal(' 1 500,50 ')).toBe('1500.50');
    expect(normalizeDecimal('1 500')).toBe('1500');
  });

  it('XOF : aucune décimale significative autorisée', () => {
    const xof = currencyFractionDigits('XOF');
    expect(xof).toBe(0);
    expect(validateDecimal('3500', xof, true)).toBeNull();
    expect(validateDecimal('3500,00', xof, true)).toBeNull();
    expect(validateDecimal('3500,5', xof, true)).toBe('tooManyDecimals');
  });

  it('coût unitaire : jusqu’à 4 décimales', () => {
    expect(validateDecimal('125.1234', 4, false)).toBeNull();
    expect(validateDecimal('125.12345', 4, false)).toBe('tooManyDecimals');
  });

  it('refuse vide (si obligatoire), négatif, texte et montants démesurés', () => {
    expect(validateDecimal('', 0, true)).toBe('required');
    expect(validateDecimal('', 4, false)).toBeNull();
    expect(validateDecimal('-1', 0, true)).toBe('negative');
    expect(validateDecimal('12abc', 0, true)).toBe('format');
    expect(validateDecimal('1e5', 0, true)).toBe('format');
    expect(validateDecimal('1234567890123456', 0, true)).toBe('tooLarge');
    // Précision préservée au‑delà de Number.MAX_SAFE_INTEGER (aucune conversion en number).
    expect(validateDecimal('999999999999999', 0, true)).toBeNull();
  });
});
