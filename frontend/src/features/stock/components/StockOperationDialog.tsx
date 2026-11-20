import { ArrowDownToLine, ArrowUpFromLine, Gauge, Loader2, PackagePlus, SlidersHorizontal, X } from 'lucide-react';
import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';

import { FormField } from '@/features/catalog/components/FormField';
import {
  buttonClassName,
  describedBy,
  inputClassName,
  primaryButtonClassName,
  secondaryButtonClassName,
} from '@/shared/ui/styles';
import { UNIT_QUANTITY_DECIMALS } from '@/features/catalog/types';
import { hasPermission, useMe } from '@/features/session/api';
import { useChangeMinimum, useStockOperation } from '@/features/stock/api';
import type { StockItem, StockOperation } from '@/features/stock/types';
import { toApiError } from '@/shared/api/apiError';
import { fieldErrorMessageKey } from '@/shared/api/errorMessages';
import { addDecimal, compareDecimal, subtractDecimal } from '@/shared/lib/decimalMath';
import { currencyFractionDigits, normalizeDecimal, validateDecimal } from '@/shared/lib/decimalInput';
import { formatMoney } from '@/shared/lib/formatMoney';
import { formatQuantity } from '@/shared/lib/formatQuantity';
import { newIdempotencyKey } from '@/shared/lib/idempotencyKey';
import { ApiErrorMessage } from '@/shared/ui/ApiErrorMessage';

const REASON_MAX = 255;
const OPERATION_ICONS = {
  initialize: PackagePlus,
  entry: ArrowDownToLine,
  exit: ArrowUpFromLine,
  adjustment: SlidersHorizontal,
  minimum: Gauge,
} as const;
const REFERENCE_MAX = 100;
/** Identifiants des contrôles (techniques, non affichés). */
const IDS = {
  quantity: 'stock-quantity',
  minimum: 'stock-minimum',
  reason: 'stock-reason',
  reference: 'stock-reference',
  note: 'stock-note',
  direction: 'stock-direction',
  unitCost: 'stock-unit-cost',
} as const;

type Field = keyof typeof IDS;
type Errors = Partial<Record<Field, string>>;

interface StockOperationDialogProps {
  readonly operation: StockOperation;
  readonly item: StockItem;
  /** Paramètre de l'entreprise s'il est lisible (sinon le serveur décide seul). */
  readonly allowNegativeStock: boolean | undefined;
  readonly onClose: () => void;
  /** Message de réussite déjà traduit. */
  readonly onDone: (message: string) => void;
}

/**
 * Opération de stock en deux temps : saisie (quantité, motif…) avec aperçu EXACT de la nouvelle
 * quantité, puis confirmation. La clé d'idempotence est créée à l'ouverture et réutilisée pour
 * tout nouvel envoi : un double clic ne crée jamais deux mouvements. Le serveur reste seul juge.
 */
export function StockOperationDialog({
  operation,
  item,
  allowNegativeStock,
  onClose,
  onDone,
}: StockOperationDialogProps) {
  const { t } = useTranslation(['stock', 'apiErrors', 'catalog']);
  const [idempotencyKey] = useState(newIdempotencyKey);
  const [step, setStep] = useState<'form' | 'confirm'>('form');
  const [quantity, setQuantity] = useState(operation === 'minimum' ? (item.minimumQuantity ?? '0') : '');
  const [minimum, setMinimum] = useState('0');
  const [reason, setReason] = useState('');
  const [reference, setReference] = useState('');
  const [note, setNote] = useState('');
  const [direction, setDirection] = useState<'IN' | 'OUT'>('OUT');
  const [unitCost, setUnitCost] = useState('');
  const { data: me } = useMe();
  const currency = me?.activeBusiness?.currency ?? '';
  // Confort d'affichage : le serveur refuse le coût sans COST_READ et ne le renvoie jamais.
  const showsUnitCost = operation === 'entry' && hasPermission(me, 'COST_READ') && currency !== '';
  const [errors, setErrors] = useState<Errors>({});
  const operationMutation = useStockOperation();
  const minimumMutation = useChangeMinimum();
  const pending = operationMutation.isPending || minimumMutation.isPending;
  const titleId = useId();
  const firstFieldRef = useRef<HTMLInputElement>(null);

  const decimals = UNIT_QUANTITY_DECIMALS[item.unit];
  const unit = t(`stock:unitShort.${item.unit}`);
  const current = item.quantity ?? '0';
  const increases = operation === 'initialize' || operation === 'entry' || (operation === 'adjustment' && direction === 'IN');
  const needsReason = operation === 'entry' || operation === 'exit' || operation === 'adjustment';
  const showsPreview = operation === 'entry' || operation === 'exit' || operation === 'adjustment';

  useEffect(() => {
    firstFieldRef.current?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !pending) onClose();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [onClose, pending]);

  const quantityValid = validateDecimal(quantity, decimals, true) === null;
  const normalized = quantityValid ? normalizeDecimal(quantity) : null;
  const after =
    normalized !== null && showsPreview
      ? increases
        ? addDecimal(current, normalized)
        : subtractDecimal(current, normalized)
      : null;
  const becomesNegative = after !== null && !increases && compareDecimal(after, '0') < 0;
  const blocked = becomesNegative && allowNegativeStock === false;

  function validate(): Errors {
    const found: Errors = {};
    const quantityError = validateDecimal(quantity, decimals, true);
    if (quantityError) {
      found.quantity = t(`stock:operation.errors.${quantityError}`);
    } else if (operation !== 'minimum' && compareDecimal(normalizeDecimal(quantity), '0') === 0) {
      found.quantity = t('stock:operation.errors.zero');
    }
    if (operation === 'initialize') {
      const minimumError = validateDecimal(minimum, decimals, false);
      if (minimumError) found.minimum = t(`stock:operation.errors.${minimumError}`);
    }
    if (showsUnitCost && unitCost.trim() !== '') {
      const costError = validateDecimal(unitCost, currencyFractionDigits(currency), false);
      if (costError) {
        found.unitCost =
          costError === 'tooManyDecimals'
            ? t('stock:operation.errors.costDecimals', { currency, count: currencyFractionDigits(currency) })
            : t(`stock:operation.errors.${costError}`);
      } else if (compareDecimal(normalizeDecimal(unitCost), '0') === 0) {
        found.unitCost = t('stock:operation.errors.costZero');
      }
    }
    if (needsReason && reason.trim() === '') found.reason = t('stock:operation.errors.reasonRequired');
    if (reason.trim().length > REASON_MAX) found.reason = t('stock:operation.errors.tooLong', { max: REASON_MAX });
    if (note.trim().length > REASON_MAX) found.note = t('stock:operation.errors.tooLong', { max: REASON_MAX });
    if (reference.trim().length > REFERENCE_MAX) {
      found.reference = t('stock:operation.errors.tooLong', { max: REFERENCE_MAX });
    }
    return found;
  }

  function review(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const found = validate();
    setErrors(found);
    const first = Object.keys(found)[0] as Field | undefined;
    if (first) {
      document.getElementById(IDS[first])?.focus();
      return;
    }
    if (!blocked) setStep('confirm');
  }

  function handleError(error: unknown) {
    const fieldErrors: Errors = {};
    toApiError(error).fieldErrors.forEach((fieldError) => {
      const field = (fieldError.field === 'minimumQuantity' ? 'minimum' : fieldError.field) as Field;
      if (field in IDS) fieldErrors[field] = t(fieldErrorMessageKey(fieldError));
    });
    setErrors(fieldErrors);
    setStep('form');
  }

  function confirm() {
    const value = normalizeDecimal(quantity);
    if (operation === 'minimum') {
      minimumMutation.mutate(
        { productId: item.productId, minimumQuantity: value },
        { onSuccess: () => onDone(t('stock:operation.done.minimum')), onError: handleError },
      );
      return;
    }
    const optional = (text: string) => (text.trim() === '' ? null : text.trim());
    const request =
      operation === 'initialize'
        ? { endpoint: 'initialize' as const, body: { quantity: value, minimumQuantity: normalizeDecimal(minimum) || '0', note: optional(note) } }
        : operation === 'adjustment'
          ? { endpoint: 'adjustments' as const, body: { direction, quantity: value, reason: reason.trim() } }
          : {
              endpoint: operation === 'entry' ? ('entries' as const) : ('exits' as const),
              body: {
                quantity: value,
                reason: reason.trim(),
                reference: optional(reference),
                ...(showsUnitCost && unitCost.trim() !== '' ? { unitCost: normalizeDecimal(unitCost) } : {}),
              },
            };
    operationMutation.mutate(
      { productId: item.productId, idempotencyKey, ...request },
      {
        onSuccess: (response) =>
          onDone(
            t(`stock:operation.done.${operation}`, {
              quantity: `${formatQuantity(response.stock.quantity ?? '0')} ${unit}`,
            }),
          ),
        onError: handleError,
      },
    );
  }

  const serverError =
    (operationMutation.error ?? minimumMutation.error) &&
    toApiError(operationMutation.error ?? minimumMutation.error).fieldErrors.length === 0
      ? (operationMutation.error ?? minimumMutation.error)
      : null;
  const quantityLabel =
    operation === 'initialize'
      ? t('stock:operation.initialQuantity', { unit })
      : operation === 'minimum'
        ? t('stock:operation.minimum', { unit })
        : t('stock:operation.quantity', { unit });
  const quantityHint =
    operation === 'minimum'
      ? t('stock:operation.minimumHint')
      : decimals === 0
        ? t('stock:operation.quantityHintInteger')
        : t('stock:operation.quantityHintDecimals', { decimals });
  const reasonPlaceholderKey =
    operation === 'exit'
      ? 'stock:operation.reasonPlaceholder.exit'
      : operation === 'adjustment'
        ? 'stock:operation.reasonPlaceholder.adjustment'
        : 'stock:operation.reasonPlaceholder.entry';
  const OperationIcon = OPERATION_ICONS[operation];
  const variation = normalized !== null ? `${increases ? '+' : '−'}${formatQuantity(normalized)}` : '';

  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-brand/50 p-0 sm:items-center sm:p-4">
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="max-h-full w-full max-w-lg overflow-y-auto rounded-t-2xl border border-line bg-sheet shadow-raised sm:rounded-2xl"
      >
        <div aria-hidden="true" className="h-1 bg-accent" />
        <div className="p-5 sm:p-6">
        <div className="flex items-start">
          <span aria-hidden="true" className="mr-3 flex h-11 w-11 flex-none items-center justify-center rounded-xl bg-brand text-accent">
            <OperationIcon className="h-5 w-5" />
          </span>
          <div className="min-w-0 flex-1">
            <p className="text-xs font-semibold uppercase tracking-[0.12em] text-accent-strong">
              {step === 'confirm' ? t('stock:operation.stepConfirm') : t('stock:operation.stepForm')}
            </p>
            <h2 id={titleId} className="font-display text-2xl leading-tight">
              {step === 'confirm' ? t('stock:operation.confirmTitle') : t(`stock:operation.titles.${operation}`)}
            </h2>
          </div>
          <button
            type="button"
            onClick={onClose}
            disabled={pending}
            aria-label={t('stock:operation.close')}
            className="-mr-2 -mt-1 inline-flex h-11 w-11 flex-none items-center justify-center rounded-lg text-ink-muted hover:bg-brand-soft hover:text-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
          >
            <X aria-hidden="true" className="h-5 w-5" />
          </button>
        </div>
        <div className="mt-4 rounded-xl border border-line bg-surface px-4 py-3">
          <p className="text-sm text-ink-muted">
            {t('stock:operation.product')} : <span className="font-semibold text-ink">{item.productName}</span>
          </p>
          {item.quantity !== null && (
            <p className="mt-1 text-sm font-semibold">
              {t('stock:operation.available', { quantity: `${formatQuantity(item.quantity)} ${unit}` })}
            </p>
          )}
        </div>

        {serverError && (
          <div className="mt-4 rounded-lg border border-l-4 border-danger/30 border-l-danger bg-danger-surface p-3 text-sm">
            <ApiErrorMessage error={serverError} />
          </div>
        )}

        {step === 'form' ? (
          <form noValidate onSubmit={review} className="mt-4 space-y-4">
            {operation === 'adjustment' && (
              <FormField id={IDS.direction} label={t('stock:operation.direction')}>
                <select
                  id={IDS.direction}
                  value={direction}
                  onChange={(event) => setDirection(event.target.value === 'IN' ? 'IN' : 'OUT')}
                  className={inputClassName(false)}
                >
                  <option value="OUT">{t('stock:operation.directionOut')}</option>
                  <option value="IN">{t('stock:operation.directionIn')}</option>
                </select>
              </FormField>
            )}
            <FormField id={IDS.quantity} label={quantityLabel} hint={quantityHint} error={errors.quantity ?? null}>
              <input
                ref={firstFieldRef}
                id={IDS.quantity}
                inputMode="decimal"
                autoComplete="off"
                value={quantity}
                onChange={(event) => setQuantity(event.target.value)}
                className={inputClassName(Boolean(errors.quantity))}
                {...describedBy(IDS.quantity, Boolean(errors.quantity), true)}
              />
            </FormField>
            {operation === 'initialize' && (
              <FormField
                id={IDS.minimum}
                label={t('stock:operation.minimum', { unit })}
                hint={t('stock:operation.minimumHint')}
                error={errors.minimum ?? null}
              >
                <input
                  id={IDS.minimum}
                  inputMode="decimal"
                  autoComplete="off"
                  value={minimum}
                  onChange={(event) => setMinimum(event.target.value)}
                  className={inputClassName(Boolean(errors.minimum))}
                  {...describedBy(IDS.minimum, Boolean(errors.minimum), true)}
                />
              </FormField>
            )}
            {needsReason && (
              <FormField id={IDS.reason} label={t('stock:operation.reason')} error={errors.reason ?? null}>
                <input
                  id={IDS.reason}
                  value={reason}
                  maxLength={REASON_MAX}
                  placeholder={t(reasonPlaceholderKey)}
                  onChange={(event) => setReason(event.target.value)}
                  className={inputClassName(Boolean(errors.reason))}
                  {...describedBy(IDS.reason, Boolean(errors.reason), false)}
                />
              </FormField>
            )}
            {(operation === 'entry' || operation === 'exit') && (
              <FormField
                id={IDS.reference}
                label={t('stock:operation.reference')}
                optionalLabel={t('catalog:common.optional')}
                error={errors.reference ?? null}
              >
                <input
                  id={IDS.reference}
                  value={reference}
                  maxLength={REFERENCE_MAX}
                  placeholder={t('stock:operation.referencePlaceholder')}
                  onChange={(event) => setReference(event.target.value)}
                  className={inputClassName(Boolean(errors.reference))}
                  {...describedBy(IDS.reference, Boolean(errors.reference), false)}
                />
              </FormField>
            )}
            {showsUnitCost && (
              <FormField
                id={IDS.unitCost}
                label={t('stock:operation.unitCost', { currency })}
                optionalLabel={t('catalog:common.optional')}
                hint={t('stock:operation.unitCostHint', { count: currencyFractionDigits(currency) })}
                error={errors.unitCost ?? null}
              >
                <input
                  id={IDS.unitCost}
                  inputMode="decimal"
                  autoComplete="off"
                  value={unitCost}
                  onChange={(event) => setUnitCost(event.target.value)}
                  className={inputClassName(Boolean(errors.unitCost))}
                  {...describedBy(IDS.unitCost, Boolean(errors.unitCost), true)}
                />
              </FormField>
            )}
            {operation === 'initialize' && (
              <FormField
                id={IDS.note}
                label={t('stock:operation.note')}
                optionalLabel={t('catalog:common.optional')}
                error={errors.note ?? null}
              >
                <input
                  id={IDS.note}
                  value={note}
                  maxLength={REASON_MAX}
                  onChange={(event) => setNote(event.target.value)}
                  className={inputClassName(Boolean(errors.note))}
                  {...describedBy(IDS.note, Boolean(errors.note), false)}
                />
              </FormField>
            )}

            {after !== null && (
              <p role="status" className="rounded-lg border border-accent bg-accent-soft p-3 text-sm font-medium">
                {t('stock:operation.preview', {
                  before: `${formatQuantity(current)} ${unit}`,
                  variation: `${variation} ${unit}`,
                  after: `${formatQuantity(after)} ${unit}`,
                })}
              </p>
            )}
            {becomesNegative && allowNegativeStock !== undefined && (
              <p
                role="alert"
                className={`rounded-lg border-l-4 p-3 text-sm font-medium ${
                  blocked ? 'border-danger bg-danger-surface text-danger' : 'border-accent-strong bg-accent-soft text-ink'
                }`}
              >
                {blocked
                  ? t('stock:operation.insufficient', { after: `${formatQuantity(after ?? '0')} ${unit}` })
                  : t('stock:operation.negativeAllowed', { after: `${formatQuantity(after ?? '0')} ${unit}` })}
              </p>
            )}

            <div className="flex flex-col-reverse sm:flex-row sm:justify-end">
              <button type="button" onClick={onClose} className={`${secondaryButtonClassName} mt-2 sm:mr-2 sm:mt-0`}>
                {t('stock:operation.cancel')}
              </button>
              <button type="submit" disabled={blocked} className={primaryButtonClassName}>
                {t('stock:operation.review')}
              </button>
            </div>
          </form>
        ) : (
          <div className="mt-4 space-y-4">
            <dl className="divide-y divide-line rounded-xl border border-line bg-sheet px-4 text-sm">
              <div className="flex justify-between py-2">
                <dt className="text-ink-muted">{quantityLabel}</dt>
                <dd className="font-semibold">
                  {operation === 'adjustment' || operation === 'entry' || operation === 'exit'
                    ? `${variation} ${unit}`
                    : `${formatQuantity(normalizeDecimal(quantity))} ${unit}`}
                </dd>
              </div>
              {after !== null && (
                <div className="flex justify-between py-2">
                  <dt className="text-ink-muted">{t('stock:history.after')}</dt>
                  <dd className="font-semibold">{`${formatQuantity(after)} ${unit}`}</dd>
                </div>
              )}
              {showsUnitCost && unitCost.trim() !== '' && (
                <div className="flex justify-between py-2">
                  <dt className="text-ink-muted">{t('stock:history.unitCost')}</dt>
                  <dd className="font-semibold">{formatMoney(normalizeDecimal(unitCost), currency)}</dd>
                </div>
              )}
              {needsReason && (
                <div className="flex justify-between py-2">
                  <dt className="text-ink-muted">{t('stock:operation.reason')}</dt>
                  <dd className="ml-4 text-right">{reason.trim()}</dd>
                </div>
              )}
            </dl>
            <div className="flex flex-col-reverse sm:flex-row sm:justify-end">
              <button
                type="button"
                onClick={() => setStep('form')}
                disabled={pending}
                className={`${secondaryButtonClassName} mt-2 sm:mr-2 sm:mt-0`}
              >
                {t('stock:operation.back')}
              </button>
              <button type="button" onClick={confirm} disabled={pending} className={buttonClassName('primary')}>
                {pending && <Loader2 aria-hidden="true" className="mr-2 h-4 w-4 animate-spin" />}
                <span>{pending ? t('stock:operation.saving') : t('stock:operation.confirm')}</span>
              </button>
            </div>
          </div>
        )}
        </div>
      </div>
    </div>
  );
}
