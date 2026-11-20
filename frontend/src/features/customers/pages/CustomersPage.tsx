import { Loader2, Mail, Phone, Plus, Search, UsersRound } from 'lucide-react';
import { useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router';

import { Avatar } from '@/shared/ui/Avatar';
import { Badge } from '@/shared/ui/Badge';
import { EmptyState } from '@/shared/ui/EmptyState';
import { PageHeader } from '@/shared/ui/PageHeader';
import { SkeletonLines } from '@/shared/ui/Skeleton';
import { inputClassName, panelClassName, primaryButtonClassName, secondaryButtonClassName } from '@/shared/ui/styles';
import type { StatusFilter } from '@/features/catalog/types';
import { useCustomers } from '@/features/customers/api';
import { CUSTOMER_SORTS, customerLabel, type CustomerQuery, type CustomerSort } from '@/features/customers/types';
import { hasPermission, useMe } from '@/features/session/api';
import { ApiErrorMessage } from '@/shared/ui/ApiErrorMessage';
import { Pagination } from '@/shared/ui/Pagination';

const PAGE_SIZE = 20;
const SEARCH_DELAY_MS = 300;
const STATUS_FILTERS: readonly StatusFilter[] = ['ACTIVE', 'ARCHIVED', 'ALL'];

function readQuery(params: URLSearchParams): CustomerQuery {
  const status = params.get('status') as StatusFilter | null;
  const sort = params.get('sort') as CustomerSort | null;
  const page = Number.parseInt(params.get('page') ?? '0', 10);
  return {
    q: params.get('q') ?? '',
    status: status && STATUS_FILTERS.includes(status) ? status : 'ACTIVE',
    sort: sort && CUSTOMER_SORTS.includes(sort) ? sort : 'name,asc',
    page: Number.isNaN(page) || page < 0 ? 0 : page,
    size: PAGE_SIZE,
  };
}

/** Liste des clients : recherche différée, filtres et tri dans l'URL, pagination côté serveur. */
export function CustomersPage() {
  const { t } = useTranslation(['customers', 'catalog']);
  const [params, setParams] = useSearchParams();
  const query = readQuery(params);
  const { data: me } = useMe();
  const canWrite = hasPermission(me, 'CUSTOMER_WRITE');
  const list = useCustomers(query);
  const [search, setSearch] = useState(query.q);
  const ids = { search: useId(), status: useId(), sort: useId() };
  // Paramètres À JOUR : la recherche différée ne doit jamais écraser un filtre choisi entre-temps
  // (le paramètre de la fonction de mise à jour de react-router reflète le rendu d'origine).
  const latestParams = useRef(params);
  useEffect(() => {
    latestParams.current = params;
  }, [params]);

  function update(changes: Partial<Record<'q' | 'status' | 'sort' | 'page', string>>) {
    setParams(
      () => {
        const next = new URLSearchParams(latestParams.current);
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

  const filtered = query.q !== '' || query.status !== 'ACTIVE';

  return (
    <section className="space-y-6">
      <PageHeader
        title={t('customers:title')}
        description={t('customers:intro')}
        actions={
          canWrite && (
            <Link to="/clients/new" className={primaryButtonClassName}>
              <Plus aria-hidden="true" className="mr-2 h-4 w-4" />
              <span>{t('customers:new')}</span>
            </Link>
          )
        }
      />

      <div className={`${panelClassName} grid grid-cols-1 gap-3 p-4 sm:grid-cols-4 sm:items-end`}>
        <div className="sm:col-span-2">
          <label htmlFor={ids.search} className="sr-only">
            {t('customers:search')}
          </label>
          <div className="relative">
            <Search aria-hidden="true" className="pointer-events-none absolute left-3 top-3 h-5 w-5 text-ink-muted" />
            <input
              id={ids.search}
              type="search"
              value={search}
              maxLength={100}
              onChange={(event) => setSearch(event.target.value)}
              placeholder={t('customers:search')}
              className={`${inputClassName(false)} pl-10`}
            />
          </div>
        </div>
        <div>
          <label htmlFor={ids.status} className="mb-1 block text-xs font-medium text-ink-muted">
            {t('customers:status.label')}
          </label>
          <select
            id={ids.status}
            value={query.status}
            onChange={(event) => update({ status: event.target.value === 'ACTIVE' ? '' : event.target.value })}
            className={inputClassName(false)}
          >
            {STATUS_FILTERS.map((status) => (
              <option key={status} value={status}>
                {t(`customers:filters.${status}`)}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor={ids.sort} className="mb-1 block text-xs font-medium text-ink-muted">
            {t('customers:sort.label')}
          </label>
          <select
            id={ids.sort}
            value={query.sort}
            onChange={(event) => update({ sort: event.target.value === 'name,asc' ? '' : event.target.value })}
            className={inputClassName(false)}
          >
            {CUSTOMER_SORTS.map((sort) => (
              <option key={sort} value={sort}>
                {t(`customers:sort.${sort}`)}
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
          icon={UsersRound}
          title={filtered ? t('customers:empty.noResult') : t('customers:empty.none')}
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
                {t('customers:empty.clear')}
              </button>
            ) : (
              canWrite && (
                <Link to="/clients/new" className={primaryButtonClassName}>
                  <Plus aria-hidden="true" className="mr-2 h-4 w-4" />
                  <span>{t('customers:new')}</span>
                </Link>
              )
            )
          }
        />
      ) : (
        <>
          <ul className={`${panelClassName} divide-y divide-line overflow-hidden`}>
            {list.data.content.map((customer) => (
              <li key={customer.id}>
                <Link
                  to={`/clients/${customer.id}`}
                  className="flex items-center p-4 transition-colors hover:bg-brand-soft/40 focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand sm:px-5"
                >
                  <span className="mr-4 flex-none">
                    <Avatar name={customerLabel(customer)} />
                  </span>
                  <div className="min-w-0 flex-1">
                    <p className="break-words font-semibold">{customerLabel(customer)}</p>
                    <div className="mt-1 flex flex-col text-sm text-ink-muted sm:flex-row sm:flex-wrap">
                      {customer.phone && (
                        <span className="inline-flex items-center sm:mr-4">
                          <Phone aria-hidden="true" className="mr-1 h-3.5 w-3.5" />
                          {customer.phone}
                        </span>
                      )}
                      {customer.email && (
                        <span className="inline-flex min-w-0 items-center break-all">
                          <Mail aria-hidden="true" className="mr-1 h-3.5 w-3.5 flex-none" />
                          {customer.email}
                        </span>
                      )}
                      {!customer.phone && !customer.email && <span>{t('customers:list.noContact')}</span>}
                    </div>
                  </div>
                  {customer.status === 'ARCHIVED' && (
                    <span className="ml-3 flex-none">
                      <Badge tone="neutral">{t('customers:status.ARCHIVED')}</Badge>
                    </span>
                  )}
                </Link>
              </li>
            ))}
          </ul>
          <Pagination
            page={list.data.page.number}
            totalPages={list.data.page.totalPages}
            totalElements={list.data.page.totalElements}
            onChange={(page) => update({ page: page === 0 ? '' : String(page) })}
          />
        </>
      )}
    </section>
  );
}
