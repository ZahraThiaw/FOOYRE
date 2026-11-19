import { useTranslation } from 'react-i18next';

import { describeApiError } from '@/shared/api/errorMessages';

interface ApiErrorMessageProps {
  readonly error: unknown;
}

/**
 * Affichage standard d'une erreur API (R7) : message traduit à partir du code ou du statut,
 * délai `Retry-After` pour 429, référence `traceId` pour 5xx. Aucun texte technique du
 * serveur n'est affiché.
 */
export function ApiErrorMessage({ error }: ApiErrorMessageProps) {
  const { t } = useTranslation(['apiErrors']);
  const { messageKey, retryAfterSeconds, traceId } = describeApiError(error);

  return (
    <div role="alert" className="space-y-1">
      <p>{t(messageKey)}</p>
      {retryAfterSeconds !== null && (
        <p className="text-sm text-ink-muted">
          {t('apiErrors:retryAfter', { seconds: retryAfterSeconds })}
        </p>
      )}
      {traceId !== null && (
        <p className="text-sm text-ink-muted">{t('apiErrors:reference', { traceId })}</p>
      )}
    </div>
  );
}
