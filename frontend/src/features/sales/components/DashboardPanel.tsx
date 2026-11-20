import { useQuery } from '@tanstack/react-query';
import { ArrowRight, BarChart3, Boxes, LineChart, ReceiptText, Wallet } from 'lucide-react';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

import type { PageResponse } from '@/features/catalog/types';
import { useDashboard } from '@/features/sales/api';
import { SaleList } from '@/features/sales/pages/SalesPages';
import { hasPermission, useMe } from '@/features/session/api';
import { useStockMovements } from '@/features/stock/api';
import { StockStatusBadge } from '@/features/stock/components/StockStatusBadge';
import type { StockItem, StockSummary } from '@/features/stock/types';
import { httpClient } from '@/shared/api/httpClient';
import { formatMoney } from '@/shared/lib/formatMoney';
import { formatQuantity } from '@/shared/lib/formatQuantity';
import { ApiErrorMessage } from '@/shared/ui/ApiErrorMessage';
import { Panel } from '@/shared/ui/Panel';
import { Skeleton } from '@/shared/ui/Skeleton';
import { panelClassName } from '@/shared/ui/styles';

const ALERTS_SHOWN = 5;
const MINUS = '−';
const PLUS = '+';
const MOVEMENTS_SHOWN = 5;
const DATE_FORMAT: Intl.DateTimeFormatOptions = { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' };

/** Indicateur du bandeau : libellé, valeur (déjà formatée par le serveur / `formatMoney`), aide. */
function Kpi({ label, value, hint, to }: { readonly label: string; readonly value: ReactNode; readonly hint?: string; readonly to?: string }) {
  const body = (
    <>
      <dt className="text-xs font-semibold uppercase tracking-[0.1em] text-ink-muted">{label}</dt>
      <dd className="mt-2 font-display text-3xl leading-none text-ink">{value}</dd>
      {hint && <dd className="mt-2 text-xs text-ink-muted">{hint}</dd>}
    </>
  );
  return (
    <div className="p-4 sm:p-5">
      {to ? (
        <Link to={to} className="block rounded-lg focus:outline-none focus-visible:ring-2 focus-visible:ring-brand">
          <dl>{body}</dl>
        </Link>
      ) : (
        <dl>{body}</dl>
      )}
    </div>
  );
}

/** Donnée non fournie par l'API : état explicite, jamais un faux graphique. */
function Unavailable({ icon: Icon, text }: { readonly icon: typeof LineChart; readonly text: string }) {
  const { t } = useTranslation(['sales']);
  return (
    <div className="flex flex-col items-center rounded-lg border border-dashed border-ink/20 px-4 py-8 text-center">
      <span className="flex h-11 w-11 items-center justify-center rounded-full bg-accent-soft ring-1 ring-accent">
        <Icon aria-hidden="true" className="h-5 w-5" strokeWidth={1.75} />
      </span>
      <p className="mt-3 text-xs font-semibold uppercase tracking-[0.12em] text-accent-strong">{t('sales:dashboard.notAvailableYet')}</p>
      <p className="mt-1 max-w-sm text-sm text-ink-muted">{text}</p>
    </div>
  );
}

/** Barre segmentée de l'état du stock : effectifs RÉELS de /stock/summary (largeurs proportionnelles). */
function StockStateBar({ summary }: { readonly summary: StockSummary }) {
  const { t } = useTranslation(['stock', 'sales']);
  const segments = [
    { key: 'IN_STOCK', label: t('stock:summary.inStock'), count: summary.inStock, className: 'bg-brand' },
    { key: 'LOW_STOCK', label: t('stock:summary.lowStock'), count: summary.lowStock, className: 'bg-accent' },
    { key: 'OUT_OF_STOCK', label: t('stock:summary.outOfStock'), count: summary.outOfStock, className: 'bg-danger' },
    { key: 'NOT_INITIALIZED', label: t('stock:status.NOT_INITIALIZED'), count: summary.notInitializedProducts, className: 'bg-line' },
  ] as const;
  // Effectifs entiers (nombre de produits) : la conversion ne sert qu'à dimensionner la barre.
  const total = segments.reduce((sum, segment) => sum + segment.count, 0);
  if (total === 0) return <p className="text-sm text-ink-muted">{t('sales:dashboard.stockStateEmpty')}</p>;
  return (
    <div>
      <div aria-hidden="true" className="flex h-3 overflow-hidden rounded-full bg-surface">
        {segments
          .filter((segment) => segment.count > 0)
          .map((segment) => (
            <span key={segment.key} className={segment.className} style={{ width: `${(segment.count / total) * 100}%` }} />
          ))}
      </div>
      <dl className="mt-4 grid grid-cols-2 gap-3">
        {segments.map((segment) => (
          <div key={segment.key} className="flex items-center">
            <span aria-hidden="true" className={`mr-2 h-2.5 w-2.5 flex-none rounded-full ${segment.className}`} />
            <dt className="min-w-0 flex-1 truncate text-sm text-ink-muted">{segment.label}</dt>
            <dd className="ml-2 font-semibold">{segment.count}</dd>
          </div>
        ))}
      </dl>
    </div>
  );
}

/**
 * Tableau de bord : uniquement des chiffres réels calculés par le serveur (aucune donnée
 * inventée, aucun calcul financier dans le navigateur). Les analyses que l'API ne fournit pas
 * encore (évolution, classement des produits) sont signalées explicitement.
 */
export function DashboardPanel() {
  const { t, i18n } = useTranslation(['sales', 'stock']);
  const { data: me } = useMe();
  const canReadStock = hasPermission(me, 'STOCK_READ');
  const dashboard = useDashboard(true);
  const summary = useQuery({
    queryKey: ['stock', 'summary'],
    queryFn: async () => (await httpClient.get<StockSummary>('/stock/summary')).data,
    enabled: canReadStock,
  });
  const alerts = useQuery({
    queryKey: ['stock', 'alerts', ALERTS_SHOWN],
    queryFn: async () =>
      (await httpClient.get<PageResponse<StockItem>>('/stock/alerts', { params: { page: 0, size: ALERTS_SHOWN } })).data,
    enabled: canReadStock,
  });
  const movements = useStockMovements('', 0, MOVEMENTS_SHOWN, canReadStock);

  if (dashboard.isError) {
    return (
      <div className={`${panelClassName} p-5`}>
        <p className="font-medium">{t('sales:dashboard.loadError')}</p>
        <ApiErrorMessage error={dashboard.error} />
      </div>
    );
  }
  const data = dashboard.data;
  if (!data) {
    return (
      <div className="space-y-4" aria-hidden="true">
        <Skeleton className="h-28 w-full rounded-xl" />
        <div className="grid gap-4 lg:grid-cols-3">
          <Skeleton className="h-64 w-full rounded-xl lg:col-span-2" />
          <Skeleton className="h-64 w-full rounded-xl" />
        </div>
      </div>
    );
  }
  const financial = data.financialsVisible && data.today && data.month ? { today: data.today, month: data.month } : null;
  const dateFormat = new Intl.DateTimeFormat(i18n.language, DATE_FORMAT);
  const alertCount = data.outOfStock + data.lowStock;

  return (
    <section aria-labelledby="dashboard-title" className="space-y-6">
      <h2 id="dashboard-title" className="sr-only">
        {t('sales:dashboard.title')}
      </h2>

      {/* Bandeau d'indicateurs : une seule surface. */}
      <div className={`${panelClassName} overflow-hidden`}>
        <div aria-hidden="true" className="h-1 bg-accent" />
        <div className="grid grid-cols-1 divide-y divide-line sm:grid-cols-2 sm:divide-x sm:divide-y-0 xl:grid-cols-4">
          {financial ? (
            <>
              <Kpi
                label={t('sales:dashboard.revenueToday')}
                value={formatMoney(financial.today.revenueInclTax, financial.today.currency)}
                hint={t('sales:dashboard.revenueHint')}
              />
              <Kpi
                label={t('sales:dashboard.revenueMonth')}
                value={formatMoney(financial.month.revenueInclTax, financial.month.currency)}
                hint={t('sales:dashboard.revenueHint')}
              />
            </>
          ) : (
            <Kpi label={t('sales:dashboard.salesToday')} value={data.salesCountToday} to="/sales?status=CONFIRMED" />
          )}
          <Kpi
            label={t('sales:dashboard.salesMonth')}
            value={data.salesCountMonth}
            hint={t('sales:dashboard.salesCountHint', { count: data.salesCountToday })}
            to="/sales?status=CONFIRMED"
          />
          {financial && financial.month.grossMargin !== null ? (
            <Kpi
              label={t('sales:dashboard.marginMonth')}
              value={formatMoney(financial.month.grossMargin, financial.month.currency)}
              hint={t('sales:dashboard.marginHint')}
            />
          ) : financial && !financial.month.marginReliable && financial.month.salesCount > 0 ? (
            <Kpi label={t('sales:dashboard.marginMonth')} value="—" hint={t('sales:dashboard.marginUnknown')} />
          ) : (
            <Kpi label={t('sales:dashboard.customers')} value={data.activeCustomers} to="/clients" />
          )}
        </div>
        <div className="flex flex-wrap items-center justify-between border-t border-line bg-surface/60 px-4 py-2.5 text-xs text-ink-muted sm:px-5">
          {financial ? (
            <p className="flex items-center">
              <Wallet aria-hidden="true" className="mr-1.5 h-3.5 w-3.5 text-accent-strong" />
              <span>
                {t('sales:dashboard.collections')} : {t('sales:dashboard.collectionsHint')}
              </span>
            </p>
          ) : (
            <p>{t('sales:dashboard.employeeNote')}</p>
          )}
          <p className="mt-1 sm:mt-0">{t('sales:dashboard.updated')}</p>
        </div>
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        <div className="lg:col-span-2">
          <Panel
            title={t('sales:dashboard.recentSales')}
            flush
            actions={
              <Link to="/sales" className="inline-flex items-center text-sm font-semibold text-accent-strong hover:text-ink">
                <span>{t('sales:dashboard.seeAll')}</span>
                <ArrowRight aria-hidden="true" className="ml-1 h-4 w-4" />
              </Link>
            }
          >
            {data.recentSales.length === 0 ? (
              <p className="flex items-center px-5 py-8 text-sm text-ink-muted">
                <ReceiptText aria-hidden="true" className="mr-2 h-5 w-5 flex-none" />
                <span>{t('sales:dashboard.noSales')}</span>
              </p>
            ) : (
              <div className="[&>ul]:rounded-none [&>ul]:border-0 [&>ul]:shadow-none">
                <SaleList items={data.recentSales} />
              </div>
            )}
          </Panel>
        </div>

        {canReadStock && (
          <Panel
            title={t('sales:dashboard.watchTitle')}
            actions={
              <Link to="/stock?status=ALERT" className="inline-flex items-center text-sm font-semibold text-accent-strong hover:text-ink">
                <span>{t('sales:dashboard.seeStock')}</span>
                <ArrowRight aria-hidden="true" className="ml-1 h-4 w-4" />
              </Link>
            }
          >
            <div className="grid grid-cols-2 gap-3">
              <Link
                to="/stock?status=OUT_OF_STOCK"
                className="rounded-lg border border-danger/25 bg-danger-surface p-3 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
              >
                <span className="block text-xs font-semibold text-danger">{t('sales:dashboard.outOfStock')}</span>
                <span className="mt-1 block font-display text-3xl text-danger">{data.outOfStock}</span>
              </Link>
              <Link
                to="/stock?status=LOW_STOCK"
                className="rounded-lg border border-accent bg-accent-soft p-3 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
              >
                <span className="block text-xs font-semibold text-accent-strong">{t('sales:dashboard.lowStock')}</span>
                <span className="mt-1 block font-display text-3xl">{data.lowStock}</span>
              </Link>
            </div>
            {alertCount === 0 ? (
              <p className="mt-4 text-sm text-ink-muted">{t('sales:dashboard.watchEmpty')}</p>
            ) : (
              <ul className="mt-4 divide-y divide-line">
                {alerts.data?.content.map((item) => (
                  <li key={item.productId} className="flex items-center justify-between py-2">
                    <Link to={`/stock/${item.productId}`} className="min-w-0 truncate pr-2 text-sm font-medium hover:underline">
                      {item.productName}
                    </Link>
                    <span className="flex flex-none items-center">
                      <span className="mr-2 text-sm font-semibold">
                        {formatQuantity(item.quantity ?? '0')} {t(`stock:unitShort.${item.unit}`)}
                      </span>
                      <StockStatusBadge status={item.status} />
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </Panel>
        )}
      </div>

      {financial && (
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
          <div className="lg:col-span-2">
            <Panel title={t('sales:dashboard.trendTitle')}>
              <Unavailable icon={LineChart} text={t('sales:dashboard.trendUnavailable')} />
            </Panel>
          </div>
          <Panel title={t('sales:dashboard.topTitle')}>
            <Unavailable icon={BarChart3} text={t('sales:dashboard.topUnavailable')} />
          </Panel>
        </div>
      )}

      {canReadStock && (
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
          <Panel title={t('sales:dashboard.stockStateTitle')}>
            {summary.data ? <StockStateBar summary={summary.data} /> : <Skeleton className="h-16 w-full" />}
          </Panel>
          <div className="lg:col-span-2">
            <Panel
              title={t('sales:dashboard.movementsTitle')}
              actions={
                <Link to="/stock/history" className="inline-flex items-center text-sm font-semibold text-accent-strong hover:text-ink">
                  <span>{t('stock:historyLink')}</span>
                  <ArrowRight aria-hidden="true" className="ml-1 h-4 w-4" />
                </Link>
              }
            >
              {movements.data && movements.data.content.length > 0 ? (
                <ul className="divide-y divide-line">
                  {movements.data.content.map((movement) => {
                    const out = movement.type === 'EXIT' || movement.type === 'ADJUSTMENT_OUT';
                    const sign = out ? MINUS : PLUS;
                    return (
                      <li key={movement.id} className="flex items-center py-2.5">
                        <span
                          aria-hidden="true"
                          className={`mr-3 flex h-8 w-8 flex-none items-center justify-center rounded-full ${
                            out ? 'bg-surface text-ink-muted ring-1 ring-line' : 'bg-brand-soft text-ink'
                          }`}
                        >
                          <Boxes className="h-4 w-4" />
                        </span>
                        <div className="min-w-0 flex-1">
                          <p className="truncate text-sm font-medium">{movement.productName}</p>
                          <p className="text-xs text-ink-muted">
                            {t(`stock:movementTypes.${movement.type}`)} · {dateFormat.format(new Date(movement.createdAt))}
                          </p>
                        </div>
                        <span className={`ml-3 flex-none text-sm font-semibold ${out ? 'text-ink-muted' : 'text-ink'}`}>
                          {sign}
                          {formatQuantity(movement.quantity)} {movement.unit ? t(`stock:unitShort.${movement.unit}`) : ''}
                        </span>
                      </li>
                    );
                  })}
                </ul>
              ) : movements.isPending ? (
                <Skeleton className="h-24 w-full" />
              ) : (
                <p className="text-sm text-ink-muted">{t('sales:dashboard.noMovements')}</p>
              )}
            </Panel>
          </div>
        </div>
      )}
    </section>
  );
}
