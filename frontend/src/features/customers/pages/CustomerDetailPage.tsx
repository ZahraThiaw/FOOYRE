import { Archive, ArrowLeft, Loader2, Pencil, Plus, ReceiptText } from 'lucide-react';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router';

import { Avatar } from '@/shared/ui/Avatar';
import { Badge } from '@/shared/ui/Badge';
import { panelClassName, primaryButtonClassName, secondaryButtonClassName } from '@/shared/ui/styles';
import { useArchiveCustomer, useCustomer } from '@/features/customers/api';
import { useSales } from '@/features/sales/api';
import { SaleList } from '@/features/sales/pages/SalesPages';
import { customerLabel } from '@/features/customers/types';
import { hasPermission, useMe } from '@/features/session/api';
import { toApiError } from '@/shared/api/apiError';
import { ApiErrorMessage } from '@/shared/ui/ApiErrorMessage';
import { ConfirmDialog } from '@/shared/ui/ConfirmDialog';
import { useNotify } from '@/shared/ui/useNotify';

const DATE_FORMAT: Intl.DateTimeFormatOptions = { dateStyle: 'medium', timeStyle: 'short' };
const TEL_PREFIX = 'tel:';
const MAIL_PREFIX = 'mailto:';

function Detail({ label, children }: { readonly label: string; readonly children: ReactNode }) {
  return (
    <div className="py-3 sm:grid sm:grid-cols-3 sm:gap-4">
      <dt className="text-sm text-ink-muted">{label}</dt>
      <dd className="mt-1 break-words sm:col-span-2 sm:mt-0">{children}</dd>
    </div>
  );
}

/** Fiche client : coordonnées, notes, actions autorisées, emplacement de l'historique commercial. */
export function CustomerDetailPage() {
  const { t, i18n } = useTranslation(['customers', 'catalog']);
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const notify = useNotify();
  const { data: me } = useMe();
  const customer = useCustomer(id);
  const archive = useArchiveCustomer(id);
  const [confirming, setConfirming] = useState(false);
  const dateFormat = new Intl.DateTimeFormat(i18n.language, DATE_FORMAT);

  if (customer.isPending) {
    return (
      <p role="status" className="flex items-center text-ink-muted">
        <Loader2 aria-hidden="true" className="mr-2 h-5 w-5 animate-spin" />
        <span>{t('catalog:common.loading')}</span>
      </p>
    );
  }
  if (customer.isError) {
    const notFound = toApiError(customer.error).status === 404;
    return (
      <div className="space-y-4">
        {notFound ? <p role="alert">{t('customers:detail.notFound')}</p> : <ApiErrorMessage error={customer.error} />}
        <Link to="/clients" className={secondaryButtonClassName}>
          <ArrowLeft aria-hidden="true" className="mr-2 h-4 w-4" />
          <span>{t('catalog:common.back')}</span>
        </Link>
      </div>
    );
  }

  const data = customer.data;
  const label = customerLabel(data);
  const active = data.status === 'ACTIVE';
  const canWrite = active && hasPermission(me, 'CUSTOMER_WRITE');
  const canArchive = active && hasPermission(me, 'CUSTOMER_ARCHIVE');
  const notProvided = <span className="text-ink-muted">{t('catalog:common.notProvided')}</span>;

  return (
    <section className="space-y-6">
      <Link to="/clients" className="inline-flex min-h-touch items-center text-sm font-medium text-ink-muted hover:text-ink">
        <ArrowLeft aria-hidden="true" className="mr-2 h-4 w-4" />
        <span>{t('customers:title')}</span>
      </Link>

      <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between">
        <div className="flex min-w-0 items-center">
          <span className="mr-4 flex-none">
            <Avatar name={label} />
          </span>
          <div className="min-w-0">
            <h1 className="break-words text-3xl text-ink sm:text-4xl">{label}</h1>
            <p className="mt-2">
              <Badge tone={active ? 'brand' : 'neutral'}>{t(`customers:status.${data.status}`)}</Badge>
            </p>
          </div>
        </div>
        {(canWrite || canArchive) && (
          <div className="mt-4 flex flex-none space-x-2 sm:mt-0">
            {canWrite && (
              <Link to={`/clients/${data.id}/edit`} className={primaryButtonClassName}>
                <Pencil aria-hidden="true" className="mr-2 h-4 w-4" />
                <span>{t('catalog:common.edit')}</span>
              </Link>
            )}
            {canArchive && (
              <button type="button" onClick={() => setConfirming(true)} className={secondaryButtonClassName}>
                <Archive aria-hidden="true" className="mr-2 h-4 w-4" />
                <span>{t('catalog:common.archive')}</span>
              </button>
            )}
          </div>
        )}
      </div>

      {!active && (
        <p role="status" className="rounded-md border-l-4 border-accent bg-accent-soft p-3 text-sm">
          {t('customers:detail.archivedNotice')}
        </p>
      )}

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-5">
      <dl className={`${panelClassName} divide-y divide-line self-start px-5 lg:col-span-2 [&>div]:sm:grid-cols-1 [&>div]:lg:block`}>
        <Detail label={t('customers:detail.phone')}>
          {data.phone ? (
            <a href={TEL_PREFIX + data.phone.replace(/[^+0-9]/g, '')} className="font-medium underline">
              {data.phone}
            </a>
          ) : (
            notProvided
          )}
        </Detail>
        <Detail label={t('customers:detail.email')}>
          {data.email ? (
            <a href={MAIL_PREFIX + data.email} className="break-all font-medium underline">
              {data.email}
            </a>
          ) : (
            notProvided
          )}
        </Detail>
        <Detail label={t('customers:detail.address')}>
          {data.address ? <span className="whitespace-pre-line">{data.address}</span> : notProvided}
        </Detail>
        <Detail label={t('customers:detail.notes')}>
          {data.notes ? <span className="whitespace-pre-line">{data.notes}</span> : notProvided}
        </Detail>
        <Detail label={t('customers:detail.status')}>{t(`customers:status.${data.status}`)}</Detail>
        <Detail label={t('customers:detail.createdAt')}>{dateFormat.format(new Date(data.createdAt))}</Detail>
        <Detail label={t('customers:detail.updatedAt')}>{dateFormat.format(new Date(data.updatedAt))}</Detail>
        {data.archivedAt && (
          <Detail label={t('customers:detail.archivedAt')}>{dateFormat.format(new Date(data.archivedAt))}</Detail>
        )}
      </dl>

      <section aria-labelledby="customer-history-title" className={`${panelClassName} self-start p-4 sm:p-5 lg:col-span-3`}>
        <h2 id="customer-history-title" className="flex items-center text-base font-semibold">
          <ReceiptText aria-hidden="true" className="mr-2 h-5 w-5 text-accent-strong" />
          <span>{t('customers:detail.history')}</span>
        </h2>
        <CustomerSales customerId={data.id} label={label} active={active} />
      </section>
      </div>

      <ConfirmDialog
        open={confirming}
        title={t('customers:archive.title')}
        description={t('customers:archive.description', { name: label })}
        confirmLabel={t('catalog:common.archive')}
        cancelLabel={t('catalog:common.cancel')}
        pending={archive.isPending}
        onCancel={() => setConfirming(false)}
        onConfirm={() =>
          archive.mutate(undefined, {
            onSuccess: () => {
              notify('success', t('customers:archive.done', { name: label }));
              void navigate('/clients');
            },
            onError: () => {
              setConfirming(false);
              notify('error', t('customers:archive.failed'));
            },
          })
        }
      />
    </section>
  );
}

/** Historique commercial : ventes CONFIRMÉES du client (données réelles). */
function CustomerSales({ customerId, label, active }: { readonly customerId: string; readonly label: string; readonly active: boolean }) {
  const { t } = useTranslation(['customers', 'sales']);
  const { data: me } = useMe();
  const sales = useSales('CONFIRMED', 0, customerId);
  const params = new URLSearchParams({ customerId, customerName: label });
  return (
    <div className="mt-3 space-y-3">
      {active && hasPermission(me, 'SALE_CREATE') && (
        <Link to={`/sales/new?${params.toString()}`} className={primaryButtonClassName}>
          <Plus aria-hidden="true" className="mr-2 h-4 w-4" />
          <span>{t('sales:new')}</span>
        </Link>
      )}
      {sales.isError && <ApiErrorMessage error={sales.error} />}
      {sales.data && sales.data.content.length === 0 && <p className="text-sm text-ink-muted">{t('customers:detail.noSales')}</p>}
      {sales.data && sales.data.content.length > 0 && <SaleList items={sales.data.content} />}
    </div>
  );
}
