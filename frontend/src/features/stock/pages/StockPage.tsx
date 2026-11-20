import { History, Loader2, PackageOpen, Search, Settings2 } from 'lucide-react';
import { useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router';

import { useCategories } from '@/features/catalog/api';
import { hasPermission, useMe } from '@/features/session/api';
import { useStockList, useStockSummary } from '@/features/stock/api';
import { StockActions } from '@/features/stock/components/StockActions';
import { StockStatusBadge } from '@/features/stock/components/StockStatusBadge';
import {
  STOCK_SORTS,
  STOCK_STATUS_FILTERS,
  type StockQuery,
  type StockSort,
  type StockStatusFilter,
} from '@/features/stock/types';
import { formatMoney } from '@/shared/lib/formatMoney';
import { formatQuantity } from '@/shared/lib/formatQuantity';
import { ApiErrorMessage } from '@/shared/ui/ApiErrorMessage';
import { EmptyState } from '@/shared/ui/EmptyState';
import { PageHeader } from '@/shared/ui/PageHeader';
import { Pagination } from '@/shared/ui/Pagination';
import { SkeletonLines } from '@/shared/ui/Skeleton';
import { buttonClassName, inputClassName, panelClassName, secondaryButtonClassName } from '@/shared/ui/styles';

const PAGE_SIZE = 20;
const SEARCH_DELAY_MS = 300;

function readQuery(params: URLSearchParams): StockQuery {
  const status = params.get('status') as StockStatusFilter | null;
  const sort = params.get('sort') as StockSort | null;
  const page = Number.parseInt(params.get('page') ?? '0', 10);
  return {
    q: params.get('q') ?? '',
    categoryId: params.get('categoryId') ?? '',
    status: status && STOCK_STATUS_FILTERS.includes(status) ? status : 'ALL',
    sort: sort && STOCK_SORTS.includes(sort) ? sort : 'name,asc',
    page: Number.isNaN(page) || page < 0 ? 0 : page,
    size: PAGE_SIZE,
  };
}

/** Vue d'ensemble du stock : indicateurs, filtres (dans l'URL), liste paginée côté serveur, actions. */
export function StockPage() {
  const { t } = useTranslation(['stock', 'catalog']);
  const [params, setParams] = useSearchParams();
  const query = readQuery(params);
  const { data: me } = useMe();
  const list = useStockList(query);
  const summary = useStockSummary();
  const categories = useCategories('ACTIVE');
  const [search, setSearch] = useState(query.q);
  const ids = { search: useId(), category: useId(), status: useId(), sort: useId() };
  const canReadSettings = hasPermission(me, 'BUSINESS_SETTINGS_READ');

  function update(changes: Partial<Record<'q' | 'categoryId' | 'status' | 'sort' | 'page', string>>) {
    setParams(
      (current) => {
        const next = new URLSearchParams(current);
        Object.entries(changes).forEach(([key, value]) => {
          if (value === undefined || value === '') next.delete(key);
          else next.set(key, value);
        });
        if (!('page' in changes)) next.delete('page');
        return next;
      },
      { replace: true },
    );
  }

  useEffect(() => {
    if (search === query.q) return undefined;
    const timer = setTimeout(() => update({ q: search }), SEARCH_DELAY_MS);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [search]);

  const filtered = query.q !== '' || query.categoryId !== '' || query.status !== 'ALL';
  const indicators = summary.data
    ? [
        { key: 'tracked', label: t('stock:summary.tracked'), value: summary.data.trackedProducts, status: 'TRACKED', tone: 'text-ink' },
        { key: 'inStock', label: t('stock:summary.inStock'), value: summary.data.inStock, status: 'IN_STOCK', tone: 'text-ink' },
        { key: 'low', label: t('stock:summary.lowStock'), value: summary.data.lowStock, status: 'LOW_STOCK', tone: 'text-accent-strong' },
        { key: 'out', label: t('stock:summary.outOfStock'), value: summary.data.outOfStock, status: 'OUT_OF_STOCK', tone: summary.data.outOfStock > 0 ? 'text-danger' : 'text-ink' },
      ]
    : [];

  return (
    <section className="space-y-6">
      <PageHeader
        title={t('stock:title')}
        description={t('stock:intro')}
        actions={
          <Link to="/stock/history" className={buttonClassName('secondary')}>
            <History aria-hidden="true" className="mr-2 h-4 w-4" />
            <span>{t('stock:historyLink')}</span>
          </Link>
        }
      />

      {summary.data && (
        <div className="space-y-3">
          <ul className={`${panelClassName} grid grid-cols-2 divide-x divide-line overflow-hidden lg:grid-cols-4`}>
            {indicators.map((indicator) => (
              <li key={indicator.key} className="border-line [&:nth-child(n+3)]:border-t lg:[&:nth-child(n+3)]:border-t-0">
                <button
                  type="button"
                  onClick={() => update({ status: indicator.status })}
                  aria-pressed={query.status === indicator.status}
                  className={`relative h-full w-full p-4 text-left transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand sm:p-5 ${
                    query.status === indicator.status
                      ? 'bg-brand-soft after:absolute after:inset-x-0 after:bottom-0 after:h-0.5 after:bg-accent'
                      : 'hover:bg-surface'
                  }`}
                >
                  <span className="block text-xs font-semibold uppercase tracking-[0.1em] text-ink-muted">{indicator.label}</span>
                  <span className={`mt-2 block font-display text-3xl leading-none ${indicator.tone}`}>{indicator.value}</span>
                </button>
              </li>
            ))}
          </ul>
          {summary.data.notInitializedProducts > 0 && (
            <p className="text-sm text-ink-muted">
              {t('stock:summary.notInitialized', { count: summary.data.notInitializedProducts })}
            </p>
          )}
          {summary.data.estimatedValue && (
            <div className="rounded-lg border-l-4 border-accent bg-accent-soft p-4">
              <p className="text-sm text-ink-muted">{t('stock:summary.estimatedValue')}</p>
              <p className="mt-1 text-xl font-bold">
                {formatMoney(summary.data.estimatedValue.amount, summary.data.estimatedValue.currency)}
              </p>
              <p className="mt-1 text-xs text-ink-muted">{t('stock:summary.estimatedValueHint')}</p>
              {summary.data.estimatedValue.productsWithoutReferencePrice > 0 && (
                <p className="mt-1 text-xs text-ink-muted">
                  {t('stock:summary.withoutReferencePrice', {
                    count: summary.data.estimatedValue.productsWithoutReferencePrice,
                  })}
                </p>
              )}
            </div>
          )}
        </div>
      )}

      <div className={`${panelClassName} grid grid-cols-1 gap-3 p-4 sm:grid-cols-2 lg:grid-cols-[minmax(0,2fr)_1fr_1fr_1fr] lg:items-end`}>
        <div className="sm:col-span-2 lg:col-span-1">
          <label htmlFor={ids.search} className="sr-only">
            {t('stock:filters.search')}
          </label>
          <div className="relative">
            <Search aria-hidden="true" className="pointer-events-none absolute left-3 top-3 h-5 w-5 text-ink-muted" />
            <input
              id={ids.search}
              type="search"
              value={search}
              maxLength={100}
              onChange={(event) => setSearch(event.target.value)}
              placeholder={t('stock:filters.search')}
              className={`${inputClassName(false)} pl-10`}
            />
          </div>
        </div>
        <div>
          <label htmlFor={ids.category} className="mb-1 block text-xs font-medium text-ink-muted">
            {t('stock:filters.category')}
          </label>
          <select
            id={ids.category}
            value={query.categoryId}
            onChange={(event) => update({ categoryId: event.target.value })}
            className={inputClassName(false)}
          >
            <option value="">{t('stock:filters.allCategories')}</option>
            {categories.data?.content.map((category) => (
              <option key={category.id} value={category.id}>
                {category.name}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor={ids.status} className="mb-1 block text-xs font-medium text-ink-muted">
            {t('stock:filters.status')}
          </label>
          <select
            id={ids.status}
            value={query.status}
            onChange={(event) => update({ status: event.target.value === 'ALL' ? '' : event.target.value })}
            className={inputClassName(false)}
          >
            {STOCK_STATUS_FILTERS.map((status) => (
              <option key={status} value={status}>
                {t(`stock:status.${status}`)}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor={ids.sort} className="mb-1 block text-xs font-medium text-ink-muted">
            {t('stock:filters.sort')}
          </label>
          <select
            id={ids.sort}
            value={query.sort}
            onChange={(event) => update({ sort: event.target.value === 'name,asc' ? '' : event.target.value })}
            className={inputClassName(false)}
          >
            {STOCK_SORTS.map((sort) => (
              <option key={sort} value={sort}>
                {t(`stock:filters.sorts.${sort}`)}
              </option>
            ))}
          </select>
        </div>
      </div>

      {list.isPending ? (
        <div className={`${panelClassName} space-y-4 p-5`}>
          <p role="status" className="flex items-center text-ink-muted">
            <Loader2 aria-hidden="true" className="mr-2 h-5 w-5 animate-spin" />
            <span>{t('catalog:common.loading')}</span>
          </p>
          <SkeletonLines count={4} />
        </div>
      ) : list.isError ? (
        <div className="space-y-3 rounded-xl border border-line bg-sheet shadow-panel p-4">
          <p>{t('catalog:common.loadError')}</p>
          <ApiErrorMessage error={list.error} />
          <button type="button" onClick={() => void list.refetch()} className={secondaryButtonClassName}>
            {t('catalog:common.retry')}
          </button>
        </div>
      ) : list.data.content.length === 0 ? (
        <EmptyState
          icon={PackageOpen}
          title={filtered ? t('stock:empty.noResult') : t('stock:empty.noProducts')}
          action={
            filtered ? (
              <button
                type="button"
                onClick={() => {
                  setSearch('');
                  setParams(new URLSearchParams(), { replace: true });
                }}
                className={secondaryButtonClassName}
              >
                {t('stock:empty.clear')}
              </button>
            ) : (
              <Link to="/products" className={secondaryButtonClassName}>
                {t('stock:empty.goToProducts')}
              </Link>
            )
          }
        />
      ) : (
        <>
          <div className={`${panelClassName} overflow-hidden`}>
            <div
              aria-hidden="true"
              className="hidden border-b border-line bg-surface/70 px-5 py-3 text-xs font-semibold uppercase tracking-wide text-ink-muted lg:grid lg:grid-cols-[minmax(0,2.2fr)_1fr_1fr_minmax(0,2fr)] lg:gap-4"
            >
              <span>{t('stock:columns.product')}</span>
              <span className="text-right">{t('stock:columns.quantity')}</span>
              <span>{t('stock:columns.status')}</span>
              <span className="text-right">{t('stock:columns.actions')}</span>
            </div>
            <ul className="divide-y divide-line">
              {list.data.content.map((item) => {
                const unit = t(`stock:unitShort.${item.unit}`);
                return (
                  <li
                    key={item.productId}
                    className="flex flex-col p-4 transition-colors hover:bg-brand-soft/40 sm:px-5 lg:grid lg:grid-cols-[minmax(0,2.2fr)_1fr_1fr_minmax(0,2fr)] lg:items-center lg:gap-4"
                  >
                    <div className="min-w-0">
                      <Link to={`/stock/${item.productId}`} className="font-semibold hover:underline">
                        {item.productName}
                      </Link>
                      <p className="mt-0.5 truncate text-sm text-ink-muted">
                        {item.category?.name ?? t('catalog:products.noCategory')}
                        {item.sku && <span className="ml-2">· {item.sku}</span>}
                      </p>
                    </div>
                    <dl className="mt-3 flex items-center justify-between lg:contents">
                      <div className="lg:mt-0 lg:text-right">
                        <dt className="sr-only">{t('stock:columns.quantity')}</dt>
                        <dd className="font-display text-2xl leading-none">
                          {item.quantity !== null ? `${formatQuantity(item.quantity)} ${unit}` : '—'}
                        </dd>
                        {item.minimumQuantity !== null && (
                          <dd className="mt-1 text-xs text-ink-muted">
                            {t('stock:columns.minimum')} : {formatQuantity(item.minimumQuantity)}
                          </dd>
                        )}
                      </div>
                      <div>
                        <dt className="sr-only">{t('stock:columns.status')}</dt>
                        <dd>
                          <StockStatusBadge status={item.status} />
                        </dd>
                      </div>
                    </dl>
                    <div className="mt-3 lg:mt-0 lg:flex lg:justify-end">
                      <StockActions item={item} />
                    </div>
                  </li>
                );
              })}
            </ul>
          </div>
          <Pagination
            page={list.data.page.number}
            totalPages={list.data.page.totalPages}
            totalElements={list.data.page.totalElements}
            onChange={(page) => update({ page: page === 0 ? '' : String(page) })}
          />
        </>
      )}

      {canReadSettings && (
        <p className="flex flex-wrap items-center text-sm text-ink-muted">
          <Settings2 aria-hidden="true" className="mr-2 h-4 w-4 flex-none text-accent-strong" />
          <span className="mr-1">{t('stock:settings.movedToSettings')}</span>
          <Link to="/settings" className="font-semibold text-ink underline underline-offset-2">
            {t('stock:settings.openSettings')}
          </Link>
        </p>
      )}
    </section>
  );
}
