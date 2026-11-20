import type { ReactNode } from 'react';

interface PageHeaderProps {
  /** Titre de page déjà traduit (unique `h1` de l'écran). */
  readonly title: string;
  readonly description?: ReactNode;
  /** Actions principales de l'écran (au plus une action « accent »). */
  readonly actions?: ReactNode;
}

/** En-tête de page : grand titre à empattements souligné du fil d'or, description et actions. */
export function PageHeader({ title, description, actions }: PageHeaderProps) {
  return (
    <div className="flex flex-col space-y-4 sm:flex-row sm:items-end sm:justify-between sm:space-y-0">
      <div className="min-w-0 space-y-2">
        <h1 className="text-3xl text-ink sm:text-4xl">{title}</h1>
        <div aria-hidden="true" className="h-0.5 w-12 rounded-full bg-accent" />
        {description && <p className="max-w-2xl text-sm text-ink-muted sm:text-base">{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center sm:ml-6 sm:flex-none">{actions}</div>}
    </div>
  );
}
