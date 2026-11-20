import { AlertTriangle, CircleCheck, CircleSlash, MinusCircle } from 'lucide-react';
import { useTranslation } from 'react-i18next';

import type { StockStatus } from '@/features/stock/types';

const STYLES: Record<StockStatus, string> = {
  IN_STOCK: 'border-brand/30 bg-surface text-ink',
  LOW_STOCK: 'border-accent bg-accent/40 text-ink',
  OUT_OF_STOCK: 'border-brand bg-brand text-brand-contrast',
  NOT_INITIALIZED: 'border-ink/20 bg-white text-ink-muted',
};

const ICONS = {
  IN_STOCK: CircleCheck,
  LOW_STOCK: AlertTriangle,
  OUT_OF_STOCK: CircleSlash,
  NOT_INITIALIZED: MinusCircle,
} as const;

/** État du stock : icône ET libellé (jamais la couleur seule), couleurs de la charte uniquement. */
export function StockStatusBadge({ status }: { readonly status: StockStatus }) {
  const { t } = useTranslation(['stock']);
  const Icon = ICONS[status];
  return (
    <span
      className={`inline-flex items-center whitespace-nowrap rounded-full border px-2 py-0.5 text-xs font-semibold ${STYLES[status]}`}
    >
      <Icon aria-hidden="true" className="mr-1 h-3.5 w-3.5" />
      {t(`stock:status.${status}`)}
    </span>
  );
}
