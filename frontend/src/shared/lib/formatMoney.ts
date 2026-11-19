/**
 * Formatage d'un montant reçu du serveur sous forme de CHAÎNE décimale (ADR‑06).
 *
 * Aucune conversion en `number` : le montant n'est jamais manipulé en virgule flottante,
 * ce qui préserve la précision quelle que soit sa taille. `Intl.NumberFormat` ne sert qu'à
 * obtenir les symboles et la mise en forme de la locale (séparateurs, position de la devise).
 * Aucun arrondi n'est effectué : le serveur envoie des montants déjà arrondis à l'échelle de
 * la devise ; si des décimales non nulles dépassent cette échelle, elles sont conservées.
 */
const DECIMAL_PATTERN = /^(-)?(\d+)(?:\.(\d+))?$/;

export const DEFAULT_LOCALE = 'fr-SN';

function groupDigits(integerDigits: string, separator: string): string {
  const groups: string[] = [];
  for (let end = integerDigits.length; end > 0; end -= 3) {
    groups.unshift(integerDigits.slice(Math.max(0, end - 3), end));
  }
  return groups.join(separator);
}

export function formatMoney(amount: string, currency: string, locale: string = DEFAULT_LOCALE) {
  const match = DECIMAL_PATTERN.exec(amount.trim());
  if (!match) {
    throw new RangeError(`Montant invalide : « ${amount} »`);
  }
  const [, minus, rawInteger = '0', rawFraction = ''] = match;

  const integerDigits = rawInteger.replace(/^0+(?=\d)/, '');
  const scale =
    new Intl.NumberFormat(locale, { style: 'currency', currency }).resolvedOptions()
      .maximumFractionDigits ?? 0;

  let fraction = rawFraction.padEnd(scale, '0');
  if (fraction.length > scale) {
    const excess = fraction.slice(scale);
    fraction = /^0*$/.test(excess) ? fraction.slice(0, scale) : fraction;
  }

  const isZero = /^0*$/.test(integerDigits) && /^0*$/.test(fraction);
  const negative = minus === '-' && !isZero;

  // Gabarit de la locale (séparateurs, position du signe et de la devise), obtenu sur un
  // nombre entier exact à 4 chiffres avec le nombre de décimales à afficher.
  const template = new Intl.NumberFormat(locale, {
    style: 'currency',
    currency,
    minimumFractionDigits: fraction.length,
    maximumFractionDigits: fraction.length,
  }).formatToParts(negative ? -1000 : 1000);
  const groupSeparator = template.find((part) => part.type === 'group')?.value ?? '';
  const decimalSeparator = template.find((part) => part.type === 'decimal')?.value ?? '.';

  let integerWritten = false;
  return template
    .map((part) => {
      switch (part.type) {
        case 'integer':
          if (integerWritten) {
            return '';
          }
          integerWritten = true;
          return groupDigits(integerDigits, groupSeparator);
        case 'group':
          return '';
        case 'decimal':
          return fraction.length > 0 ? decimalSeparator : '';
        case 'fraction':
          return fraction;
        default:
          return part.value;
      }
    })
    .join('');
}
