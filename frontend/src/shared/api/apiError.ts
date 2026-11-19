/**
 * Erreur API normalisée à partir des réponses Problem Details (RFC 9457) du backend
 * (docs/api-specification.md §1.3).
 *
 * - `code` : code stable servant à afficher un message traduit (voir errorMessages.ts).
 *   Codes produits par le frontend lui‑même : `NETWORK_ERROR` (pas de réponse), `TIMEOUT`
 *   (délai dépassé), `UNKNOWN_ERROR` (réponse sans code exploitable).
 * - `kind` : `http` (le serveur a répondu), `network`, `timeout` ou `unknown`.
 * - `retryAfterSeconds` : valeur de l'en‑tête `Retry-After` (429), en secondes, sinon `null`.
 *   Information d'affichage uniquement : aucune nouvelle tentative automatique n'en découle.
 * - `message` : texte TECHNIQUE (titre du serveur ou message d'Axios), utile aux journaux de
 *   développement. Il ne doit JAMAIS être affiché à l'utilisateur.
 */
import { isAxiosError } from 'axios';

/** Erreur de validation d'un champ : `code` stable traduisible, `message` serveur facultatif. */
export interface FieldError {
  readonly field: string;
  /** Ex. `REQUIRED`, `INVALID_FORMAT`… ; `null` si le serveur n'en fournit pas. */
  readonly code: string | null;
  /** Complément textuel du serveur — jamais affiché tel quel. */
  readonly message: string | null;
}

export type ApiErrorKind = 'http' | 'network' | 'timeout' | 'unknown';

export class ApiError extends Error {
  readonly status: number | null;
  readonly code: string;
  readonly kind: ApiErrorKind;
  readonly traceId: string | null;
  readonly fieldErrors: readonly FieldError[];
  readonly retryAfterSeconds: number | null;

  constructor(params: {
    status: number | null;
    code: string;
    message: string;
    kind?: ApiErrorKind;
    traceId?: string | null;
    fieldErrors?: readonly FieldError[];
    retryAfterSeconds?: number | null;
  }) {
    super(params.message);
    this.name = 'ApiError';
    this.status = params.status;
    this.code = params.code;
    this.kind = params.kind ?? (params.status === null ? 'unknown' : 'http');
    this.traceId = params.traceId ?? null;
    this.fieldErrors = params.fieldErrors ?? [];
    this.retryAfterSeconds = params.retryAfterSeconds ?? null;
  }
}

const TIMEOUT_CODES = new Set(['ECONNABORTED', 'ETIMEDOUT']);

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function readFieldErrors(value: unknown): FieldError[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.flatMap((item: unknown) => {
    if (!isRecord(item) || typeof item.field !== 'string') {
      return [];
    }
    const code = typeof item.code === 'string' ? item.code : null;
    const message = typeof item.message === 'string' ? item.message : null;
    return code === null && message === null ? [] : [{ field: item.field, code, message }];
  });
}

/**
 * `Retry-After` : nombre de secondes, ou date HTTP. Renvoie un entier ≥ 0, ou `null` si
 * l'en‑tête est absent ou illisible.
 */
export function parseRetryAfter(value: unknown, now: number = Date.now()): number | null {
  if (typeof value !== 'string' || value.trim() === '') {
    return null;
  }
  const trimmed = value.trim();
  if (/^\d+$/.test(trimmed)) {
    return Number.parseInt(trimmed, 10);
  }
  const date = Date.parse(trimmed);
  if (Number.isNaN(date)) {
    return null;
  }
  return Math.max(0, Math.ceil((date - now) / 1000));
}

function readRetryAfter(headers: unknown): number | null {
  if (!isRecord(headers)) {
    return null;
  }
  const getter = (headers as { get?: unknown }).get;
  const raw: unknown =
    typeof getter === 'function'
      ? (getter as (name: string) => unknown).call(headers, 'retry-after')
      : (headers['retry-after'] ?? headers['Retry-After']);
  return parseRetryAfter(raw);
}

export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) {
    return error;
  }
  if (isAxiosError(error)) {
    const response = error.response;

    if (!response) {
      const isTimeout = error.code !== undefined && TIMEOUT_CODES.has(error.code);
      return new ApiError({
        status: null,
        code: isTimeout ? 'TIMEOUT' : 'NETWORK_ERROR',
        kind: isTimeout ? 'timeout' : 'network',
        message: error.message,
      });
    }

    const body: unknown = response.data;
    const retryAfterSeconds = readRetryAfter(response.headers);
    if (isRecord(body)) {
      return new ApiError({
        status: response.status,
        code: typeof body.code === 'string' ? body.code : 'UNKNOWN_ERROR',
        kind: 'http',
        message: typeof body.title === 'string' ? body.title : error.message,
        traceId: typeof body.traceId === 'string' ? body.traceId : null,
        fieldErrors: readFieldErrors(body.errors),
        retryAfterSeconds,
      });
    }
    return new ApiError({
      status: response.status,
      code: 'UNKNOWN_ERROR',
      kind: 'http',
      message: error.message,
      retryAfterSeconds,
    });
  }
  return new ApiError({
    status: null,
    code: 'UNKNOWN_ERROR',
    kind: 'unknown',
    message: error instanceof Error ? error.message : 'Unknown error',
  });
}
