import { initialsOf } from '@/shared/lib/initials';

interface AvatarProps {
  readonly name: string;
  readonly size?: 'sm' | 'md';
  /** `onDark` : sur le vert profond de la barre latérale. */
  readonly onDark?: boolean;
}

/** Pastille aux initiales (décorative : le nom est toujours écrit à côté). */
export function Avatar({ name, size = 'md', onDark = false }: AvatarProps) {
  const dimensions = size === 'sm' ? 'h-8 w-8 text-xs' : 'h-10 w-10 text-sm';
  const colors = onDark ? 'bg-accent text-brand' : 'bg-accent-soft text-ink ring-1 ring-accent';
  return (
    <span
      aria-hidden="true"
      className={`inline-flex flex-none items-center justify-center rounded-full font-semibold ${dimensions} ${colors}`}
    >
      {initialsOf(name)}
    </span>
  );
}
