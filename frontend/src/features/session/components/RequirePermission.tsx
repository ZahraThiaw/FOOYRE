import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import { hasPermission, useMe } from '@/features/session/api';
import type { Permission } from '@/features/session/types';

/**
 * Affiche le contenu seulement si `/me` accorde la permission. CONFORT D'AFFICHAGE uniquement :
 * le serveur refuse de toute façon la requête ({@code 403}).
 */
export function RequirePermission({
  permission,
  children,
}: {
  readonly permission: Permission;
  readonly children: ReactNode;
}) {
  const { t } = useTranslation(['apiErrors']);
  const { data: me } = useMe();
  if (!hasPermission(me, permission)) {
    return (
      <p role="alert" className="rounded-md border-l-4 border-brand bg-brand-soft p-4">
        {t('apiErrors:codes.FORBIDDEN')}
      </p>
    );
  }
  return <>{children}</>;
}
