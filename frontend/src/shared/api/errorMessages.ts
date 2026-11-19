/**
 * Traduction CENTRALISÉE des erreurs API (R7). Aucune fonctionnalité ne doit réimplémenter
 * ce choix : utiliser `describeApiError` (ou le composant `ApiErrorMessage`).
 *
 * Ordre de priorité du message affiché :
 * 1. délai dépassé / absence de réponse (`timeout`, `network`) ;
 * 2. code stable CONNU du backend (docs/api-specification.md §1.3) ;
 * 3. sinon, statut HTTP (400, 401, 403, 404, 409, 422, 429, 5xx) ;
 * 4. sinon, message générique.
 * Le texte renvoyé par le serveur (`title`, `detail`, `message`) n'est jamais affiché.
 */
import { toApiError, type FieldError } from './apiError';

/** Codes stables documentés (§1.3) ; chacun a une traduction dans `apiErrors.codes`. */
export const KNOWN_ERROR_CODES = [
  'VALIDATION_ERROR',
  'SESSION_REVOKED',
  'FORBIDDEN',
  'CSRF_CHECK_FAILED',
  'AI_NOT_ENABLED',
  'SUPPORT_SCOPE_DENIED',
  'NOT_FOUND',
  'INSUFFICIENT_STOCK',
  'SALE_ALREADY_CONFIRMED',
  'SALE_ALREADY_CANCELLED',
  'OPTIMISTIC_LOCK',
  'IDEMPOTENCY_KEY_REUSED',
  'CURRENCY_LOCKED',
  'BUSINESS_CONTEXT_MISMATCH',
  'PAYMENT_EXCEEDS_BALANCE',
  'INVALID_STATE_TRANSITION',
  'DISCOUNT_LIMIT_EXCEEDED',
  'CREDIT_INSUFFICIENT',
  'CUSTOMER_REQUIRED_FOR_CREDIT',
  'CANCELLATION_SETTLEMENT_MISMATCH',
  'RATE_LIMITED',
  'AI_QUOTA_EXCEEDED',
  'AI_INSUFFICIENT_DATA',
  'AI_INVALID_OUTPUT',
  'AI_DISABLED',
  'AI_UNAVAILABLE',
  'INTERNAL_ERROR',
  'MOCK_NOT_IMPLEMENTED',
] as const;
export type KnownErrorCode = (typeof KNOWN_ERROR_CODES)[number];

/** Codes stables des erreurs de validation de champ (§1.3). */
export const KNOWN_FIELD_ERROR_CODES = [
  'REQUIRED',
  'INVALID_FORMAT',
  'TOO_LONG',
  'TOO_SHORT',
  'INVALID_VALUE',
  'ALREADY_EXISTS',
] as const;
export type KnownFieldErrorCode = (typeof KNOWN_FIELD_ERROR_CODES)[number];

type StatusMessage =
  | 'badRequest'
  | 'unauthorized'
  | 'forbidden'
  | 'notFound'
  | 'conflict'
  | 'unprocessable'
  | 'tooManyRequests'
  | 'server';

export type ApiErrorMessageKey =
  | `apiErrors:codes.${KnownErrorCode}`
  | `apiErrors:status.${StatusMessage}`
  | 'apiErrors:network'
  | 'apiErrors:timeout'
  | 'apiErrors:unknown';

export interface ApiErrorDescription {
  /** Clé de traduction du message principal. */
  readonly messageKey: ApiErrorMessageKey;
  /** Référence à communiquer au support : uniquement pour les erreurs serveur (5xx). */
  readonly traceId: string | null;
  /** Délai indiqué par `Retry-After` : uniquement pour 429 (aucune relance automatique). */
  readonly retryAfterSeconds: number | null;
}

const knownCodes: ReadonlySet<string> = new Set(KNOWN_ERROR_CODES);
const knownFieldCodes: ReadonlySet<string> = new Set(KNOWN_FIELD_ERROR_CODES);

function isKnownCode(code: string): code is KnownErrorCode {
  return knownCodes.has(code);
}

function isKnownFieldCode(code: string): code is KnownFieldErrorCode {
  return knownFieldCodes.has(code);
}

function statusMessage(status: number | null): StatusMessage | null {
  if (status === null) return null;
  if (status >= 500) return 'server';
  switch (status) {
    case 401:
      return 'unauthorized';
    case 403:
      return 'forbidden';
    case 404:
      return 'notFound';
    case 409:
      return 'conflict';
    case 422:
      return 'unprocessable';
    case 429:
      return 'tooManyRequests';
    default:
      return status >= 400 ? 'badRequest' : null;
  }
}

export function describeApiError(error: unknown): ApiErrorDescription {
  const apiError = toApiError(error);
  const isServerError = apiError.status !== null && apiError.status >= 500;

  let messageKey: ApiErrorMessageKey;
  if (apiError.kind === 'timeout') {
    messageKey = 'apiErrors:timeout';
  } else if (apiError.kind === 'network') {
    messageKey = 'apiErrors:network';
  } else if (isKnownCode(apiError.code)) {
    messageKey = `apiErrors:codes.${apiError.code}`;
  } else {
    const status = statusMessage(apiError.status);
    messageKey = status ? `apiErrors:status.${status}` : 'apiErrors:unknown';
  }

  return {
    messageKey,
    traceId: isServerError ? apiError.traceId : null,
    retryAfterSeconds: apiError.status === 429 ? apiError.retryAfterSeconds : null,
  };
}

/** Clé de traduction d'une erreur de champ ; un code inconnu ou absent → message générique. */
export function fieldErrorMessageKey(
  fieldError: FieldError,
): `apiErrors:fields.${KnownFieldErrorCode}` {
  return fieldError.code !== null && isKnownFieldCode(fieldError.code)
    ? `apiErrors:fields.${fieldError.code}`
    : 'apiErrors:fields.INVALID_VALUE';
}
