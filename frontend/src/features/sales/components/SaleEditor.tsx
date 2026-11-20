import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { AlertCircle, Loader2, Minus, PackageSearch, Plus, Search, ShoppingBag, Trash2, UserRound, X } from 'lucide-react';
import { useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';

import { fetchProducts } from '@/features/catalog/api';
import { UNIT_QUANTITY_DECIMALS, type MeasurementUnit, type PageResponse } from '@/features/catalog/types';
import { useCustomers } from '@/features/customers/api';
import { customerLabel } from '@/features/customers/types';
import { quoteSale, useCancelDraft, useSaveSale } from '@/features/sales/api';
import type { Sale, SaleInput } from '@/features/sales/types';
import { hasPermission, useMe } from '@/features/session/api';
import type { StockItem } from '@/features/stock/types';
import { toApiError } from '@/shared/api/apiError';
import { httpClient } from '@/shared/api/httpClient';
import { addDecimal, compareDecimal, subtractDecimal } from '@/shared/lib/decimalMath';
import { currencyFractionDigits, normalizeDecimal, validateDecimal } from '@/shared/lib/decimalInput';
import { formatMoney } from '@/shared/lib/formatMoney';
import { formatQuantity } from '@/shared/lib/formatQuantity';
import { newIdempotencyKey } from '@/shared/lib/idempotencyKey';
import { ApiErrorMessage } from '@/shared/ui/ApiErrorMessage';
import { Button } from '@/shared/ui/Button';
import { ConfirmDialog } from '@/shared/ui/ConfirmDialog';
import { Skeleton } from '@/shared/ui/Skeleton';
import { buttonClassName, inputClassName, panelClassName } from '@/shared/ui/styles';
import { useNotify } from '@/shared/ui/useNotify';

const SEARCH_DELAY_MS = 300;
const PRODUCT_PAGE = 12;
const ZERO = '0';
const ONE = '1';
/** Couleur de l'indication de stock (classes Tailwind, jamais la couleur seule : texte associé). */
const STOCK_TONES: Record<StockItem['status'], string> = {
  OUT_OF_STOCK: 'text-danger font-semibold',
  LOW_STOCK: 'text-accent-strong font-semibold',
  IN_STOCK: 'text-ink-muted',
  NOT_INITIALIZED: 'text-ink-muted',
};

interface CartLine {
  readonly productId: string;
  readonly name: string;
  readonly unit: MeasurementUnit;
  readonly quantity: string;
  readonly discount: string;
}

export interface InitialCart {
  readonly customer: { id: string; label: string } | null;
  readonly lines: readonly CartLine[];
  readonly globalDiscount: string;
}

function useDebounced<T>(value: T, delay: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delay);
    return () => clearTimeout(timer);
  }, [value, delay]);
  return debounced;
}

/** Quantité saisie valide et strictement positive (sinon `null`). */
function validQuantity(raw: string, unit: MeasurementUnit): string | null {
  if (validateDecimal(raw, UNIT_QUANTITY_DECIMALS[unit], true) !== null) return null;
  const value = normalizeDecimal(raw);
  return compareDecimal(value, ZERO) > 0 ? value : null;
}

/**
 * Saisie d'une vente, présentée comme une caisse : catalogue à gauche, ticket à droite (en bas sur
 * mobile, avec le total toujours visible). Panier local ; montants TOUJOURS demandés au serveur
 * (`/sales/quote`) ; confirmation explicite. Aucune règle métier n'est appliquée ici : le serveur
 * contrôle prix, remises, stock et droits.
 */
export function SaleEditor({ existing, initial }: { readonly existing?: Sale; readonly initial: InitialCart }) {
  const { t } = useTranslation(['sales', 'catalog', 'stock']);
  const navigate = useNavigate();
  const notify = useNotify();
  const { data: me } = useMe();
  const currency = me?.activeBusiness?.currency ?? 'XOF';
  const moneyDecimals = currencyFractionDigits(currency);
  const canDiscount = hasPermission(me, 'SALE_DISCOUNT_UNLIMITED') || hasPermission(me, 'SALE_DISCOUNT_LIMITED');
  const canReadStock = hasPermission(me, 'STOCK_READ');
  const [customer, setCustomer] = useState(initial.customer);
  const [lines, setLines] = useState<CartLine[]>([...initial.lines]);
  const [globalDiscount, setGlobalDiscount] = useState(initial.globalDiscount);
  const [customerQuery, setCustomerQuery] = useState('');
  const [productQuery, setProductQuery] = useState('');
  const [confirming, setConfirming] = useState(false);
  const [abandoning, setAbandoning] = useState(false);
  // Une clé par action, créée à l'ouverture et réutilisée en cas de nouvel envoi.
  const [createKey] = useState(newIdempotencyKey);
  const [confirmKey] = useState(newIdempotencyKey);
  const save = useSaveSale();
  const cancel = useCancelDraft(existing?.id ?? '');
  const ids = { customer: useId(), product: useId(), cart: useId(), global: useId(), summary: useId() };

  const debouncedCustomer = useDebounced(customerQuery.trim(), SEARCH_DELAY_MS);
  const customers = useCustomers({ q: debouncedCustomer, status: 'ACTIVE', sort: 'name,asc', page: 0, size: 5 });
  const debouncedProduct = useDebounced(productQuery.trim(), SEARCH_DELAY_MS);
  // Catalogue de caisse : produits actifs (recherche facultative), quantités en stock à titre indicatif.
  const products = useQuery({
    queryKey: ['sales', 'product-search', debouncedProduct],
    queryFn: () =>
      fetchProducts({ q: debouncedProduct, categoryId: '', status: 'ACTIVE', sort: 'name,asc', page: 0, size: PRODUCT_PAGE }),
    placeholderData: keepPreviousData,
  });
  const stock = useQuery({
    queryKey: ['stock', 'sale-picker', debouncedProduct],
    queryFn: async () => {
      const params: Record<string, string | number> = { status: 'ALL', sort: 'name,asc', page: 0, size: PRODUCT_PAGE };
      if (debouncedProduct !== '') params.q = debouncedProduct;
      return (await httpClient.get<PageResponse<StockItem>>('/stock', { params })).data;
    },
    enabled: canReadStock,
    placeholderData: keepPreviousData,
  });
  const stockById = new Map((stock.data?.content ?? []).map((item) => [item.productId, item]));

  const lineError = (line: CartLine): string | null => {
    if (validQuantity(line.quantity, line.unit) === null) return t('sales:editor.errors.quantity');
    if (validateDecimal(line.discount, moneyDecimals, false)) return t('sales:editor.errors.discount');
    return null;
  };
  const globalError = validateDecimal(globalDiscount, moneyDecimals, false) ? t('sales:editor.errors.discount') : null;
  const valid = lines.length > 0 && lines.every((line) => lineError(line) === null) && globalError === null;
  const input: SaleInput = {
    customerId: customer?.id ?? null,
    lines: lines.map((line) => ({
      productId: line.productId,
      quantity: normalizeDecimal(line.quantity),
      discount: line.discount.trim() === '' ? null : normalizeDecimal(line.discount),
    })),
    globalDiscount: globalDiscount.trim() === '' ? null : normalizeDecimal(globalDiscount),
  };
  const debouncedInput = useDebounced(JSON.stringify(input), SEARCH_DELAY_MS);
  const quote = useQuery({
    queryKey: ['sales', 'quote', debouncedInput],
    queryFn: () => quoteSale(JSON.parse(debouncedInput) as SaleInput),
    enabled: valid,
    retry: false,
  });

  /** Ajoute le produit, ou une unité de plus s'il est déjà dans le panier. */
  function addProduct(product: { id: string; name: string; unit: MeasurementUnit }) {
    setLines((current) => {
      const existingLine = current.find((line) => line.productId === product.id);
      if (!existingLine) {
        return [...current, { productId: product.id, name: product.name, unit: product.unit, quantity: ONE, discount: '' }];
      }
      const quantity = validQuantity(existingLine.quantity, existingLine.unit);
      if (quantity === null) return current;
      return current.map((line) => (line.productId === product.id ? { ...line, quantity: addDecimal(quantity, ONE) } : line));
    });
  }

  const updateLine = (productId: string, changes: Partial<CartLine>) =>
    setLines((current) => current.map((line) => (line.productId === productId ? { ...line, ...changes } : line)));

  function step(line: CartLine, direction: 1 | -1) {
    const quantity = validQuantity(line.quantity, line.unit);
    if (quantity === null) return;
    const next = direction === 1 ? addDecimal(quantity, ONE) : subtractDecimal(quantity, ONE);
    if (compareDecimal(next, ZERO) > 0) updateLine(line.productId, { quantity: next });
  }

  function submit(confirm: boolean) {
    save.mutate(
      { existing, input, createKey, confirmKey: confirm ? confirmKey : null },
      {
        onSuccess: (sale) => {
          setConfirming(false);
          notify('success', confirm ? t('sales:editor.confirmed', { reference: sale.reference }) : t('sales:editor.saved'));
          void navigate(`/sales/${sale.id}`, { replace: true });
        },
        onError: () => {
          setConfirming(false);
          notify('error', t('sales:editor.failed'));
        },
      },
    );
  }

  const summary = quote.data;
  const busy = save.isPending || cancel.isPending;
  const saveError = save.error && toApiError(save.error).status !== null ? save.error : null;
  const quantityInCart = (productId: string) => lines.find((line) => line.productId === productId)?.quantity;

  return (
    <div className="pb-24 lg:grid lg:grid-cols-[minmax(0,1fr)_22rem] lg:gap-6 lg:pb-0 xl:grid-cols-[minmax(0,1fr)_25rem]">
      <div className="min-w-0 space-y-5">
        {/* Client */}
        <section aria-labelledby={ids.customer} className={`${panelClassName} p-4 sm:p-5`}>
          <h2 id={ids.customer} className="flex items-center text-base font-semibold">
            <UserRound aria-hidden="true" className="mr-2 h-5 w-5 text-accent-strong" />
            <span>{t('sales:editor.customer')}</span>
          </h2>
          {customer ? (
            <div className="mt-3 flex items-center justify-between rounded-lg border border-accent bg-accent-soft px-3 py-2">
              <p className="min-w-0 truncate font-semibold">{customer.label}</p>
              <button
                type="button"
                onClick={() => setCustomer(null)}
                className={`${buttonClassName('ghost', 'sm')} ml-3 flex-none`}
              >
                <X aria-hidden="true" className="mr-1 h-4 w-4" />
                <span>{t('sales:editor.changeCustomer')}</span>
              </button>
            </div>
          ) : (
            <div className="mt-3 space-y-2">
              <label htmlFor={`${ids.customer}-q`} className="sr-only">
                {t('sales:editor.customerSearch')}
              </label>
              <div className="relative">
                <Search aria-hidden="true" className="pointer-events-none absolute left-3 top-3 h-5 w-5 text-ink-muted" />
                <input
                  id={`${ids.customer}-q`}
                  type="search"
                  value={customerQuery}
                  onChange={(event) => setCustomerQuery(event.target.value)}
                  placeholder={t('sales:editor.customerSearch')}
                  className={`${inputClassName(false)} pl-10`}
                />
              </div>
              <p className="text-xs text-ink-muted">
                <span className="font-medium text-ink">{t('sales:editor.noCustomer')}</span> — {t('sales:editor.walkIn')}
              </p>
              {debouncedCustomer !== '' && customers.data && (
                <ul className="divide-y divide-line overflow-hidden rounded-lg border border-line">
                  {customers.data.content.map((found) => (
                    <li key={found.id} className="flex items-center justify-between px-3 py-2">
                      <span className="min-w-0">
                        <span className="block truncate font-medium">{customerLabel(found)}</span>
                        {found.name && found.phone && <span className="block text-xs text-ink-muted">{found.phone}</span>}
                      </span>
                      <button
                        type="button"
                        onClick={() => {
                          setCustomer({ id: found.id, label: customerLabel(found) });
                          setCustomerQuery('');
                        }}
                        className={`${buttonClassName('secondary', 'sm')} ml-3 flex-none`}
                      >
                        {t('sales:editor.chooseCustomer')}
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}
        </section>

        {/* Catalogue de caisse */}
        <section aria-labelledby={ids.product} className={`${panelClassName} p-4 sm:p-5`}>
          <div className="flex flex-wrap items-baseline justify-between">
            <h2 id={ids.product} className="text-base font-semibold">
              {t('sales:editor.catalogTitle')}
            </h2>
            <p className="text-xs text-ink-muted">{t('sales:editor.catalogHint')}</p>
          </div>
          <div className="relative mt-3">
            <Search aria-hidden="true" className="pointer-events-none absolute left-3 top-3 h-5 w-5 text-ink-muted" />
            <label htmlFor={`${ids.product}-q`} className="sr-only">
              {t('sales:editor.productSearch')}
            </label>
            <input
              id={`${ids.product}-q`}
              type="search"
              value={productQuery}
              onChange={(event) => setProductQuery(event.target.value)}
              placeholder={t('sales:editor.productSearch')}
              className={`${inputClassName(false)} pl-10`}
            />
          </div>
          <div className="mt-3">
            {products.isPending ? (
              <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
                {Array.from({ length: 6 }, (_, index) => (
                  <Skeleton key={index} className="h-20 w-full rounded-lg" />
                ))}
              </div>
            ) : products.isError ? (
              <ApiErrorMessage error={products.error} />
            ) : products.data.content.length === 0 ? (
              <p className="flex items-center rounded-lg border border-dashed border-ink/20 px-3 py-4 text-sm text-ink-muted">
                <PackageSearch aria-hidden="true" className="mr-2 h-5 w-5 flex-none" />
                <span>{t('sales:editor.noProductResult')}</span>
              </p>
            ) : (
              <>
                <ul className="grid grid-cols-2 gap-2 sm:grid-cols-3">
                  {products.data.content.map((product) => {
                    const inCart = quantityInCart(product.id);
                    const stockItem = stockById.get(product.id);
                    const stockText = !stockItem
                      ? null
                      : !stockItem.initialized || stockItem.quantity === null
                        ? t('sales:editor.stockNotTracked')
                        : stockItem.status === 'OUT_OF_STOCK'
                          ? t('sales:editor.stockOut')
                          : t('sales:editor.stockAvailable', {
                              quantity: `${formatQuantity(stockItem.quantity)} ${t(`stock:unitShort.${stockItem.unit}`)}`,
                            });
                    const stockTone = STOCK_TONES[stockItem?.status ?? 'IN_STOCK'];
                    return (
                      <li key={product.id}>
                        <button
                          type="button"
                          onClick={() => addProduct(product)}
                          aria-label={`${t('sales:editor.add')} · ${product.name}`}
                          className={`flex h-full w-full flex-col rounded-lg border p-3 text-left transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-brand ${
                            inCart ? 'border-brand bg-brand-soft' : 'border-line bg-sheet hover:border-ink/30 hover:bg-surface'
                          }`}
                        >
                          <span className="line-clamp-2 text-sm font-semibold leading-snug">{product.name}</span>
                          <span className="mt-1 text-sm font-bold">{formatMoney(product.salePrice, product.currency)}</span>
                          {stockText && <span className={`mt-1 text-xs ${stockTone}`}>{stockText}</span>}
                          <span className="mt-auto flex items-center pt-2 text-xs font-semibold text-accent-strong">
                            {inCart ? (
                              <span>{t('sales:editor.inCart', { quantity: inCart })}</span>
                            ) : (
                              <>
                                <Plus aria-hidden="true" className="mr-1 h-3.5 w-3.5" />
                                <span>{t('sales:editor.add')}</span>
                              </>
                            )}
                          </span>
                        </button>
                      </li>
                    );
                  })}
                </ul>
                {products.data.page.totalElements > products.data.content.length && (
                  <p className="mt-2 text-xs text-ink-muted">{t('sales:editor.moreResults')}</p>
                )}
              </>
            )}
          </div>
        </section>

        {/* Panier */}
        <section aria-labelledby={ids.cart} className={`${panelClassName} p-4 sm:p-5`}>
          <h2 id={ids.cart} className="flex items-center text-base font-semibold">
            <ShoppingBag aria-hidden="true" className="mr-2 h-5 w-5 text-accent-strong" />
            <span>{t('sales:editor.cartTitle')}</span>
            {lines.length > 0 && (
              <span className="ml-2 rounded-full bg-brand px-2 py-0.5 text-xs font-semibold text-brand-contrast">
                {t('sales:editor.itemCount', { count: lines.length })}
              </span>
            )}
          </h2>
          {lines.length === 0 ? (
            <p className="mt-3 rounded-lg border border-dashed border-ink/20 px-3 py-6 text-center text-sm text-ink-muted">
              {t('sales:editor.emptyCart')}
            </p>
          ) : (
            <ul className="mt-2 divide-y divide-line">
              {lines.map((line, index) => {
                const error = lineError(line);
                const quoted = summary?.lines[index];
                const unitShort = t(`stock:unitShort.${line.unit}`);
                return (
                  <li key={line.productId} className="py-3">
                    <div className="flex items-start justify-between">
                      <div className="min-w-0">
                        <p className="font-semibold">{line.name}</p>
                        {quoted && (
                          <p className="text-sm text-ink-muted">
                            {t('sales:editor.unitPrice')} : {formatMoney(quoted.unitPrice, currency)}
                          </p>
                        )}
                      </div>
                      <button
                        type="button"
                        onClick={() => setLines((current) => current.filter((item) => item.productId !== line.productId))}
                        aria-label={t('sales:editor.remove', { name: line.name })}
                        className="-mr-2 inline-flex min-h-touch min-w-touch flex-none items-center justify-center rounded-lg text-ink-muted hover:bg-danger-surface hover:text-danger focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
                      >
                        <Trash2 aria-hidden="true" className="h-5 w-5" />
                      </button>
                    </div>
                    <div className="mt-2 flex flex-wrap items-end">
                      <div className="mb-2 mr-4">
                        <label htmlFor={`${ids.product}-qty-${index}`} className="mb-1 block text-xs font-medium text-ink-muted">
                          {t('sales:editor.quantity')} ({unitShort})
                        </label>
                        <div className="flex items-center">
                          <button
                            type="button"
                            onClick={() => step(line, -1)}
                            aria-label={t('sales:editor.decrease', { name: line.name })}
                            className="inline-flex h-11 w-11 items-center justify-center rounded-l-lg border border-line bg-surface hover:bg-brand-soft focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
                          >
                            <Minus aria-hidden="true" className="h-4 w-4" />
                          </button>
                          <input
                            id={`${ids.product}-qty-${index}`}
                            inputMode="decimal"
                            value={line.quantity}
                            onChange={(event) => updateLine(line.productId, { quantity: event.target.value })}
                            aria-invalid={error !== null}
                            className={`h-11 w-20 border-y bg-sheet px-2 text-center font-semibold focus:outline-none focus:ring-2 focus:ring-accent/60 ${
                              error ? 'border-2 border-danger' : 'border-line'
                            }`}
                          />
                          <button
                            type="button"
                            onClick={() => step(line, 1)}
                            aria-label={t('sales:editor.increase', { name: line.name })}
                            className="inline-flex h-11 w-11 items-center justify-center rounded-r-lg border border-line bg-surface hover:bg-brand-soft focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
                          >
                            <Plus aria-hidden="true" className="h-4 w-4" />
                          </button>
                        </div>
                      </div>
                      {canDiscount && (
                        <div className="mb-2 mr-4 w-32">
                          <label htmlFor={`${ids.product}-disc-${index}`} className="mb-1 block text-xs font-medium text-ink-muted">
                            {t('sales:editor.discount')} ({currency})
                          </label>
                          <input
                            id={`${ids.product}-disc-${index}`}
                            inputMode="decimal"
                            value={line.discount}
                            onChange={(event) => updateLine(line.productId, { discount: event.target.value })}
                            className={inputClassName(false)}
                          />
                        </div>
                      )}
                      <div className="mb-2 ml-auto text-right">
                        <p className="text-xs text-ink-muted">{t('sales:editor.lineTotal')}</p>
                        <p className="text-base font-bold">{quoted ? formatMoney(quoted.totalInclTax, currency) : '—'}</p>
                      </div>
                    </div>
                    {error && (
                      <p role="alert" className="flex items-center text-sm font-medium text-danger">
                        <AlertCircle aria-hidden="true" className="mr-1.5 h-4 w-4 flex-none" />
                        <span>{error}</span>
                      </p>
                    )}
                  </li>
                );
              })}
            </ul>
          )}
          {canDiscount && lines.length > 0 && (
            <div className="mt-2 max-w-xs border-t border-line pt-3">
              <label htmlFor={ids.global} className="mb-1 block text-xs font-medium text-ink-muted">
                {t('sales:editor.globalDiscount')} ({currency})
              </label>
              <input
                id={ids.global}
                inputMode="decimal"
                value={globalDiscount}
                onChange={(event) => setGlobalDiscount(event.target.value)}
                className={inputClassName(globalError !== null)}
              />
            </div>
          )}
        </section>
      </div>

      {/* Ticket */}
      <aside id={ids.summary} className="mt-5 lg:mt-0">
        <div className="overflow-hidden rounded-2xl border border-line bg-sheet shadow-raised lg:sticky lg:top-24">
          <div aria-hidden="true" className="h-1 bg-accent" />
          <section className="space-y-3 p-5" aria-live="polite">
            <h2 className="font-display text-xl leading-snug">{t('sales:editor.summary')}</h2>
            {customer && <p className="text-sm text-ink-muted">{customer.label}</p>}
            {lines.length === 0 && <p className="text-sm text-ink-muted">{t('sales:editor.emptyCart')}</p>}
            {quote.isFetching && !summary && (
              <div className="space-y-2" role="status">
                <p className="flex items-center text-sm text-ink-muted">
                  <Loader2 aria-hidden="true" className="mr-2 h-4 w-4 animate-spin" />
                  <span>{t('sales:editor.calculating')}</span>
                </p>
                <Skeleton className="h-4 w-full" />
                <Skeleton className="h-8 w-2/3" />
              </div>
            )}
            {quote.isError && <ApiErrorMessage error={quote.error} />}
            {summary && valid && (
              <dl className="space-y-1.5 text-sm">
                <div className="flex justify-between">
                  <dt className="text-ink-muted">{t('sales:editor.gross')}</dt>
                  <dd>{formatMoney(summary.grossAmount, currency)}</dd>
                </div>
                <div className="flex justify-between">
                  <dt className="text-ink-muted">{t('sales:editor.discounts')}</dt>
                  <dd>{formatMoney(summary.discountAmount, currency)}</dd>
                </div>
                {summary.vatEnabled ? (
                  <>
                    <div className="flex justify-between">
                      <dt className="text-ink-muted">{t('sales:editor.totalExclTax')}</dt>
                      <dd>{formatMoney(summary.totalExclTax, currency)}</dd>
                    </div>
                    <div className="flex justify-between">
                      <dt className="text-ink-muted">{t('sales:editor.tax', { rate: summary.vatRate })}</dt>
                      <dd>{formatMoney(summary.taxAmount, currency)}</dd>
                    </div>
                  </>
                ) : null}
                <div className="mt-2 flex items-baseline justify-between border-t border-dashed border-ink/25 pt-3">
                  <dt className="font-semibold">{t('sales:editor.total')}</dt>
                  <dd className="font-display text-3xl">{formatMoney(summary.totalInclTax, currency)}</dd>
                </div>
                <p className="pt-1 text-xs text-ink-muted">
                  {summary.vatEnabled ? t('sales:editor.vatProvisional') : t('sales:editor.vatDisabled')}
                </p>
                {!summary.discountWithinLimit && (
                  <p role="alert" className="flex items-start rounded-lg bg-danger-surface p-2 text-sm font-medium text-danger">
                    <AlertCircle aria-hidden="true" className="mr-1.5 mt-0.5 h-4 w-4 flex-none" />
                    <span>{t('sales:editor.discountTooHigh')}</span>
                  </p>
                )}
              </dl>
            )}
            <p className="text-xs text-ink-muted">{t('sales:editor.serverDecides')}</p>

            {saveError && (
              <div className="rounded-lg border border-l-4 border-danger/30 border-l-danger bg-danger-surface p-3 text-sm">
                <ApiErrorMessage error={saveError} />
                <p className="mt-1 text-ink-muted">{t('sales:editor.stockRefused')}</p>
              </div>
            )}

            <div className="space-y-2 pt-2">
              <Button
                className="w-full"
                onClick={() => setConfirming(true)}
                disabled={!valid || busy || !summary || !summary.discountWithinLimit}
                loading={save.isPending}
              >
                {t('sales:editor.confirm')}
              </Button>
              <Button variant="secondary" className="w-full" onClick={() => submit(false)} disabled={!valid || busy}>
                {t('sales:editor.saveDraft')}
              </Button>
              {existing && (
                <Button variant="ghost" className="w-full text-danger hover:bg-danger-surface" onClick={() => setAbandoning(true)} disabled={busy}>
                  {t('sales:editor.abandon')}
                </Button>
              )}
            </div>
          </section>
        </div>
      </aside>

      {/* Mobile : total toujours visible, accès direct au ticket. */}
      {lines.length > 0 && (
        <div className="fixed inset-x-0 bottom-0 z-20 border-t border-line bg-sheet/95 px-4 py-3 shadow-raised lg:hidden">
          <div className="mx-auto flex max-w-3xl items-center justify-between">
            <div>
              <p className="text-xs text-ink-muted">
                {t('sales:editor.totalShort')} · {t('sales:editor.itemCount', { count: lines.length })}
              </p>
              <p className="font-display text-xl">{summary && valid ? formatMoney(summary.totalInclTax, currency) : '—'}</p>
            </div>
            <a href={`#${ids.summary}`} className={buttonClassName('accent', 'sm')}>
              {t('sales:editor.goToSummary')}
            </a>
          </div>
        </div>
      )}

      <ConfirmDialog
        open={confirming}
        title={t('sales:editor.confirmTitle')}
        description={t('sales:editor.confirmDescription', {
          total: summary ? formatMoney(summary.totalInclTax, currency) : '',
        })}
        confirmLabel={t('sales:editor.confirm')}
        cancelLabel={t('catalog:common.cancel')}
        pending={save.isPending}
        onCancel={() => setConfirming(false)}
        onConfirm={() => submit(true)}
      />
      <ConfirmDialog
        open={abandoning}
        title={t('sales:editor.abandonTitle')}
        description={t('sales:editor.abandonDescription')}
        confirmLabel={t('sales:editor.abandon')}
        cancelLabel={t('catalog:common.cancel')}
        pending={cancel.isPending}
        onCancel={() => setAbandoning(false)}
        onConfirm={() =>
          cancel.mutate(undefined, {
            onSuccess: () => {
              notify('success', t('sales:editor.abandoned'));
              void navigate('/sales');
            },
            onError: () => {
              setAbandoning(false);
              notify('error', t('sales:editor.failed'));
            },
          })
        }
      />
    </div>
  );
}
