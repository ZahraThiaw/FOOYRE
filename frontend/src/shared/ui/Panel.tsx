import { useId, type ReactNode } from 'react';

import { panelClassName } from './styles';

interface PanelProps {
  /** Titre déjà traduit ; s'il est fourni, la section est nommée par lui (`aria-labelledby`). */
  readonly title?: string;
  readonly description?: string;
  /** Actions alignées à droite du titre. */
  readonly actions?: ReactNode;
  /** Sans marge intérieure (listes ou tableaux bord à bord). */
  readonly flush?: boolean;
  readonly className?: string;
  readonly children: ReactNode;
}

/** Feuille de travail : surface blanche sur le fond blanc cassé, filet fin, ombre légère. */
export function Panel({ title, description, actions, flush = false, className = '', children }: PanelProps) {
  const titleId = useId();
  return (
    <section aria-labelledby={title ? titleId : undefined} className={`${panelClassName} ${className}`}>
      {(title || actions) && (
        <header className="flex flex-wrap items-start justify-between border-b border-line px-4 py-3 sm:px-5">
          <div className="min-w-0">
            {title && (
              <h2 id={titleId} className="text-base font-semibold text-ink">
                {title}
              </h2>
            )}
            {description && <p className="mt-0.5 text-sm text-ink-muted">{description}</p>}
          </div>
          {actions && <div className="ml-auto flex items-center space-x-2 pl-4">{actions}</div>}
        </header>
      )}
      <div className={flush ? '' : 'p-4 sm:p-5'}>{children}</div>
    </section>
  );
}
