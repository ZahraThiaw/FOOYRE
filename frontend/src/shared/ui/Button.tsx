import { Loader2 } from 'lucide-react';
import type { ButtonHTMLAttributes, ReactNode } from 'react';

import { buttonClassName, type ButtonSize, type ButtonVariant } from './styles';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  readonly variant?: ButtonVariant;
  readonly size?: ButtonSize;
  /** Action en cours : bouton désactivé et indicateur de chargement (le libellé reste lisible). */
  readonly loading?: boolean;
  /** Icône Lucide placée avant le libellé (décorative). */
  readonly icon?: ReactNode;
}

/** Bouton du système de design. `type="button"` par défaut (jamais d'envoi de formulaire involontaire). */
export function Button({
  variant = 'primary',
  size = 'md',
  loading = false,
  icon,
  disabled,
  className = '',
  type = 'button',
  children,
  ...rest
}: ButtonProps) {
  return (
    <button
      type={type}
      disabled={disabled === true || loading}
      aria-busy={loading || undefined}
      className={`${buttonClassName(variant, size)} ${className}`}
      {...rest}
    >
      {loading ? (
        <Loader2 aria-hidden="true" className="mr-2 h-4 w-4 animate-spin" />
      ) : (
        icon && (
          <span aria-hidden="true" className="mr-2 inline-flex">
            {icon}
          </span>
        )
      )}
      {children}
    </button>
  );
}
