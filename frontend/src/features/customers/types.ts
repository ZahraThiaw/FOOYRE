/**
 * Contrat API des clients (MVP‑4A). L'entreprise n'apparaît jamais : le serveur la déduit de la
 * session. Données personnelles : jamais journalisées ni conservées hors du cache mémoire.
 */
import type { CatalogStatus, StatusFilter } from '@/features/catalog/types';

export interface Customer {
  readonly id: string;
  /** `null` si le client n'est identifié que par son téléphone. */
  readonly name: string | null;
  readonly phone: string | null;
  readonly email: string | null;
  readonly address: string | null;
  readonly notes: string | null;
  readonly status: CatalogStatus;
  readonly archivedAt: string | null;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly version: number;
}

export interface CustomerInput {
  readonly name: string | null;
  readonly phone: string | null;
  readonly email: string | null;
  readonly address: string | null;
  readonly notes: string | null;
  readonly version?: number;
}

export type CustomerSort = 'name,asc' | 'createdAt,desc' | 'updatedAt,desc';
export const CUSTOMER_SORTS: readonly CustomerSort[] = ['name,asc', 'createdAt,desc', 'updatedAt,desc'];

export interface CustomerQuery {
  readonly q: string;
  readonly status: StatusFilter;
  readonly sort: CustomerSort;
  readonly page: number;
  readonly size: number;
}

/** Nom affiché : le nom, sinon le téléphone (un client peut n'avoir qu'un numéro). */
export function customerLabel(customer: Pick<Customer, 'name' | 'phone'>): string {
  return customer.name ?? customer.phone ?? '';
}
