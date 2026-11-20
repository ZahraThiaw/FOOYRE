import { ArrowLeft, Loader2 } from 'lucide-react';
import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router';

import {
  createProduct,
  updateProduct,
  useCatalogMutation,
  useCategories,
  useProduct,
} from '@/features/catalog/api';
import { useCatalogAccess } from '@/features/catalog/access';
import { FormField } from '@/features/catalog/components/FormField';
import {
  describedBy,
  inputClassName,
  primaryButtonClassName,
  secondaryButtonClassName,
} from '@/features/catalog/components/formStyles';
import { MEASUREMENT_UNITS, type MeasurementUnit, type Product, type ProductInput } from '@/features/catalog/types';
import { toApiError } from '@/shared/api/apiError';
import { fieldErrorMessageKey } from '@/shared/api/errorMessages';
import {
  currencyFractionDigits,
  normalizeDecimal,
  validateDecimal,
  type DecimalInputError,
} from '@/shared/lib/decimalInput';
import { ApiErrorMessage } from '@/shared/ui/ApiErrorMessage';
import { useNotify } from '@/shared/ui/useNotify';

const LIMITS = { name: 150, description: 1000, code: 64 } as const;
const BARCODE_PATTERN = /^[A-Za-z0-9-]+$/;

/** Identifiants des contrôles (techniques, non affichés). */
const IDS = {
  name: 'product-name',
  description: 'product-description',
  sku: 'product-sku',
  barcode: 'product-barcode',
  categoryId: 'product-categoryId',
  unit: 'product-unit',
  purchasePrice: 'product-purchasePrice',
  salePrice: 'product-salePrice',
} as const;

type FieldName = 'name' | 'description' | 'sku' | 'barcode' | 'categoryId' | 'unit' | 'purchasePrice' | 'salePrice';
type Errors = Partial<Record<FieldName, string>>;

interface FormValues {
  name: string;
  description: string;
  sku: string;
  barcode: string;
  categoryId: string;
  unit: MeasurementUnit;
  purchasePrice: string;
  salePrice: string;
}

function initialValues(product: Product | undefined): FormValues {
  return {
    name: product?.name ?? '',
    description: product?.description ?? '',
    sku: product?.sku ?? '',
    barcode: product?.barcode ?? '',
    categoryId: product?.category?.id ?? '',
    unit: product?.unit ?? 'PIECE',
    purchasePrice: product?.purchasePrice ?? '',
    salePrice: product?.salePrice ?? '',
  };
}

const optional = (value: string) => (value.trim() === '' ? null : value.trim());

/** Création (`/products/new`) et modification (`/products/:id/edit`) d'un produit. */
export function ProductFormPage() {
  const { id } = useParams();
  const { t } = useTranslation(['catalog']);
  const existing = useProduct(id ?? '');

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
    return <ProductForm key={existing.data.id} product={existing.data} />;
  }
  return <ProductForm product={undefined} />;
}

function ProductForm({ product }: { readonly product: Product | undefined }) {
  const { t } = useTranslation(['catalog', 'apiErrors']);
  const navigate = useNavigate();
  const notify = useNotify();
  const { canSeeCosts, currency } = useCatalogAccess();
  const categories = useCategories('ACTIVE');
  const [values, setValues] = useState<FormValues>(() => initialValues(product));
  const [errors, setErrors] = useState<Errors>({});
  const save = useCatalogMutation((input: ProductInput) =>
    product ? updateProduct(product.id, input) : createProduct(input),
  );
  const saleDecimals = currencyFractionDigits(currency);
  const currentArchivedCategory = product?.category?.status === 'ARCHIVED' ? product.category : null;

  const set = (field: keyof FormValues) => (value: string) => {
    setValues((current) => ({ ...current, [field]: value }));
    setErrors((current) => ({ ...current, [field]: undefined }));
  };

  const decimalMessage = (error: DecimalInputError) => t(`catalog:productForm.errors.${error}`);

  function validate(): Errors {
    const found: Errors = {};
    const tooLong = (max: number) => t('catalog:productForm.errors.tooLong', { max });
    if (values.name.trim() === '') found.name = t('catalog:productForm.errors.required');
    else if (values.name.trim().length > LIMITS.name) found.name = tooLong(LIMITS.name);
    if (values.description.trim().length > LIMITS.description) found.description = tooLong(LIMITS.description);
    if (values.sku.trim().length > LIMITS.code) found.sku = tooLong(LIMITS.code);
    const barcode = values.barcode.trim();
    if (barcode.length > LIMITS.code) found.barcode = tooLong(LIMITS.code);
    else if (barcode !== '' && !BARCODE_PATTERN.test(barcode)) found.barcode = t('catalog:productForm.errors.codeFormat');
    const sale = validateDecimal(values.salePrice, saleDecimals, true);
    if (sale) found.salePrice = decimalMessage(sale);
    if (canSeeCosts) {
      // Même précision que le prix de vente : décimales de la devise (règle du backend).
      const purchase = validateDecimal(values.purchasePrice, saleDecimals, false);
      if (purchase) found.purchasePrice = decimalMessage(purchase);
    }
    return found;
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const found = validate();
    setErrors(found);
    const firstInvalid = Object.keys(found)[0];
    if (firstInvalid) {
      document.getElementById(IDS[firstInvalid as FieldName])?.focus();
      return;
    }
    const input: ProductInput = {
      name: values.name.trim(),
      description: optional(values.description),
      sku: optional(values.sku),
      barcode: optional(values.barcode),
      categoryId: values.categoryId === '' ? null : values.categoryId,
      unit: values.unit,
      purchasePrice: canSeeCosts ? optional(normalizeDecimal(values.purchasePrice)) : null,
      salePrice: normalizeDecimal(values.salePrice),
      ...(product ? { version: product.version } : {}),
    };
    save.mutate(input, {
      onSuccess: (saved) => {
        notify(
          'success',
          t(product ? 'catalog:productForm.updated' : 'catalog:productForm.created', { name: saved.name }),
        );
        void navigate(`/products/${saved.id}`);
      },
      onError: (error) => {
        // Erreurs de champ du serveur (ex. SKU déjà utilisé) : affichées sous les champs.
        const fieldErrors: Errors = {};
        toApiError(error).fieldErrors.forEach((fieldError) => {
          fieldErrors[fieldError.field as FieldName] = t(fieldErrorMessageKey(fieldError));
        });
        setErrors(fieldErrors);
        notify('error', t('catalog:productForm.saveFailed'));
      },
    });
  }

  const serverError = save.error && toApiError(save.error).fieldErrors.length === 0 ? save.error : null;
  const field = (name: FieldName) => ({ id: IDS[name], error: errors[name] ?? null });

  return (
    <section className="mx-auto max-w-2xl space-y-6">
      <Link
        to={product ? `/products/${product.id}` : '/products'}
        className="inline-flex min-h-touch items-center text-sm font-medium text-ink-muted hover:text-ink"
      >
        <ArrowLeft aria-hidden="true" className="mr-2 h-4 w-4" />
        <span>{t('catalog:common.back')}</span>
      </Link>
      <h1 className="text-3xl text-ink sm:text-4xl">
        {product ? t('catalog:productForm.editTitle') : t('catalog:productForm.createTitle')}
      </h1>

      {serverError && (
        <div className="rounded-lg border border-l-4 border-danger/30 border-l-danger bg-danger-surface p-3 text-sm">
          <ApiErrorMessage error={serverError} />
        </div>
      )}

      <form noValidate onSubmit={handleSubmit} className="space-y-5 rounded-xl border border-line bg-sheet shadow-panel p-4 sm:p-6">
        <FormField {...field('name')} label={t('catalog:productForm.name')}>
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
          <FormField
            {...field('salePrice')}
            label={t('catalog:productForm.salePrice')}
            hint={t('catalog:productForm.priceHint', { currency, decimals: saleDecimals })}
          >
            <input
              id={IDS.salePrice}
              inputMode="decimal"
              value={values.salePrice}
              onChange={(event) => set('salePrice')(event.target.value)}
              className={inputClassName(Boolean(errors.salePrice))}
              {...describedBy(IDS.salePrice, Boolean(errors.salePrice), true)}
            />
          </FormField>
          {canSeeCosts && (
            <FormField
              {...field('purchasePrice')}
              label={t('catalog:productForm.purchasePrice')}
              optionalLabel={t('catalog:common.optional')}
              hint={t('catalog:productForm.costHint', { currency, decimals: saleDecimals })}
            >
              <input
                id={IDS.purchasePrice}
                inputMode="decimal"
                value={values.purchasePrice}
                onChange={(event) => set('purchasePrice')(event.target.value)}
                className={inputClassName(Boolean(errors.purchasePrice))}
                {...describedBy(IDS.purchasePrice, Boolean(errors.purchasePrice), true)}
              />
            </FormField>
          )}
        </div>

        <div className="grid grid-cols-1 gap-5 sm:grid-cols-2">
          <FormField {...field('unit')} label={t('catalog:productForm.unit')}>
            <select
              id={IDS.unit}
              value={values.unit}
              onChange={(event) => set('unit')(event.target.value)}
              className={inputClassName(Boolean(errors.unit))}
            >
              {MEASUREMENT_UNITS.map((unit) => (
                <option key={unit} value={unit}>
                  {t(`catalog:units.${unit}`)}
                </option>
              ))}
            </select>
          </FormField>
          <FormField
            {...field('categoryId')}
            label={t('catalog:productForm.category')}
            optionalLabel={t('catalog:common.optional')}
          >
            <select
              id={IDS.categoryId}
              value={values.categoryId}
              onChange={(event) => set('categoryId')(event.target.value)}
              className={inputClassName(Boolean(errors.categoryId))}
              {...describedBy(IDS.categoryId, Boolean(errors.categoryId), false)}
            >
              <option value="">{t('catalog:productForm.noCategory')}</option>
              {currentArchivedCategory && (
                <option value={currentArchivedCategory.id}>
                  {t('catalog:productForm.currentArchivedCategory', { name: currentArchivedCategory.name })}
                </option>
              )}
              {categories.data?.content.map((category) => (
                <option key={category.id} value={category.id}>
                  {category.name}
                </option>
              ))}
            </select>
          </FormField>
        </div>

        <div className="grid grid-cols-1 gap-5 sm:grid-cols-2">
          <FormField {...field('sku')} label={t('catalog:productForm.sku')} optionalLabel={t('catalog:common.optional')}>
            <input
              id={IDS.sku}
              value={values.sku}
              onChange={(event) => set('sku')(event.target.value)}
              autoComplete="off"
              className={inputClassName(Boolean(errors.sku))}
              {...describedBy(IDS.sku, Boolean(errors.sku), false)}
            />
          </FormField>
          <FormField
            {...field('barcode')}
            label={t('catalog:productForm.barcode')}
            optionalLabel={t('catalog:common.optional')}
          >
            <input
              id={IDS.barcode}
              value={values.barcode}
              inputMode="text"
              onChange={(event) => set('barcode')(event.target.value)}
              autoComplete="off"
              className={inputClassName(Boolean(errors.barcode))}
              {...describedBy(IDS.barcode, Boolean(errors.barcode), false)}
            />
          </FormField>
        </div>

        <FormField
          {...field('description')}
          label={t('catalog:productForm.description')}
          optionalLabel={t('catalog:common.optional')}
        >
          <textarea
            id={IDS.description}
            rows={3}
            value={values.description}
            onChange={(event) => set('description')(event.target.value)}
            className={`${inputClassName(Boolean(errors.description))} py-2`}
            {...describedBy(IDS.description, Boolean(errors.description), false)}
          />
        </FormField>

        <div className="flex flex-col-reverse sm:flex-row sm:justify-end">
          <Link
            to={product ? `/products/${product.id}` : '/products'}
            className={`${secondaryButtonClassName} mt-2 sm:mr-2 sm:mt-0`}
          >
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
