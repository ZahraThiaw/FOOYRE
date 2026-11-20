/**
 * Contrat API du stock (MVP‑3). Quantités en CHAÎNES décimales (jamais converties en `number`).
 * La valeur estimée n'est présente que si l'utilisatrice a COST_READ (filtrée par le serveur).
 */
import type { CatalogStatus, MeasurementUnit } from '@/features/catalog/types';

export type StockStatus = 'IN_STOCK' | 'LOW_STOCK' | 'OUT_OF_STOCK' | 'NOT_INITIALIZED';
export type StockStatusFilter = StockStatus | 'TRACKED' | 'ALERT' | 'ALL';
export type MovementType = 'INITIAL' | 'ENTRY' | 'EXIT' | 'ADJUSTMENT_IN' | 'ADJUSTMENT_OUT';

export const STOCK_STATUS_FILTERS: readonly StockStatusFilter[] = [
  'ALL',
  'TRACKED',
  'ALERT',
  'IN_STOCK',
  'LOW_STOCK',
  'OUT_OF_STOCK',
  'NOT_INITIALIZED',
];

export type StockSort = 'name,asc' | 'quantity,asc' | 'quantity,desc' | 'updatedAt,desc';
export const STOCK_SORTS: readonly StockSort[] = ['name,asc', 'quantity,asc', 'quantity,desc', 'updatedAt,desc'];

export interface StockItem {
  readonly productId: string;
  readonly productName: string;
  readonly sku: string | null;
  readonly category: { readonly id: string; readonly name: string; readonly status: CatalogStatus } | null;
  readonly unit: MeasurementUnit;
  readonly productStatus: CatalogStatus;
  readonly initialized: boolean;
  readonly quantity: string | null;
  readonly minimumQuantity: string | null;
  readonly status: StockStatus;
  readonly updatedAt: string | null;
}

export interface StockMovement {
  readonly id: string;
  readonly productId: string;
  readonly productName: string | null;
  readonly unit: MeasurementUnit | null;
  readonly type: MovementType;
  readonly quantity: string;
  readonly quantityBefore: string;
  readonly quantityAfter: string;
  readonly reason: string | null;
  readonly reference: string | null;
  /** Coût unitaire d'une ENTRÉE : absent sans COST_READ (filtré par le serveur) ou si non saisi. */
  readonly unitCost?: string;
  readonly author: { readonly id: string; readonly fullName: string | null };
  readonly createdAt: string;
}

export interface StockSummary {
  readonly trackedProducts: number;
  readonly notInitializedProducts: number;
  readonly inStock: number;
  readonly lowStock: number;
  readonly outOfStock: number;
  /** Absente sans COST_READ. Estimation au prix d'achat de RÉFÉRENCE (pas une valeur comptable). */
  readonly estimatedValue?: {
    readonly amount: string;
    readonly currency: string;
    readonly productsWithoutReferencePrice: number;
  };
}

export interface OperationResponse {
  readonly movement: StockMovement;
  readonly stock: StockItem;
}

export interface BusinessSettings {
  readonly allowNegativeStock: boolean;
}

export interface StockQuery {
  readonly q: string;
  readonly categoryId: string;
  readonly status: StockStatusFilter;
  readonly sort: StockSort;
  readonly page: number;
  readonly size: number;
}

/** Opérations proposées dans l'interface. */
export type StockOperation = 'initialize' | 'entry' | 'exit' | 'adjustment' | 'minimum';
