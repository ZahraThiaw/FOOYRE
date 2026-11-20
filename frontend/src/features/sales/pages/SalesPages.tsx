import { ArrowLeft, CircleDashed, CircleSlash, Loader2, Plus, ReceiptText, Wallet } from 'lucide-react';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams, useSearchParams } from 'react-router';

import type { MeasurementUnit } from '@/features/catalog/types';
import { useSale, useSales } from '@/features/sales/api';
import { SaleEditor, type InitialCart } from '@/features/sales/components/SaleEditor';
import type { Sale, SaleStatus, SaleSummary } from '@/features/sales/types';
import { hasPermission, useMe } from '@/features/session/api';
import { toApiError } from '@/shared/api/apiError';
import { formatMoney } from '@/shared/lib/formatMoney';
import { formatQuantity } from '@/shared/lib/formatQuantity';
import { ApiErrorMessage } from '@/shared/ui/ApiErrorMessage';
import { Badge, type BadgeTone } from '@/shared/ui/Badge';
import { EmptyState } from '@/shared/ui/EmptyState';
import { PageHeader } from '@/shared/ui/PageHeader';
import { Pagination } from '@/shared/ui/Pagination';
import { SkeletonLines } from '@/shared/ui/Skeleton';
import { buttonClassName, panelClassName } from '@/shared/ui/styles';

const FILTERS: readonly (SaleStatus | 'ALL')[] = ['ALL', 'CONFIRMED', 'DRAFT', 'CANCELLED'];
const DATE_FORMAT: Intl.DateTimeFormatOptions = { dateStyle: 'medium', timeStyle: 'short' };
const TIME_FORMAT: Intl.DateTimeFormatOptions = { timeStyle: 'short' };
const DAY_FORMAT: Intl.DateTimeFormatOptions = { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' };
const EMPTY_CART: InitialCart = { customer: null, lines: [], globalDiscount: '' };

const STATUS_TONES: Record<SaleStatus, BadgeTone> = { CONFIRMED: 'brand', DRAFT: 'accent', CANCELLED: 'neutral' };

function Loading() {
  const { t } = useTranslation(['catalog']);
  return (
    <div className={`${panelClassName} space-y-4 p-5`}>
      <p role="status" className="flex items-center text-ink-muted">
        <Loader2 aria-hidden="true" className="mr-2 h-5 w-5 animate-spin" />
        <span>{t('catalog:common.loading')}</span>
      </p>
      <SkeletonLines count={4} />
    </div>
  );
}

function BackLink() {
  const { t } = useTranslation(['sales']);
  return (
    <Link to="/sales" className="inline-flex min-h-touch items-center text-sm font-medium text-ink-muted hover:text-ink">
      <ArrowLeft aria-hidden="true" className="mr-2 h-4 w-4" />
      <span>{t('sales:title')}</span>
    </Link>
  );
}

/** Badges de statut commercial et de règlement (texte, jamais la couleur seule). */
export function SaleBadges({ sale }: { readonly sale: Pick<SaleSummary, 'status' | 'paymentStatus'> }) {
  const { t } = useTranslation(['sales']);
  return (
    <span className="inline-flex flex-wrap items-center">
      <span className="mb-1 mr-1">
        <Badge tone={STATUS_TONES[sale.status]}>{t(`sales:status.${sale.status}`)}</Badge>
      </span>
      {sale.paymentStatus && (
        <span className="mb-1">
          <Badge
            tone="neutral"
            icon={sale.paymentStatus === 'PAID' ? <Wallet className="h-3 w-3" /> : <CircleDashed className="h-3 w-3" />}
          >
            {t(`sales:payment.${sale.paymentStatus}`)}
          </Badge>
        </span>
      )}
    </span>
  );
}

function SaleRow({ sale, timeOnly }: { readonly sale: SaleSummary; readonly timeOnly: boolean }) {
  const { t, i18n } = useTranslation(['sales']);
  const date = new Date(sale.confirmedAt ?? sale.createdAt);
  const formatted = new Intl.DateTimeFormat(i18n.language, timeOnly ? TIME_FORMAT : DATE_FORMAT).format(date);
  return (
    <Link
      to={`/sales/${sale.id}`}
      className="flex items-center px-4 py-3 transition-colors hover:bg-brand-soft/50 focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand sm:px-5"
    >
      <span
        aria-hidden="true"
        className={`mr-3 hidden h-10 w-10 flex-none items-center justify-center rounded-full sm:flex ${
          sale.status === 'CONFIRMED' ? 'bg-brand text-accent' : 'bg-surface text-ink-muted ring-1 ring-line'
        }`}
      >
        {sale.status === 'CANCELLED' ? <CircleSlash className="h-5 w-5" /> : <ReceiptText className="h-5 w-5" />}
      </span>
      <div className="min-w-0 flex-1">
        <p className="font-semibold">{sale.reference ?? t('sales:list.draftLabel')}</p>
        <p className="truncate text-sm text-ink-muted">
          {sale.customerName ?? t('sales:list.noCustomer')} · {t('sales:list.lines', { count: sale.lineCount })} · {formatted}
        </p>
      </div>
      <div className="ml-3 flex flex-none flex-col items-end">
        {sale.totalInclTax && <span className="font-bold">{formatMoney(sale.totalInclTax, sale.currency)}</span>}
        <SaleBadges sale={sale} />
      </div>
    </Link>
  );
}

/** Liste de ventes ; `groupByDay` : journal regroupé par jour (Aujourd'hui, Hier, date). */
export function SaleList({ items, groupByDay = false }: { readonly items: readonly SaleSummary[]; readonly groupByDay?: boolean }) {
  const { t, i18n } = useTranslation(['sales']);
  if (!groupByDay) {
    return (
      <ul className={`divide-y divide-line overflow-hidden ${panelClassName}`}>
        {items.map((sale) => (
          <li key={sale.id}>
            <SaleRow sale={sale} timeOnly={false} />
          </li>
        ))}
      </ul>
    );
  }
  const dayFormat = new Intl.DateTimeFormat(i18n.language, DAY_FORMAT);
  const today = new Date();
  const yesterday = new Date(today.getFullYear(), today.getMonth(), today.getDate() - 1);
  const groups: { key: string; label: string; sales: SaleSummary[] }[] = [];
  for (const sale of items) {
    const date = new Date(sale.confirmedAt ?? sale.createdAt);
    const key = date.toDateString();
    const label =
      key === today.toDateString()
        ? t('sales:list.today')
        : key === yesterday.toDateString()
          ? t('sales:list.yesterday')
          : dayFormat.format(date);
    const group = groups.find((candidate) => candidate.key === key);
    if (group) group.sales.push(sale);
    else groups.push({ key, label, sales: [sale] });
  }
  return (
    <div className="space-y-5">
      {groups.map((group) => (
        <section key={group.key} aria-label={group.label}>
          <h2 className="mb-2 flex items-center text-xs font-semibold uppercase tracking-[0.12em] text-ink-muted">
            <span>{group.label}</span>
            <span aria-hidden="true" className="ml-3 h-px flex-1 bg-line" />
            <span className="ml-3 normal-case tracking-normal">{t('sales:list.count', { count: group.sales.length })}</span>
          </h2>
          <ul className={`divide-y divide-line overflow-hidden ${panelClassName}`}>
            {group.sales.map((sale) => (
              <li key={sale.id}>
                <SaleRow sale={sale} timeOnly />
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
}

export function SalesPage() {
  const { t } = useTranslation(['sales', 'catalog']);
  const [params, setParams] = useSearchParams();
  const status = FILTERS.find((value) => value === params.get('status')) ?? 'ALL';
  const [page, setPage] = useState(0);
  const sales = useSales(status, page);
  const { data: me } = useMe();
  const canCreate = hasPermission(me, 'SALE_CREATE');
  return (
    <section className="space-y-6">
      <PageHeader
        title={t('sales:title')}
        description={t('sales:intro')}
        actions={
          canCreate && (
            <Link to="/sales/new" className={buttonClassName('primary')}>
              <Plus aria-hidden="true" className="mr-2 h-4 w-4" />
              <span>{t('sales:new')}</span>
            </Link>
          )
        }
      />
      <p className="flex items-start rounded-lg border-l-4 border-accent bg-accent-soft p-3 text-sm text-ink">
        <Wallet aria-hidden="true" className="mr-2 mt-0.5 h-4 w-4 flex-none text-accent-strong" />
        <span>{t('sales:payment.notConnected')}</span>
      </p>
      <div role="group" aria-label={t('sales:list.filter')} className="inline-flex flex-wrap rounded-xl border border-line bg-sheet p-1 shadow-panel">
        {FILTERS.map((value) => (
          <button
            key={value}
            type="button"
            aria-pressed={status === value}
            onClick={() => {
              setPage(0);
              setParams(value === 'ALL' ? {} : { status: value }, { replace: true });
            }}
            className={`min-h-[40px] rounded-lg px-4 text-sm font-semibold transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-brand ${
              status === value ? 'bg-brand text-brand-contrast' : 'text-ink-muted hover:bg-brand-soft hover:text-ink'
            }`}
          >
            {t(`sales:status.${value}`)}
          </button>
        ))}
      </div>
      {sales.isPending ? (
        <Loading />
      ) : sales.isError ? (
        <div className={`${panelClassName} p-5`}>
          <ApiErrorMessage error={sales.error} />
        </div>
      ) : sales.data.content.length === 0 ? (
        <EmptyState
          icon={ReceiptText}
          title={status === 'ALL' ? t('sales:list.empty') : t('sales:list.emptyFiltered')}
          action={
            canCreate &&
            status === 'ALL' && (
              <Link to="/sales/new" className={buttonClassName('primary')}>
                <Plus aria-hidden="true" className="mr-2 h-4 w-4" />
                <span>{t('sales:new')}</span>
              </Link>
            )
          }
        />
      ) : (
        <>
          <SaleList items={sales.data.content} groupByDay />
          <Pagination
            page={sales.data.page.number}
            totalPages={sales.data.page.totalPages}
            totalElements={sales.data.page.totalElements}
            onChange={setPage}
          />
        </>
      )}
    </section>
  );
}

export function NewSalePage() {
  const { t } = useTranslation(['sales', 'catalog']);
  const [params] = useSearchParams();
  // Client présélectionné depuis sa fiche (identifiant vérifié par le serveur à l'enregistrement).
  const customerId = params.get('customerId');
  const customerName = params.get('customerName');
  const initial: InitialCart =
    customerId && customerName ? { ...EMPTY_CART, customer: { id: customerId, label: customerName } } : EMPTY_CART;
  return (
    <section className="space-y-5">
      <BackLink />
      <PageHeader title={t('sales:editor.createTitle')} />
      <SaleEditor initial={initial} />
    </section>
  );
}

function draftCart(sale: Sale): InitialCart {
  return {
    customer: sale.customerId ? { id: sale.customerId, label: sale.customerName ?? '' } : null,
    lines: sale.draftLines.map((line, index) => {
      const quoted = sale.quote?.lines[index];
      return {
        productId: line.productId,
        name: quoted?.productName ?? line.productId,
        unit: (quoted?.unit ?? 'PIECE') as MeasurementUnit,
        quantity: line.quantity,
        discount: line.discount === '0' ? '' : line.discount,
      };
    }),
    globalDiscount: sale.globalDiscount === '0' ? '' : sale.globalDiscount,
  };
}

function Row({ label, children, strong = false }: { readonly label: string; readonly children: ReactNode; readonly strong?: boolean }) {
  return (
    <div className="flex justify-between py-1">
      <dt className="text-ink-muted">{label}</dt>
      <dd className={`ml-4 text-right ${strong ? 'font-semibold' : ''}`}>{children}</dd>
    </div>
  );
}

export function SaleDetailPage() {
  const { t, i18n } = useTranslation(['sales', 'catalog', 'stock']);
  const { id = '' } = useParams();
  const sale = useSale(id);
  const { data: me } = useMe();
  if (sale.isPending) return <Loading />;
  if (sale.isError) {
    return (
      <div className="space-y-4">
        <div className={`${panelClassName} p-5`}>
          {toApiError(sale.error).status === 404 ? (
            <p role="alert" className="font-medium">
              {t('sales:detail.notFound')}
            </p>
          ) : (
            <ApiErrorMessage error={sale.error} />
          )}
        </div>
        <Link to="/sales" className={buttonClassName('secondary')}>
          {t('catalog:common.back')}
        </Link>
      </div>
    );
  }
  const data = sale.data;
  if (data.status === 'DRAFT') {
    return (
      <section className="space-y-5">
        <BackLink />
        <PageHeader title={t('sales:editor.editTitle')} actions={<SaleBadges sale={data} />} />
        {data.quote === null && (
          <p role="alert" className="flex items-start rounded-lg border-l-4 border-danger bg-danger-surface p-3 text-sm font-medium text-danger">
            {t('sales:editor.unavailable')}
          </p>
        )}
        <SaleEditor key={`${data.id}-${data.version}`} existing={data} initial={draftCart(data)} />
      </section>
    );
  }
  const dateFormat = new Intl.DateTimeFormat(i18n.language, DATE_FORMAT);
  const quote = data.quote;
  const hasCosts = data.grossMargin !== null || data.costsComplete !== null;
  return (
    <section className="space-y-5">
      <BackLink />
      {data.status === 'CANCELLED' && (
        <p className="mx-auto max-w-2xl rounded-lg border-l-4 border-accent bg-accent-soft p-3 text-sm">{t('sales:detail.abandonedNotice')}</p>
      )}
      {data.status === 'CONFIRMED' && (
        <p className="mx-auto flex max-w-2xl items-start rounded-lg border-l-4 border-accent bg-accent-soft p-3 text-sm">
          <Wallet aria-hidden="true" className="mr-2 mt-0.5 h-4 w-4 flex-none text-accent-strong" />
          <span>{t('sales:payment.notConnected')}</span>
        </p>
      )}

      {/* Reçu */}
      <article className="mx-auto max-w-2xl overflow-hidden rounded-2xl border border-line bg-sheet shadow-raised">
        <div aria-hidden="true" className="h-1.5 bg-accent" />
        <header className="border-b border-dashed border-ink/25 px-5 pb-5 pt-6 text-center sm:px-8">
          <p className="text-xs font-semibold uppercase tracking-[0.18em] text-accent-strong">
            {me?.activeBusiness?.name ?? t('sales:detail.receipt')}
          </p>
          <h1 className="mt-2 text-3xl sm:text-4xl">{data.reference ?? t('sales:list.draftLabel')}</h1>
          <div className="mt-3 flex justify-center">
            <SaleBadges sale={data} />
          </div>
        </header>
        <div className="px-5 py-4 sm:px-8">
          <dl className="text-sm">
            <Row label={t('sales:detail.date')}>{dateFormat.format(new Date(data.confirmedAt ?? data.createdAt))}</Row>
            <Row label={t('sales:detail.customer')}>
              {data.customerId ? (
                <Link to={`/clients/${data.customerId}`} className="font-medium underline underline-offset-2">
                  {data.customerName}
                </Link>
              ) : (
                t('sales:list.noCustomer')
              )}
            </Row>
            <Row label={t('sales:detail.author')}>{data.authorName ?? ''}</Row>
          </dl>
        </div>
        {quote && (
          <>
            <section className="border-t border-line px-5 py-4 sm:px-8">
              <h2 className="text-xs font-semibold uppercase tracking-[0.12em] text-ink-muted">{t('sales:detail.lines')}</h2>
              <ul className="mt-2 divide-y divide-line">
                {quote.lines.map((line) => (
                  <li key={line.productId} className="flex justify-between py-3 text-sm">
                    <div className="min-w-0 pr-4">
                      <p className="font-semibold">{line.productName}</p>
                      <p className="text-ink-muted">
                        {formatQuantity(line.quantity)} {t(`stock:unitShort.${line.unit as MeasurementUnit}`)} ×{' '}
                        {formatMoney(line.unitPrice, quote.currency)}
                      </p>
                      {hasCosts && (
                        <p className="text-xs text-ink-muted">
                          {t('sales:detail.unitCost')} :{' '}
                          {line.unitCost ? formatMoney(line.unitCost, quote.currency) : t('sales:detail.costUnknown')}
                        </p>
                      )}
                    </div>
                    <p className="flex-none font-semibold">{formatMoney(line.totalInclTax, quote.currency)}</p>
                  </li>
                ))}
              </ul>
            </section>
            <dl className="border-t border-dashed border-ink/25 bg-surface/60 px-5 py-4 text-sm sm:px-8">
              <Row label={t('sales:editor.gross')}>{formatMoney(quote.grossAmount, quote.currency)}</Row>
              <Row label={t('sales:editor.discounts')}>{formatMoney(quote.discountAmount, quote.currency)}</Row>
              {quote.vatEnabled && (
                <>
                  <Row label={t('sales:editor.totalExclTax')}>{formatMoney(quote.totalExclTax, quote.currency)}</Row>
                  <Row label={t('sales:editor.tax', { rate: quote.vatRate })}>{formatMoney(quote.taxAmount, quote.currency)}</Row>
                </>
              )}
              <div className="mt-2 flex items-baseline justify-between border-t border-line pt-3">
                <dt className="font-semibold">{t('sales:editor.total')}</dt>
                <dd className="font-display text-3xl">{formatMoney(quote.totalInclTax, quote.currency)}</dd>
              </div>
              {quote.vatEnabled && <p className="mt-1 text-xs text-ink-muted">{t('sales:editor.vatProvisional')}</p>}
            </dl>
            {hasCosts && (
              <div className="border-t border-line px-5 py-4 text-sm sm:px-8">
                {data.grossMargin !== null ? (
                  <dl>
                    <Row label={t('sales:detail.grossMargin')} strong>
                      {formatMoney(data.grossMargin, quote.currency)}
                    </Row>
                  </dl>
                ) : (
                  <p className="text-xs">{t('sales:detail.marginUnavailable')}</p>
                )}
                <p className="mt-1 text-xs text-ink-muted">{t('sales:detail.marginHint')}</p>
              </div>
            )}
          </>
        )}
      </article>
    </section>
  );
}
