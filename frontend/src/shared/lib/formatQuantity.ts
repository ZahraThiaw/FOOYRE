/**
 * Formatage d'une quantité reçue en CHAÎNE décimale (« 12.5 » → « 12,5 », « 1500 » → « 1 500 »),
 * sans conversion en `number` : `Intl` ne fournit que les séparateurs de la locale.
 */
import { DEFAULT_LOCALE } from './formatMoney';

const DECIMAL = /^(-)?(\d+)(?:\.(\d+))?$/;

export function formatQuantity(quantity: string, locale: string = DEFAULT_LOCALE): string {
  const match = DECIMAL.exec(quantity.trim());
  if (!match) {
    return quantity;
  }
  const [, minus = '', integer = '0', fraction = ''] = match;
  const parts = new Intl.NumberFormat(locale, { minimumFractionDigits: 1 }).formatToParts(1000.5);
  const group = parts.find((part) => part.type === 'group')?.value ?? '';
  const decimal = parts.find((part) => part.type === 'decimal')?.value ?? ',';
  const grouped = integer.replace(/\B(?=(\d{3})+(?!\d))/g, group);
  const trimmedFraction = fraction.replace(/0+$/, '');
  return `${minus}${grouped}${trimmedFraction ? decimal + trimmedFraction : ''}`;
}
