import { ArrowLeft, Loader2 } from 'lucide-react';
import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';

import { FormField } from '@/features/catalog/components/FormField';
import {
  describedBy,
  inputClassName,
  primaryButtonClassName,
  secondaryButtonClassName,
} from '@/features/catalog/components/formStyles';
import { useCustomer, useSaveCustomer } from '@/features/customers/api';
import { customerLabel, type Customer, type CustomerInput } from '@/features/customers/types';
import { toApiError } from '@/shared/api/apiError';
import { fieldErrorMessageKey } from '@/shared/api/errorMessages';
import { newIdempotencyKey } from '@/shared/lib/idempotencyKey';
import { ApiErrorMessage } from '@/shared/ui/ApiErrorMessage';
import { useNotify } from '@/shared/ui/useNotify';

/** Mêmes limites que le serveur (le serveur reste seul juge). */
const LIMITS = { name: 150, phone: 30, email: 254, address: 500, notes: 2000 } as const;
const PHONE_CHARACTERS = /^[+0-9 ().-]+$/;
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const MIN_PHONE_DIGITS = 6;
const MAX_PHONE_DIGITS = 15;

/** Identifiants des contrôles (techniques, non affichés). */
const IDS = {
  name: 'customer-name',
  phone: 'customer-phone',
  email: 'customer-email',
  address: 'customer-address',
  notes: 'customer-notes',
} as const;

type FieldName = keyof typeof IDS;
type Values = Record<FieldName, string>;
type Errors = Partial<Record<FieldName, string>>;

const optional = (value: string) => (value.trim() === '' ? null : value.trim());

function validPhone(phone: string): boolean {
  const digits = phone.replace(/\D/g, '').length;
  return (
    PHONE_CHARACTERS.test(phone) &&
    (phone.match(/\+/g) ?? []).length <= 1 &&
    digits >= MIN_PHONE_DIGITS &&
    digits <= MAX_PHONE_DIGITS
  );
}

/** Création (`/clients/new`) et modification (`/clients/:id/edit`) d'un client. */
export function CustomerFormPage() {
  const { id } = useParams();
  const { t } = useTranslation(['catalog']);
  const existing = useCustomer(id ?? '');

  if (id !== undefined) {
    if (existing.isPending) {
      return (
        <p role="status" className="flex items-center text-ink-muted">
          <Loader2 aria-hidden="true" className="mr-2 h-5 w-5 animate-spin" />
          <span>{t('catalog:common.loading')}</span>
        </p>
      );
    }
    if (existing.isError) {
      return <ApiErrorMessage error={existing.error} />;
    }
    return <CustomerForm key={existing.data.id} customer={existing.data} />;
  }
  return <CustomerForm customer={undefined} />;
}

function CustomerForm({ customer }: { readonly customer: Customer | undefined }) {
  const { t } = useTranslation(['customers', 'catalog', 'apiErrors']);
  const navigate = useNavigate();
  const notify = useNotify();
  // Clé générée une fois par formulaire : réutilisée si l'envoi est répété (double clic, coupure).
  const [idempotencyKey] = useState(newIdempotencyKey);
  const save = useSaveCustomer(customer, idempotencyKey);
  const [prefill] = useSearchParams();
  const [values, setValues] = useState<Values>(() => ({
    name: customer?.name ?? prefill.get('name') ?? '',
    phone: customer?.phone ?? prefill.get('phone') ?? '',
    email: customer?.email ?? '',
    address: customer?.address ?? '',
    notes: customer?.notes ?? '',
  }));
  const [errors, setErrors] = useState<Errors>({});
  const backTo = customer ? `/clients/${customer.id}` : '/clients';

  const set = (field: FieldName) => (value: string) => {
    setValues((current) => ({ ...current, [field]: value }));
    setErrors((current) => ({ ...current, [field]: undefined }));
  };

  function validate(): Errors {
    const found: Errors = {};
    const tooLong = (max: number) => t('customers:form.errors.tooLong', { max });
    const name = values.name.trim();
    const phone = values.phone.trim();
    const email = values.email.trim();
    if (name === '' && phone === '') found.name = t('customers:form.errors.identity');
    if (name.length > LIMITS.name) found.name = tooLong(LIMITS.name);
    if (phone.length > LIMITS.phone) found.phone = tooLong(LIMITS.phone);
    else if (phone !== '' && !validPhone(phone)) found.phone = t('customers:form.errors.phoneFormat');
    if (email.length > LIMITS.email) found.email = tooLong(LIMITS.email);
    else if (email !== '' && !EMAIL.test(email)) found.email = t('customers:form.errors.emailFormat');
    if (values.address.trim().length > LIMITS.address) found.address = tooLong(LIMITS.address);
    if (values.notes.trim().length > LIMITS.notes) found.notes = tooLong(LIMITS.notes);
    return found;
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const found = validate();
    setErrors(found);
    const firstInvalid = Object.keys(found)[0] as FieldName | undefined;
    if (firstInvalid) {
      document.getElementById(IDS[firstInvalid])?.focus();
      return;
    }
    const input: CustomerInput = {
      name: optional(values.name),
      phone: optional(values.phone),
      email: optional(values.email),
      address: optional(values.address),
      notes: optional(values.notes),
      ...(customer ? { version: customer.version } : {}),
    };
    save.mutate(input, {
      onSuccess: (saved) => {
        notify(
          'success',
          t(customer ? 'customers:form.updated' : 'customers:form.created', { name: customerLabel(saved) }),
        );
        void navigate(`/clients/${saved.id}`);
      },
      onError: (error) => {
        const fieldErrors: Errors = {};
        toApiError(error).fieldErrors.forEach((fieldError) => {
          if (fieldError.field in IDS) {
            fieldErrors[fieldError.field as FieldName] =
              fieldError.field === 'name' && fieldError.code === 'REQUIRED'
                ? t('customers:form.errors.identity')
                : t(fieldErrorMessageKey(fieldError));
          }
        });
        setErrors(fieldErrors);
        notify('error', t('customers:form.saveFailed'));
      },
    });
  }

  const serverError = save.error && toApiError(save.error).fieldErrors.length === 0 ? save.error : null;
  const field = (name: FieldName) => ({ id: IDS[name], error: errors[name] ?? null });
  const optionalLabel = t('catalog:common.optional');

  return (
    <section className="mx-auto max-w-2xl space-y-6">
      <Link to={backTo} className="inline-flex min-h-touch items-center text-sm font-medium text-ink-muted hover:text-ink">
        <ArrowLeft aria-hidden="true" className="mr-2 h-4 w-4" />
        <span>{t('catalog:common.back')}</span>
      </Link>
      <div className="space-y-1">
        <h1 className="text-3xl text-ink sm:text-4xl">
          {customer ? t('customers:form.editTitle') : t('customers:form.createTitle')}
        </h1>
        <p className="text-sm text-ink-muted">{t('customers:form.identityHint')}</p>
      </div>

      {serverError && (
        <div className="rounded-lg border border-l-4 border-danger/30 border-l-danger bg-danger-surface p-3 text-sm">
          <ApiErrorMessage error={serverError} />
        </div>
      )}

      <form noValidate onSubmit={handleSubmit} className="space-y-5 rounded-xl border border-line bg-sheet shadow-panel p-4 sm:p-6">
        <FormField {...field('name')} label={t('customers:form.name')}>
          <input
            id={IDS.name}
            value={values.name}
            onChange={(event) => set('name')(event.target.value)}
            maxLength={LIMITS.name}
            autoComplete="off"
            className={inputClassName(Boolean(errors.name))}
            {...describedBy(IDS.name, Boolean(errors.name), false)}
          />
        </FormField>

        <div className="grid grid-cols-1 gap-5 sm:grid-cols-2">
          <FormField {...field('phone')} label={t('customers:form.phone')} hint={t('customers:form.phoneHint')}>
            <input
              id={IDS.phone}
              type="tel"
              inputMode="tel"
              value={values.phone}
              onChange={(event) => set('phone')(event.target.value)}
              maxLength={LIMITS.phone}
              autoComplete="off"
              className={inputClassName(Boolean(errors.phone))}
              {...describedBy(IDS.phone, Boolean(errors.phone), true)}
            />
          </FormField>
          <FormField {...field('email')} label={t('customers:form.email')} optionalLabel={optionalLabel}>
            <input
              id={IDS.email}
              type="email"
              inputMode="email"
              value={values.email}
              onChange={(event) => set('email')(event.target.value)}
              maxLength={LIMITS.email}
              autoComplete="off"
              className={inputClassName(Boolean(errors.email))}
              {...describedBy(IDS.email, Boolean(errors.email), false)}
            />
          </FormField>
        </div>

        <FormField {...field('address')} label={t('customers:form.address')} optionalLabel={optionalLabel}>
          <textarea
            id={IDS.address}
            rows={2}
            value={values.address}
            onChange={(event) => set('address')(event.target.value)}
            maxLength={LIMITS.address}
            className={`${inputClassName(Boolean(errors.address))} py-2`}
            {...describedBy(IDS.address, Boolean(errors.address), false)}
          />
        </FormField>

        <FormField
          {...field('notes')}
          label={t('customers:form.notes')}
          optionalLabel={optionalLabel}
          hint={t('customers:form.notesHint')}
        >
          <textarea
            id={IDS.notes}
            rows={3}
            value={values.notes}
            onChange={(event) => set('notes')(event.target.value)}
            maxLength={LIMITS.notes}
            className={`${inputClassName(Boolean(errors.notes))} py-2`}
            {...describedBy(IDS.notes, Boolean(errors.notes), true)}
          />
        </FormField>

        <div className="flex flex-col-reverse sm:flex-row sm:justify-end">
          <Link to={backTo} className={`${secondaryButtonClassName} mt-2 sm:mr-2 sm:mt-0`}>
            {t('catalog:common.cancel')}
          </Link>
          <button type="submit" disabled={save.isPending} className={primaryButtonClassName}>
            {save.isPending && <Loader2 aria-hidden="true" className="mr-2 h-4 w-4 animate-spin" />}
            <span>{save.isPending ? t('catalog:common.saving') : t('catalog:common.save')}</span>
          </button>
        </div>
      </form>
    </section>
  );
}
