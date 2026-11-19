import { describe, expect, it } from 'vitest';

import { formatMoney } from './formatMoney';

const intlXof = (value: number) =>
  new Intl.NumberFormat('fr-SN', { style: 'currency', currency: 'XOF' }).format(value);

describe('formatMoney', () => {
  it('formate un montant XOF comme Intl (séparateurs et devise de la locale)', () => {
    expect(formatMoney('14500.00', 'XOF')).toBe(intlXof(14500));
    expect(formatMoney('0', 'XOF')).toBe(intlXof(0));
    expect(formatMoney('1234567', 'XOF')).toBe(intlXof(1234567));
  });

  it('gère les montants négatifs', () => {
    expect(formatMoney('-9500.00', 'XOF')).toBe(intlXof(-9500));
    expect(formatMoney('-0.00', 'XOF')).toBe(intlXof(0));
  });

  it('respecte l’échelle de la devise (2 décimales pour EUR)', () => {
    const intlEur = new Intl.NumberFormat('fr-SN', { style: 'currency', currency: 'EUR' });
    expect(formatMoney('1234.5', 'EUR')).toBe(intlEur.format(1234.5));
  });

  it('ne passe jamais par un nombre flottant : précision conservée au‑delà de 2^53', () => {
    const formatted = formatMoney('12345678901234567', 'XOF');
    expect(formatted.replace(/\D/g, '')).toBe('12345678901234567');
  });

  it('n’arrondit pas : des décimales non nulles au‑delà de l’échelle sont conservées', () => {
    expect(formatMoney('10.50', 'XOF').replace(/[^\d,]/g, '')).toBe('10,50');
  });

  it('refuse une valeur qui n’est pas une chaîne décimale', () => {
    expect(() => formatMoney('12abc', 'XOF')).toThrow(RangeError);
    expect(() => formatMoney('1e5', 'XOF')).toThrow(RangeError);
    expect(() => formatMoney('', 'XOF')).toThrow(RangeError);
  });
});
