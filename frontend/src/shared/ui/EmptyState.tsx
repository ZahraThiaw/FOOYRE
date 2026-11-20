import type { LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';

interface EmptyStateProps {
  readonly icon: LucideIcon;
  /** Textes déjà traduits. */
  readonly title: string;
  readonly description?: string | undefined;
  /** Action proposée (ex. « Ajouter un produit »), affichée seulement si l'utilisatrice y a droit. */
  readonly action?: ReactNode;
}

/** État vide : explique la situation et propose quoi faire, jamais un écran muet. */
export function EmptyState({ icon: Icon, title, description, action }: EmptyStateProps) {
  return (
    <div className="rounded-xl border border-dashed border-ink/20 bg-sheet/60 px-6 py-10 text-center">
      <span className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-accent-soft ring-1 ring-accent">
        <Icon aria-hidden="true" className="h-6 w-6 text-ink" strokeWidth={1.75} />
      </span>
      <p className="mt-4 font-semibold text-ink">{title}</p>
      {description && <p className="mx-auto mt-1 max-w-md text-sm text-ink-muted">{description}</p>}
      {action && <div className="mt-5 flex justify-center">{action}</div>}
    </div>
  );
}
