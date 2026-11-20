import { ArrowDownToLine, ArrowUpFromLine, Gauge, PackagePlus, SlidersHorizontal } from 'lucide-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { buttonClassName } from '@/shared/ui/styles';
import { hasPermission, useMe } from '@/features/session/api';
import { useBusinessSettings } from '@/features/stock/api';
import { StockOperationDialog } from '@/features/stock/components/StockOperationDialog';
import type { StockItem, StockOperation } from '@/features/stock/types';
import { useNotify } from '@/shared/ui/useNotify';

const OPERATIONS: readonly Exclude<StockOperation, 'initialize'>[] = ['entry', 'exit', 'adjustment', 'minimum'];
const ICONS = { entry: ArrowDownToLine, exit: ArrowUpFromLine, adjustment: SlidersHorizontal, minimum: Gauge } as const;
/** Opérations affichées avec leur libellé ; les autres en icône seule (nom accessible complet). */
const LABELLED = new Set<StockOperation>(['entry', 'exit']);

/**
 * Actions de stock d'un produit (STOCK_ADJUST, confort d'affichage : le serveur contrôle). Produit
 * non suivi : « Initialiser le stock » ; ensuite entrée, sortie, ajustement, seuil.
 */
export function StockActions({ item }: { readonly item: StockItem }) {
  const { t } = useTranslation(['stock']);
  const { data: me } = useMe();
  const notify = useNotify();
  const canAdjust = hasPermission(me, 'STOCK_ADJUST');
  const settings = useBusinessSettings(canAdjust && hasPermission(me, 'BUSINESS_SETTINGS_READ'));
  const [operation, setOperation] = useState<StockOperation | null>(null);

  if (!canAdjust || item.productStatus !== 'ACTIVE') return null;

  return (
    <>
      <div className="flex flex-wrap items-center">
        {!item.initialized ? (
          <button type="button" onClick={() => setOperation('initialize')} className={buttonClassName('accent', 'sm')}>
            <PackagePlus aria-hidden="true" className="mr-2 h-4 w-4" />
            <span>{t('stock:actions.initialize')}</span>
          </button>
        ) : (
          OPERATIONS.map((op) => {
            const Icon = ICONS[op];
            const label = t(`stock:actions.${op}`);
            const labelled = LABELLED.has(op);
            return (
              <button
                key={op}
                type="button"
                onClick={() => setOperation(op)}
                aria-label={`${label} · ${item.productName}`}
                title={labelled ? undefined : label}
                className={`${buttonClassName(op === 'entry' ? 'primary' : 'secondary', 'sm')} mb-1 mr-1.5 ${labelled ? '' : 'w-11 px-0 sm:w-9'}`}
              >
                <Icon aria-hidden="true" className={`h-4 w-4 ${labelled ? 'mr-1.5' : ''}`} />
                {labelled && <span aria-hidden="true">{label}</span>}
              </button>
            );
          })
        )}
      </div>
      {operation && (
        <StockOperationDialog
          operation={operation}
          item={item}
          allowNegativeStock={settings.data?.allowNegativeStock}
          onClose={() => setOperation(null)}
          onDone={(message) => {
            setOperation(null);
            notify('success', message);
          }}
        />
      )}
    </>
  );
}
