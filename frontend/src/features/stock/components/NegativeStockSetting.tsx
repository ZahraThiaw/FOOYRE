import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useBusinessSettings, useUpdateBusinessSettings } from '@/features/stock/api';
import { ConfirmDialog } from '@/shared/ui/ConfirmDialog';
import { useNotify } from '@/shared/ui/useNotify';

/**
 * Paramètre « stock négatif » (D5) : lecture avec BUSINESS_SETTINGS_READ, modification avec
 * BUSINESS_SETTINGS_UPDATE (propriétaire). Confort d'affichage : le serveur contrôle les droits.
 */
export function NegativeStockSetting({ canUpdate }: { readonly canUpdate: boolean }) {
  const { t } = useTranslation(['stock']);
  const notify = useNotify();
  const settings = useBusinessSettings(true);
  const update = useUpdateBusinessSettings();
  const [confirming, setConfirming] = useState(false);
  if (!settings.data) return null;
  const enabled = settings.data.allowNegativeStock;

  return (
    <section id="stock" aria-labelledby="stock-settings-title" className="scroll-mt-24 rounded-xl border border-line bg-sheet p-4 shadow-panel sm:p-5">
      <h2 id="stock-settings-title" className="text-base font-semibold">
        {t('stock:settings.title')}
      </h2>
      <div className="mt-2 flex flex-col sm:flex-row sm:items-center sm:justify-between">
        <div className="sm:mr-4">
          <p className="font-medium">
            {t('stock:settings.negativeStock')} :{' '}
            <span>{enabled ? t('stock:settings.enabled') : t('stock:settings.disabled')}</span>
          </p>
          <p className="mt-1 text-sm text-ink-muted">{t('stock:settings.negativeStockHelp')}</p>
          {!canUpdate && <p className="mt-1 text-xs text-ink-muted">{t('stock:settings.ownerOnly')}</p>}
        </div>
        {canUpdate && (
          <button
            type="button"
            role="switch"
            aria-checked={enabled}
            aria-label={t('stock:settings.negativeStock')}
            onClick={() => setConfirming(true)}
            className={`relative mt-3 inline-flex h-8 w-14 flex-none items-center rounded-full border-2 border-brand focus:outline-none focus-visible:ring-2 focus-visible:ring-brand sm:mt-0 ${
              enabled ? 'bg-brand' : 'bg-white'
            }`}
          >
            <span
              aria-hidden="true"
              className={`inline-block h-5 w-5 rounded-full transition-transform ${
                enabled ? 'translate-x-7 bg-accent' : 'translate-x-1 bg-brand'
              }`}
            />
          </button>
        )}
      </div>
      <ConfirmDialog
        open={confirming}
        title={enabled ? t('stock:settings.disableTitle') : t('stock:settings.enableTitle')}
        description={enabled ? t('stock:settings.disableDescription') : t('stock:settings.enableDescription')}
        confirmLabel={t('stock:operation.confirm')}
        cancelLabel={t('stock:operation.cancel')}
        pending={update.isPending}
        onCancel={() => setConfirming(false)}
        onConfirm={() =>
          update.mutate(
            { allowNegativeStock: !enabled },
            {
              onSuccess: () => notify('success', t('stock:settings.saved')),
              onError: () => notify('error', t('stock:settings.failed')),
              onSettled: () => setConfirming(false),
            },
          )
        }
      />
    </section>
  );
}
