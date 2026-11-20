import { hasPermission, useMe } from '@/features/session/api';

/**
 * Droits d'AFFICHAGE du catalogue (confort uniquement) : le serveur contrôle chaque requête et
 * retire lui‑même le prix d'achat sans COST_READ.
 */
export function useCatalogAccess() {
  const { data: me } = useMe();
  return {
    canWrite: hasPermission(me, 'CATALOG_WRITE'),
    canSeeCosts: hasPermission(me, 'COST_READ'),
    currency: me?.activeBusiness?.currency ?? 'XOF',
  };
}
