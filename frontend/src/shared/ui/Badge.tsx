import type { ReactNode } from 'react';

export type BadgeTone = 'neutral' | 'brand' | 'accent' | 'danger' | 'solid';

const TONES: Record<BadgeTone, string> = {
  neutral: 'border-line bg-sheet text-ink-muted',
  brand: 'border-brand/20 bg-brand-soft text-ink',
  accent: 'border-accent bg-accent-soft text-ink',
  danger: 'border-danger/30 bg-danger-surface text-danger',
  solid: 'border-brand bg-brand text-brand-contrast',
};

interface BadgeProps {
  readonly tone?: BadgeTone;
  /** Icône décorative : l'état est toujours porté par le libellé (jamais la couleur seule). */
  readonly icon?: ReactNode;
  readonly children: ReactNode;
}

/** Pastille d'état (stock, statut, règlement) : icône facultative et libellé obligatoire. */
export function Badge({ tone = 'neutral', icon, children }: BadgeProps) {
  return (
    <span
      className={`inline-flex items-center whitespace-nowrap rounded-full border px-2 py-0.5 text-xs font-semibold ${TONES[tone]}`}
    >
      {icon && (
        <span aria-hidden="true" className="mr-1 inline-flex">
          {icon}
        </span>
      )}
      {children}
    </span>
  );
}
