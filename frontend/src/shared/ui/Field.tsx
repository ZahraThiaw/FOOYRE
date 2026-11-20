import { AlertCircle } from 'lucide-react';
import type { ReactNode } from 'react';

interface FieldProps {
  readonly id: string;
  readonly label: string;
  /** Mention « facultatif » déjà traduite, affichée après le libellé. */
  readonly optionalLabel?: string;
  readonly hint?: string;
  readonly error?: string | null;
  readonly children: ReactNode;
}

/**
 * Champ de formulaire : libellé, aide et message d'erreur reliés au contrôle (`{id}-hint`,
 * `{id}-error`). L'erreur est signalée par une icône ET un texte en terre cuite (jamais par la
 * couleur seule).
 */
export function Field({ id, label, optionalLabel, hint, error, children }: FieldProps) {
  return (
    <div className="space-y-1.5">
      <label htmlFor={id} className="block text-sm font-medium text-ink">
        {label}
        {optionalLabel && <span className="ml-1 font-normal text-ink-muted">({optionalLabel})</span>}
      </label>
      {children}
      {hint && !error && (
        <p id={`${id}-hint`} className="text-xs text-ink-muted">
          {hint}
        </p>
      )}
      {error && (
        <p id={`${id}-error`} className="flex items-start text-sm font-medium text-danger">
          <AlertCircle aria-hidden="true" className="mr-1.5 mt-0.5 h-4 w-4 flex-none" />
          <span>{error}</span>
        </p>
      )}
    </div>
  );
}
