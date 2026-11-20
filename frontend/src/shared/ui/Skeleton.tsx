interface SkeletonProps {
  /** Dimensions Tailwind (ex. `h-4 w-32`). */
  readonly className?: string;
}

/**
 * Bloc d'attente : réserve la place du contenu pendant le chargement (pas de saut de mise en
 * page). Purement décoratif (`aria-hidden`) : l'état « chargement » est annoncé par le texte de
 * l'écran. L'animation s'arrête si le système demande de réduire les animations.
 */
export function Skeleton({ className = 'h-4 w-full' }: SkeletonProps) {
  return <span aria-hidden="true" data-slot="skeleton" className={`block animate-pulse rounded-md bg-brand/10 ${className}`} />;
}

/** Plusieurs lignes d'attente (listes). */
export function SkeletonLines({ count = 3 }: { readonly count?: number }) {
  return (
    <span aria-hidden="true" className="block space-y-3">
      {Array.from({ length: count }, (_, index) => (
        <Skeleton key={index} className={index % 2 === 0 ? 'h-4 w-full' : 'h-4 w-2/3'} />
      ))}
    </span>
  );
}
