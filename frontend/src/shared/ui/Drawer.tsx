import { useEffect, useRef, type ReactNode, type RefObject } from 'react';

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

interface DrawerProps {
  readonly open: boolean;
  readonly onClose: () => void;
  /** Nom accessible du tiroir, déjà traduit. */
  readonly label: string;
  readonly id?: string;
  /** Élément qui a ouvert le tiroir : il retrouve le focus à la fermeture. */
  readonly returnFocusRef?: RefObject<HTMLElement | null>;
  readonly children: ReactNode;
}

/**
 * Tiroir latéral modal (mobile), accessible : rôle `dialog` + `aria-modal`, focus placé dans le
 * tiroir à l'ouverture et maintenu à l'intérieur (Tab / Maj+Tab), Échap ou clic sur le voile pour
 * fermer, focus rendu à l'élément d'origine, défilement de la page bloqué pendant l'ouverture.
 * Pas d'élément `<dialog>` ni d'attribut `inert` : non pris en charge par Safari 14 (cible).
 */
export function Drawer({ open, onClose, label, id, returnFocusRef, children }: DrawerProps) {
  const panelRef = useRef<HTMLDivElement>(null);
  const onCloseRef = useRef(onClose);
  useEffect(() => {
    onCloseRef.current = onClose;
  });

  useEffect(() => {
    if (!open) return undefined;
    const panel = panelRef.current;
    const returnTarget = returnFocusRef?.current ?? null;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    panel?.querySelector<HTMLElement>(FOCUSABLE)?.focus();

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        onCloseRef.current();
        return;
      }
      if (event.key !== 'Tab' || !panel) return;
      const focusable = Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE));
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (!first || !last) return;
      const active = document.activeElement;
      if (event.shiftKey && (active === first || !panel.contains(active))) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && (active === last || !panel.contains(active))) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      document.body.style.overflow = previousOverflow;
      returnTarget?.focus();
    };
  }, [open, returnFocusRef]);

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 md:hidden">
      <div aria-hidden="true" data-slot="drawer-backdrop" className="absolute inset-0 bg-brand/60" onClick={onClose} />
      <div
        ref={panelRef}
        id={id}
        role="dialog"
        aria-modal="true"
        aria-label={label}
        className="absolute inset-y-0 left-0 flex w-72 max-w-[85%] flex-col overflow-y-auto bg-brand text-brand-contrast shadow-raised"
      >
        {children}
      </div>
    </div>
  );
}
