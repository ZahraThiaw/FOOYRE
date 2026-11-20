/**
 * Arithmétique décimale EXACTE pour l'aperçu des quantités (aucun nombre flottant JavaScript) :
 * les chaînes décimales sont converties en entiers `BigInt` à échelle fixe, additionnées ou
 * soustraites, puis reconverties en chaîne. Le serveur reste seul juge du résultat enregistré.
 */
const SCALE = 3;
const FACTOR = 10n ** BigInt(SCALE);
const DECIMAL = /^(-)?(\d+)(?:\.(\d+))?$/;

function toScaled(value: string): bigint {
  const match = DECIMAL.exec(value.trim());
  if (!match) {
    throw new RangeError(`Valeur décimale invalide : « ${value} »`);
  }
  const [, minus, integer = '0', fraction = ''] = match;
  if (fraction.replace(/0+$/, '').length > SCALE) {
    throw new RangeError(`Trop de décimales : « ${value} »`);
  }
  const scaled = BigInt(integer) * FACTOR + BigInt(fraction.padEnd(SCALE, '0').slice(0, SCALE) || '0');
  return minus ? -scaled : scaled;
}

function fromScaled(value: bigint): string {
  const negative = value < 0n;
  const absolute = negative ? -value : value;
  const integer = (absolute / FACTOR).toString();
  const fraction = (absolute % FACTOR).toString().padStart(SCALE, '0').replace(/0+$/, '');
  return `${negative ? '-' : ''}${integer}${fraction ? `.${fraction}` : ''}`;
}

/** `a + b` (chaînes décimales, au plus 3 décimales). */
export function addDecimal(a: string, b: string): string {
  return fromScaled(toScaled(a) + toScaled(b));
}

/** `a - b` (chaînes décimales, au plus 3 décimales). */
export function subtractDecimal(a: string, b: string): string {
  return fromScaled(toScaled(a) - toScaled(b));
}

/** Signe de `a - b` : -1, 0 ou 1. */
export function compareDecimal(a: string, b: string): number {
  const difference = toScaled(a) - toScaled(b);
  return difference === 0n ? 0 : difference < 0n ? -1 : 1;
}
