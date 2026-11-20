import { Loader2 } from 'lucide-react';
import { useEffect, useId, useRef } from 'react';

import { buttonClassName } from './styles';

interface ConfirmDialogProps {
  readonly open: boolean;
  readonly title: string;
  readonly description: string;
  readonly confirmLabel: string;
  readonly cancelLabel: string;
  readonly pending?: boolean;
  readonly onConfirm: () => void;
  readonly onCancel: () => void;
}

/**
 * Boîte de confirmation accessible (rôle `alertdialog`, focus initial sur « Annuler », Échap pour
 * fermer). Pas d'élément `<dialog>` natif : non pris en charge par Safari 14 (cible navigateurs).
 * Les textes sont fournis traduits par l'appelant.
 */
export function ConfirmDialog({
  open,
  title,
  description,
  confirmLabel,
  cancelLabel,
  pending = false,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  const titleId = useId();
  const descriptionId = useId();
  const cancelRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return undefined;
    cancelRef.current?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !pending) onCancel();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [open, pending, onCancel]);

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-brand/50 p-4 sm:items-center">
      <div
        role="alertdialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={descriptionId}
        className="w-full max-w-md rounded-2xl border border-line bg-sheet p-6 shadow-raised"
      >
        <h2 id={titleId} className="font-display text-2xl text-ink">
          {title}
        </h2>
        <p id={descriptionId} className="mt-2 text-sm text-ink-muted">
          {description}
        </p>
        <div className="mt-6 flex flex-col-reverse sm:flex-row sm:justify-end">
          <button
            ref={cancelRef}
            type="button"
            onClick={onCancel}
            disabled={pending}
            className={`${buttonClassName('secondary')} mt-2 sm:mr-2 sm:mt-0`}
          >
            {cancelLabel}
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={pending}
            className={buttonClassName('primary')}
          >
            {pending && <Loader2 aria-hidden="true" className="mr-2 h-4 w-4 animate-spin" />}
            <span>{confirmLabel}</span>
          </button>
        </div>
      </div>
    </div>
  );
}
