import { describe, expect, it } from 'vitest';

import { addDecimal, compareDecimal, subtractDecimal } from './decimalMath';
import { formatQuantity } from './formatQuantity';
import { newIdempotencyKey } from './idempotencyKey';

describe('decimalMath (aperçu exact, sans flottant)', () => {
  it('additionne et soustrait exactement', () => {
    expect(addDecimal('0.1', '0.2')).toBe('0.3'); // 0.1 + 0.2 = 0.30000000000000004 en flottant
    expect(addDecimal('12.5', '0.75')).toBe('13.25');
    expect(subtractDecimal('7', '9')).toBe('-2');
    expect(subtractDecimal('1.001', '1.001')).toBe('0');
    expect(subtractDecimal('-0.5', '0.25')).toBe('-0.75');
    expect(addDecimal('999999999999999.999', '0.001')).toBe('1000000000000000');
  });

  it('compare', () => {
    expect(compareDecimal('-0.001', '0')).toBe(-1);
    expect(compareDecimal('2.50', '2.5')).toBe(0);
    expect(compareDecimal('10', '9.999')).toBe(1);
  });

  it('refuse une valeur invalide ou trop précise', () => {
    expect(() => addDecimal('abc', '1')).toThrow(RangeError);
    expect(() => addDecimal('1.0001', '1')).toThrow(RangeError);
    expect(addDecimal('1.5000', '1')).toBe('2.5'); // zéros finaux tolérés
  });
});

describe('formatQuantity', () => {
  it('applique les séparateurs fr-SN sans conversion en nombre', () => {
    expect(formatQuantity('12.5')).toBe('12,5');
    expect(formatQuantity('7.500')).toBe('7,5');
    expect(formatQuantity('-2')).toBe('-2');
    expect(formatQuantity('1500')).toMatch(/^1[\s\u00a0\u202f]500$/);
    expect(formatQuantity('pas un nombre')).toBe('pas un nombre');
  });
});

describe('newIdempotencyKey', () => {
  it('génère des UUID v4 distincts', () => {
    const keys = new Set(Array.from({ length: 50 }, () => newIdempotencyKey()));
    expect(keys.size).toBe(50);
    keys.forEach((key) => expect(key).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/));
  });
});
