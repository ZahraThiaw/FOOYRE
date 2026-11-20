/**
 * Système de design FOOYRE « Le fil d'or » : classes partagées (Tailwind) des contrôles et
 * surfaces. Une seule source pour les boutons, champs, panneaux et tableaux de toute
 * l'application ; les composants de `shared/ui` les utilisent, et les écrans peuvent les
 * appliquer à un `Link` ou à un élément natif.
 */

/** Attributs d'accessibilité d'un contrôle relié à son aide ou à son erreur. */
export function describedBy(id: string, hasError: boolean, hasHint: boolean) {
  return {
    'aria-invalid': hasError ? true : undefined,
    'aria-describedby': hasError ? `${id}-error` : hasHint ? `${id}-hint` : undefined,
  } as const;
}

/** Anneau de focus visible sur fond clair (vert profond : contraste ≥ 3:1). */
export const focusRing =
  'focus:outline-none focus-visible:ring-2 focus-visible:ring-brand focus-visible:ring-offset-2 focus-visible:ring-offset-sheet';

export type ButtonVariant = 'primary' | 'accent' | 'secondary' | 'ghost' | 'danger';
export type ButtonSize = 'md' | 'sm';

const BUTTON_BASE =
  'inline-flex items-center justify-center rounded-lg font-semibold transition-colors disabled:cursor-not-allowed disabled:opacity-60';

const BUTTON_SIZES: Record<ButtonSize, string> = {
  md: 'min-h-touch px-4 text-sm sm:text-base',
  // Compact (barres d'outils sur ordinateur) : la zone tactile reste de 44 px sur mobile.
  sm: 'min-h-touch px-3 text-sm sm:min-h-[36px]',
};

const BUTTON_VARIANTS: Record<ButtonVariant, string> = {
  primary: 'bg-brand text-brand-contrast shadow-panel hover:bg-brand-800',
  // Action d'exception (au plus une par écran) : or champagne, texte vert profond (8,4:1).
  accent: 'bg-accent text-brand shadow-panel hover:bg-accent/85',
  secondary: 'border border-line bg-sheet text-ink hover:border-ink/30 hover:bg-brand-soft',
  ghost: 'text-ink hover:bg-brand-soft',
  danger: 'bg-danger text-white shadow-panel hover:bg-danger/90',
};

/** Classes d'un bouton (ou d'un lien présenté comme un bouton). */
export function buttonClassName(variant: ButtonVariant = 'primary', size: ButtonSize = 'md'): string {
  return `${BUTTON_BASE} ${BUTTON_SIZES[size]} ${BUTTON_VARIANTS[variant]} ${focusRing}`;
}

export const primaryButtonClassName = buttonClassName('primary');
export const secondaryButtonClassName = buttonClassName('secondary');
export const accentButtonClassName = buttonClassName('accent');
export const dangerButtonClassName = buttonClassName('danger');

/** Champ de saisie, liste déroulante ou zone de texte (feuille blanche, erreur en terre cuite). */
export const inputClassName = (hasError: boolean) =>
  `block w-full min-h-touch rounded-lg bg-sheet px-3 text-base text-ink placeholder:text-ink-muted/70 transition-colors focus:outline-none focus:ring-2 disabled:cursor-not-allowed disabled:bg-surface disabled:opacity-70 ${
    hasError
      ? 'border-2 border-danger focus:border-danger focus:ring-danger/25'
      : 'border border-line hover:border-ink/30 focus:border-brand focus:ring-accent/60'
  }`;

/** Feuille de travail : panneau blanc, filet et ombre légère. */
export const panelClassName = 'rounded-xl border border-line bg-sheet shadow-panel';

/** Tableau de données : en-tête discret, lignes séparées par un filet, montants à droite. */
export const tableClassNames = {
  wrapper: 'overflow-x-auto rounded-xl border border-line bg-sheet shadow-panel',
  table: 'min-w-full text-sm',
  head: 'border-b border-line bg-surface/70 text-left text-xs font-semibold uppercase tracking-wide text-ink-muted',
  headCell: 'px-4 py-3 font-semibold',
  row: 'border-b border-line last:border-b-0 hover:bg-brand-soft/50',
  cell: 'px-4 py-3 align-top',
  numeric: 'text-right tabular-nums',
} as const;
