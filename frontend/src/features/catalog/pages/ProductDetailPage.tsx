import { Archive, ArrowLeft, Boxes, Loader2, Pencil } from 'lucide-react';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router';

import { archiveProduct, useCatalogMutation, useProduct } from '@/features/catalog/api';
import { useCatalogAccess } from '@/features/catalog/access';
import { hasPermission, useMe } from '@/features/session/api';
import { Avatar } from '@/shared/ui/Avatar';
import { Badge } from '@/shared/ui/Badge';
import { panelClassName, primaryButtonClassName, secondaryButtonClassName } from '@/shared/ui/styles';
import { toApiError } from '@/shared/api/apiError';
import { formatMoney } from '@/shared/lib/formatMoney';
import { ApiErrorMessage } from '@/shared/ui/ApiErrorMessage';
import { ConfirmDialog } from '@/shared/ui/ConfirmDialog';
import { useNotify } from '@/shared/ui/useNotify';

const DATE_FORMAT: Intl.DateTimeFormatOptions = { dateStyle: 'medium', timeStyle: 'short' };

function Detail({ label, children }: { readonly label: string; readonly children: ReactNode }) {
  return (
    <div className="py-3 sm:grid sm:grid-cols-3 sm:gap-4">
      <dt className="text-sm text-ink-muted">{label}</dt>
      <dd className="mt-1 break-words sm:col-span-2 sm:mt-0">{children}</dd>
    </div>
  );
}

/** Fiche produit : informations, prix d'achat si autorisé (retiré par le serveur sinon), actions. */
export function ProductDetailPage() {
  const { t, i18n } = useTranslation(['catalog']);
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const notify = useNotify();
  const { canWrite, canSeeCosts } = useCatalogAccess();
  const product = useProduct(id);
  const archive = useCatalogMutation(() => archiveProduct(id));
  const [confirming, setConfirming] = useState(false);
  const dateFormat = new Intl.DateTimeFormat(i18n.language, DATE_FORMAT);
  const { data: me } = useMe();
  const canReadStock = hasPermission(me, 'STOCK_READ');

  if (product.isPending) {
    return (
      <p role="status" className={`${panelClassName} flex items-center p-5 text-ink-muted`}>
        <Loader2 aria-hidden="true" className="mr-2 h-5 w-5 animate-spin" />
        <span>{t('catalog:common.loading')}</span>
      </p>
    );
  }
  if (product.isError) {
    const notFound = toApiError(product.error).status === 404;
    return (
      <div className="space-y-4">
        {notFound ? <p role="alert">{t('catalog:productDetail.notFound')}</p> : <ApiErrorMessage error={product.error} />}
        <Link to="/products" className={secondaryButtonClassName}>
          <ArrowLeft aria-hidden="true" className="mr-2 h-4 w-4" />
          <span>{t('catalog:common.back')}</span>
        </Link>
      </div>
    );
  }

  const data = product.data;
  const notProvided = <span className="text-ink-muted">{t('catalog:common.notProvided')}</span>;

  return (
    <section className="space-y-6">
      <Link to="/products" className="inline-flex min-h-touch items-center text-sm font-medium text-ink-muted hover:text-ink">
        <ArrowLeft aria-hidden="true" className="mr-2 h-4 w-4" />
        <span>{t('catalog:products.title')}</span>
      </Link>

      <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between">
        <div className="flex min-w-0 items-center">
          <span className="mr-4 hidden sm:block">
            <Avatar name={data.name} />
          </span>
          <div className="min-w-0">
            <h1 className="break-words text-3xl text-ink sm:text-4xl">{data.name}</h1>
            <div aria-hidden="true" className="mt-2 h-0.5 w-12 rounded-full bg-accent" />
          </div>
        </div>
        {canWrite && data.status === 'ACTIVE' && (
          <div className="mt-4 flex flex-none space-x-2 sm:mt-0">
            <Link to={`/products/${data.id}/edit`} className={primaryButtonClassName}>
              <Pencil aria-hidden="true" className="mr-2 h-4 w-4" />
              <span>{t('catalog:common.edit')}</span>
            </Link>
            <button type="button" onClick={() => setConfirming(true)} className={secondaryButtonClassName}>
              <Archive aria-hidden="true" className="mr-2 h-4 w-4" />
              <span>{t('catalog:common.archive')}</span>
            </button>
          </div>
        )}
      </div>

      {data.status === 'ARCHIVED' && (
        <p role="status" className="rounded-md border-l-4 border-accent bg-accent-soft p-3 text-sm">
          {t('catalog:productDetail.archivedNotice')}
        </p>
      )}

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
      <div className={`${panelClassName} overflow-hidden lg:order-2`}>
        <div aria-hidden="true" className="h-1 bg-accent" />
        <div className="p-5">
          <p className="text-xs font-semibold uppercase tracking-[0.1em] text-ink-muted">{t('catalog:productDetail.salePrice')}</p>
          <p className="mt-2 font-display text-4xl leading-none">{formatMoney(data.salePrice, data.currency)}</p>
          <p className="mt-3">
            <Badge tone={data.status === 'ACTIVE' ? 'brand' : 'neutral'}>{t(`catalog:status.${data.status}`)}</Badge>
          </p>
          {canReadStock && data.status === 'ACTIVE' && (
            <Link to={`/stock/${data.id}`} className={`${secondaryButtonClassName} mt-5 w-full`}>
              <Boxes aria-hidden="true" className="mr-2 h-4 w-4" />
              <span>{t('catalog:productDetail.seeStock')}</span>
            </Link>
          )}
        </div>
      </div>
      <dl className={`${panelClassName} divide-y divide-line px-5 lg:order-1 lg:col-span-2`}>
        {canSeeCosts && (
          <Detail label={t('catalog:productDetail.purchasePrice')}>
            {data.purchasePrice !== undefined ? formatMoney(data.purchasePrice, data.currency) : notProvided}
            <p className="mt-1 text-xs text-ink-muted">{t('catalog:productDetail.purchasePriceHint')}</p>
          </Detail>
        )}
        <Detail label={t('catalog:productDetail.category')}>
          {data.category
            ? data.category.status === 'ARCHIVED'
              ? t('catalog:products.archivedCategory', { name: data.category.name })
              : data.category.name
            : t('catalog:products.noCategory')}
        </Detail>
        <Detail label={t('catalog:productDetail.unit')}>{t(`catalog:units.${data.unit}`)}</Detail>
        <Detail label={t('catalog:productDetail.sku')}>{data.sku ?? notProvided}</Detail>
        <Detail label={t('catalog:productDetail.barcode')}>{data.barcode ?? notProvided}</Detail>
        <Detail label={t('catalog:productDetail.description')}>
          {data.description ? <span className="whitespace-pre-line">{data.description}</span> : notProvided}
        </Detail>
        <Detail label={t('catalog:productDetail.status')}>{t(`catalog:status.${data.status}`)}</Detail>
        <Detail label={t('catalog:productDetail.createdAt')}>{dateFormat.format(new Date(data.createdAt))}</Detail>
        <Detail label={t('catalog:productDetail.updatedAt')}>{dateFormat.format(new Date(data.updatedAt))}</Detail>
      </dl>
      </div>

      <ConfirmDialog
        open={confirming}
        title={t('catalog:productArchive.title')}
        description={t('catalog:productArchive.description', { name: data.name })}
        confirmLabel={t('catalog:common.archive')}
        cancelLabel={t('catalog:common.cancel')}
        pending={archive.isPending}
        onCancel={() => setConfirming(false)}
        onConfirm={() =>
          archive.mutate(undefined, {
            onSuccess: () => {
              notify('success', t('catalog:productArchive.done', { name: data.name }));
              void navigate('/products');
            },
            onError: () => {
              setConfirming(false);
              notify('error', t('catalog:productArchive.failed'));
            },
          })
        }
      />
    </section>
  );
}
