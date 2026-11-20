import { ArrowLeft, Loader2 } from 'lucide-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';

import { PageHeader } from '@/shared/ui/PageHeader';
import { Panel } from '@/shared/ui/Panel';
import { panelClassName, secondaryButtonClassName } from '@/shared/ui/styles';
import { useStockItem, useStockMovements } from '@/features/stock/api';
import { MovementsTable } from '@/features/stock/components/MovementsTable';
import { StockActions } from '@/features/stock/components/StockActions';
import { StockStatusBadge } from '@/features/stock/components/StockStatusBadge';
import { toApiError } from '@/shared/api/apiError';
import { formatQuantity } from '@/shared/lib/formatQuantity';
import { ApiErrorMessage } from '@/shared/ui/ApiErrorMessage';
import { Pagination } from '@/shared/ui/Pagination';

const DATE_FORMAT: Intl.DateTimeFormatOptions = { dateStyle: 'medium', timeStyle: 'short' };

/** Stock d'un produit : état, actions, historique paginé (lecture seule). */
export function StockProductPage() {
  const { t, i18n } = useTranslation(['stock', 'catalog']);
  const { productId = '' } = useParams();
  const item = useStockItem(productId);
  const [page, setPage] = useState(0);
  const movements = useStockMovements(productId, page);

  if (item.isPending) {
    return (
      <p role="status" className="flex items-center text-ink-muted">
        <Loader2 aria-hidden="true" className="mr-2 h-5 w-5 animate-spin" />
        <span>{t('catalog:common.loading')}</span>
      </p>
    );
  }
  if (item.isError) {
    return (
      <div className="space-y-4">
        {toApiError(item.error).status === 404 ? (
          <p role="alert">{t('stock:detail.notFound')}</p>
        ) : (
          <ApiErrorMessage error={item.error} />
        )}
        <Link to="/stock" className={secondaryButtonClassName}>
          {t('catalog:common.back')}
        </Link>
      </div>
    );
  }

  const data = item.data;
  const unit = t(`stock:unitShort.${data.unit}`);
  return (
    <section className="space-y-6">
      <Link to="/stock" className="inline-flex min-h-touch items-center text-sm font-medium text-ink-muted hover:text-ink">
        <ArrowLeft aria-hidden="true" className="mr-2 h-4 w-4" />
        <span>{t('stock:title')}</span>
      </Link>
      <PageHeader
        title={data.productName}
        description={
          <>
            {data.category?.name ?? t('catalog:products.noCategory')}
            {data.sku && <span className="ml-2">· {data.sku}</span>}
          </>
        }
        actions={<StockStatusBadge status={data.status} />}
      />

      {data.productStatus === 'ARCHIVED' && (
        <p role="status" className="rounded-md border-l-4 border-accent bg-accent-soft p-3 text-sm">
          {t('stock:detail.archived')}
        </p>
      )}
      {!data.initialized && data.productStatus === 'ACTIVE' && (
        <p className="rounded-md border-l-4 border-accent bg-accent-soft p-3 text-sm">{t('stock:detail.notInitialized')}</p>
      )}

      {data.initialized && (
        <dl className={`${panelClassName} grid grid-cols-1 divide-y divide-line overflow-hidden sm:grid-cols-3 sm:divide-x sm:divide-y-0`}>
          <div className="p-4 sm:p-5">
            <dt className="text-xs font-semibold uppercase tracking-[0.1em] text-ink-muted">{t('stock:columns.quantity')}</dt>
            <dd className="mt-2 font-display text-4xl leading-none">{`${formatQuantity(data.quantity ?? '0')} ${unit}`}</dd>
          </div>
          <div className="p-4 sm:p-5">
            <dt className="text-xs font-semibold uppercase tracking-[0.1em] text-ink-muted">{t('stock:columns.minimum')}</dt>
            <dd className="mt-2 font-display text-4xl leading-none">{`${formatQuantity(data.minimumQuantity ?? '0')} ${unit}`}</dd>
          </div>
          <div className="p-4 sm:p-5">
            <dt className="text-xs font-semibold uppercase tracking-[0.1em] text-ink-muted">{t('stock:columns.updatedAt')}</dt>
            <dd className="mt-2 font-semibold">
              {data.updatedAt ? new Intl.DateTimeFormat(i18n.language, DATE_FORMAT).format(new Date(data.updatedAt)) : '—'}
            </dd>
          </div>
        </dl>
      )}

      <StockActions item={data} />

      <Panel title={t('stock:history.title')} description={t('stock:history.intro')}>
        <div>
          {movements.data && movements.data.content.length > 0 ? (
            <>
              <MovementsTable movements={movements.data.content} showProduct={false} />
              <div className="mt-3">
                <Pagination
                  page={movements.data.page.number}
                  totalPages={movements.data.page.totalPages}
                  totalElements={movements.data.page.totalElements}
                  onChange={setPage}
                />
              </div>
            </>
          ) : movements.isError ? (
            <ApiErrorMessage error={movements.error} />
          ) : movements.isPending ? null : (
            <p className="text-sm text-ink-muted">{t('stock:empty.noMovement')}</p>
          )}
        </div>
      </Panel>
    </section>
  );
}
