import { ArrowLeft, Loader2 } from 'lucide-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

import { useStockMovements } from '@/features/stock/api';
import { MovementsTable } from '@/features/stock/components/MovementsTable';
import { ApiErrorMessage } from '@/shared/ui/ApiErrorMessage';
import { PageHeader } from '@/shared/ui/PageHeader';
import { Pagination } from '@/shared/ui/Pagination';
import { panelClassName } from '@/shared/ui/styles';

/** Historique de tous les mouvements de l'entreprise (lecture seule, du plus récent au plus ancien). */
export function StockHistoryPage() {
  const { t } = useTranslation(['stock', 'catalog']);
  const [page, setPage] = useState(0);
  const movements = useStockMovements('', page);

  return (
    <section className="space-y-6">
      <Link to="/stock" className="inline-flex min-h-touch items-center text-sm font-medium text-ink-muted hover:text-ink">
        <ArrowLeft aria-hidden="true" className="mr-2 h-4 w-4" />
        <span>{t('stock:title')}</span>
      </Link>
      <PageHeader title={t('stock:history.title')} description={t('stock:history.intro')} />
      <div className={`${panelClassName} p-4 sm:p-5`}>
        {movements.isPending ? (
          <p role="status" className="flex items-center text-ink-muted">
            <Loader2 aria-hidden="true" className="mr-2 h-5 w-5 animate-spin" />
            <span>{t('catalog:common.loading')}</span>
          </p>
        ) : movements.isError ? (
          <ApiErrorMessage error={movements.error} />
        ) : movements.data.content.length === 0 ? (
          <p className="text-sm text-ink-muted">{t('stock:empty.noMovement')}</p>
        ) : (
          <>
            <MovementsTable movements={movements.data.content} showProduct />
            <div className="mt-3">
              <Pagination
                page={movements.data.page.number}
                totalPages={movements.data.page.totalPages}
                totalElements={movements.data.page.totalElements}
                onChange={setPage}
              />
            </div>
          </>
        )}
      </div>
    </section>
  );
}
