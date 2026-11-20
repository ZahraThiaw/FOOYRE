import { Archive, Loader2, PackageOpen, Plus, Search } from 'lucide-react';
import { useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router';

import { archiveProduct, useCatalogMutation, useCategories, useProducts } from '@/features/catalog/api';
import { useCatalogAccess } from '@/features/catalog/access';
import { Avatar } from '@/shared/ui/Avatar';
import { Badge } from '@/shared/ui/Badge';
import { EmptyState } from '@/shared/ui/EmptyState';
import { PageHeader } from '@/shared/ui/PageHeader';
import { SkeletonLines } from '@/shared/ui/Skeleton';
import { inputClassName, panelClassName, primaryButtonClassName, secondaryButtonClassName } from '@/shared/ui/styles';
import {
  PRODUCT_SORTS,
  type Product,
  type ProductQuery,
  type ProductSort,
  type StatusFilter,
} from '@/features/catalog/types';
import { formatMoney } from '@/shared/lib/formatMoney';
import { ApiErrorMessage } from '@/shared/ui/ApiErrorMessage';
import { ConfirmDialog } from '@/shared/ui/ConfirmDialog';
import { useNotify } from '@/shared/ui/useNotify';
import { Pagination } from '@/shared/ui/Pagination';

const PAGE_SIZE = 20;
const SEARCH_DELAY_MS = 300;
const STATUS_FILTERS: readonly StatusFilter[] = ['ACTIVE', 'ARCHIVED', 'ALL'];

function readQuery(params: URLSearchParams): ProductQuery {
  const status = params.get('status');
  const sort = params.get('sort');
  const page = Number.parseInt(params.get('page') ?? '0', 10);
  return {
    q: params.get('q') ?? '',
    categoryId: params.get('categoryId') ?? '',
    status: status === 'ARCHIVED' || status === 'ALL' ? status : 'ACTIVE',
    sort: PRODUCT_SORTS.includes(sort as ProductSort) ? (sort as ProductSort) : 'name,asc',
    page: Number.isNaN(page) || page < 0 ? 0 : page,
    size: PAGE_SIZE,
  };
}

/**
 * Catalogue des produits : recherche (nom, SKU, code-barres), filtres catégorie et statut, tri,
 * pagination SERVEUR. Les critères sont dans l'URL (partageables, conservés à l'actualisation).
 */
export function ProductsPage() {
  const { t } = useTranslation(['catalog']);
  const [params, setParams] = useSearchParams();
  const query = readQuery(params);
  const { canWrite, canSeeCosts } = useCatalogAccess();
  const products = useProducts(query);
  const categories = useCategories('ACTIVE');
  const notify = useNotify();
  const archive = useCatalogMutation((product: Product) => archiveProduct(product.id));
  const [toArchive, setToArchive] = useState<Product | null>(null);
  const [search, setSearch] = useState(query.q);
  const searchId = useId();
  const categoryId = useId();
  const statusId = useId();
  const sortId = useId();

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

  // Recherche appliquée après une courte pause de saisie.
  useEffect(() => {
    if (search === query.q) return undefined;
    const timer = setTimeout(() => update({ q: search }), SEARCH_DELAY_MS);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [search]);

  const filtered = query.q !== '' || query.categoryId !== '' || query.status !== 'ACTIVE';

  function confirmArchive() {
    if (!toArchive) return;
    const product = toArchive;
    archive.mutate(product, {
      onSuccess: () => {
        notify('success', t('catalog:productArchive.done', { name: product.name }));
        setToArchive(null);
      },
      onError: () => {
        notify('error', t('catalog:productArchive.failed'));
        setToArchive(null);
      },
    });
  }

  return (
    <section className="space-y-6">
      <PageHeader
        title={t('catalog:products.title')}
        description={t('catalog:products.intro')}
        actions={
          canWrite && (
            <Link to="/products/new" className={primaryButtonClassName}>
              <Plus aria-hidden="true" className="mr-2 h-5 w-5" />
              <span>{t('catalog:products.add')}</span>
            </Link>
          )
        }
      />

      <div className={`${panelClassName} grid grid-cols-1 gap-3 p-4 sm:grid-cols-2 lg:grid-cols-[minmax(0,2fr)_1fr_1fr_1fr] lg:items-end`}>
        <div className="sm:col-span-2 lg:col-span-1">
          <label htmlFor={searchId} className="sr-only">
            {t('catalog:products.search')}
          </label>
          <div className="relative">
            <Search aria-hidden="true" className="pointer-events-none absolute left-3 top-3 h-5 w-5 text-ink-muted" />
            <input
              id={searchId}
              type="search"
              value={search}
              maxLength={100}
              onChange={(event) => setSearch(event.target.value)}
              placeholder={t('catalog:products.search')}
              className={`${inputClassName(false)} pl-10`}
            />
          </div>
        </div>
        <div>
          <label htmlFor={categoryId} className="mb-1 block text-xs font-medium text-ink-muted">
            {t('catalog:products.categoryFilter')}
          </label>
          <select
            id={categoryId}
            value={query.categoryId}
            onChange={(event) => update({ categoryId: event.target.value })}
            className={inputClassName(false)}
          >
            <option value="">{t('catalog:products.allCategories')}</option>
            {categories.data?.content.map((category) => (
              <option key={category.id} value={category.id}>
                {category.name}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor={statusId} className="mb-1 block text-xs font-medium text-ink-muted">
            {t('catalog:products.statusFilter')}
          </label>
          <select
            id={statusId}
            value={query.status}
            onChange={(event) => update({ status: event.target.value === 'ACTIVE' ? '' : event.target.value })}
            className={inputClassName(false)}
          >
            {STATUS_FILTERS.map((status) => (
              <option key={status} value={status}>
                {t(`catalog:status.${status}`)}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor={sortId} className="mb-1 block text-xs font-medium text-ink-muted">
            {t('catalog:products.sortLabel')}
          </label>
          <select
            id={sortId}
            value={query.sort}
            onChange={(event) => update({ sort: event.target.value === 'name,asc' ? '' : event.target.value })}
            className={inputClassName(false)}
          >
            {PRODUCT_SORTS.map((sort) => (
              <option key={sort} value={sort}>
                {t(`catalog:products.sort.${sort}`)}
              </option>
            ))}
          </select>
        </div>
      </div>

      {products.isPending ? (
        <div className={`${panelClassName} space-y-4 p-5`}>
          <p role="status" className="flex items-center text-ink-muted">
            <Loader2 aria-hidden="true" className="mr-2 h-5 w-5 animate-spin" />
            <span>{t('catalog:common.loading')}</span>
          </p>
          <SkeletonLines count={4} />
        </div>
      ) : products.isError ? (
        <div className={`${panelClassName} space-y-3 p-5`}>
          <p>{t('catalog:common.loadError')}</p>
          <ApiErrorMessage error={products.error} />
          <button type="button" onClick={() => void products.refetch()} className={secondaryButtonClassName}>
            {t('catalog:common.retry')}
          </button>
        </div>
      ) : products.data.content.length === 0 ? (
        <EmptyState
          icon={PackageOpen}
          title={filtered ? t('catalog:products.empty.filtered') : t('catalog:products.empty.title')}
          description={filtered ? undefined : t('catalog:products.empty.description')}
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
                {t('catalog:products.empty.clearFilters')}
              </button>
            ) : (
              canWrite && (
                <Link to="/products/new" className={primaryButtonClassName}>
                  <Plus aria-hidden="true" className="mr-2 h-5 w-5" />
                  <span>{t('catalog:products.add')}</span>
                </Link>
              )
            )
          }
        />
      ) : (
        <>
          <ul className={`${panelClassName} divide-y divide-line overflow-hidden`}>
            {products.data.content.map((product) => (
              <li key={product.id} className="flex flex-col p-4 transition-colors hover:bg-brand-soft/40 sm:flex-row sm:items-center sm:px-5">
                <span className="mb-2 mr-4 hidden sm:mb-0 sm:block">
                  <Avatar name={product.name} />
                </span>
                <div className="min-w-0 flex-1">
                  <Link
                    to={`/products/${product.id}`}
                    className="font-semibold text-ink underline-offset-2 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
                  >
                    {product.name}
                  </Link>
                  <p className="mt-0.5 text-sm text-ink-muted">
                    {product.category
                      ? product.category.status === 'ARCHIVED'
                        ? t('catalog:products.archivedCategory', { name: product.category.name })
                        : product.category.name
                      : t('catalog:products.noCategory')}
                    {product.sku && <span className="ml-2">· {product.sku}</span>}
                  </p>
                </div>
                <dl className="mt-2 flex flex-wrap items-center text-sm sm:mt-0">
                  <div className="mr-6">
                    <dt className="sr-only">{t('catalog:products.columns.salePrice')}</dt>
                    <dd className="text-base font-bold">{formatMoney(product.salePrice, product.currency)}</dd>
                  </div>
                  {canSeeCosts && product.purchasePrice !== undefined && (
                    <div className="mr-6 text-ink-muted">
                      <dt className="sr-only">{t('catalog:products.columns.purchasePrice')}</dt>
                      <dd>
                        {t('catalog:products.purchasePriceValue', {
                          amount: formatMoney(product.purchasePrice, product.currency),
                        })}
                      </dd>
                    </div>
                  )}
                  <div className="mr-6 text-ink-muted">
                    <dt className="sr-only">{t('catalog:products.columns.unit')}</dt>
                    <dd>{t(`catalog:units.${product.unit}`)}</dd>
                  </div>
                  {product.status === 'ARCHIVED' && (
                    <div className="mr-4">
                      <dt className="sr-only">{t('catalog:products.columns.status')}</dt>
                      <dd>
                        <Badge tone="neutral">{t('catalog:status.ARCHIVED')}</Badge>
                      </dd>
                    </div>
                  )}
                </dl>
                {canWrite && product.status === 'ACTIVE' && (
                  <button
                    type="button"
                    onClick={() => setToArchive(product)}
                    aria-label={t('catalog:products.archiveAction', { name: product.name })}
                    title={t('catalog:common.archive')}
                    className={`${secondaryButtonClassName} mt-3 w-11 px-0 sm:mt-0`}
                  >
                    <Archive aria-hidden="true" className="h-4 w-4" />
                  </button>
                )}
              </li>
            ))}
          </ul>
          <Pagination
            page={products.data.page.number}
            totalPages={products.data.page.totalPages}
            totalElements={products.data.page.totalElements}
            onChange={(page) => update({ page: page === 0 ? '' : String(page) })}
          />
        </>
      )}

      <ConfirmDialog
        open={toArchive !== null}
        title={t('catalog:productArchive.title')}
        description={t('catalog:productArchive.description', { name: toArchive?.name ?? '' })}
        confirmLabel={t('catalog:common.archive')}
        cancelLabel={t('catalog:common.cancel')}
        pending={archive.isPending}
        onConfirm={confirmArchive}
        onCancel={() => setToArchive(null)}
      />
    </section>
  );
}
