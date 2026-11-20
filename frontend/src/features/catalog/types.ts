/**
 * Contrat API du catalogue (MVP‑2, docs/api-specification.md §2.3). Montants en CHAÎNES décimales
 * (jamais convertis en `number`). Le prix d'achat n'est présent que si l'utilisatrice a la
 * permission COST_READ : le serveur le retire sinon (aucun masquage côté client).
 */

export type CatalogStatus = 'ACTIVE' | 'ARCHIVED';
export type StatusFilter = CatalogStatus | 'ALL';

/** Unités de mesure (liste fermée, codes stables du backend). */
export const MEASUREMENT_UNITS = [
  'PIECE',
  'UNIT',
  'KILOGRAM',
  'GRAM',
  'LITRE',
  'MILLILITRE',
  'METRE',
  'PACK',
  'CARTON',
  'LOT',
] as const;
export type MeasurementUnit = (typeof MEASUREMENT_UNITS)[number];

/**
 * Décimales autorisées pour une QUANTITÉ dans chaque unité (même règle que le backend,
 * MeasurementUnit.quantityDecimals) : unités dénombrables entières, mesures continues 3 décimales.
 */
export const UNIT_QUANTITY_DECIMALS: Readonly<Record<MeasurementUnit, number>> = {
  PIECE: 0,
  UNIT: 0,
  KILOGRAM: 3,
  GRAM: 3,
  LITRE: 3,
  MILLILITRE: 3,
  METRE: 3,
  PACK: 0,
  CARTON: 0,
  LOT: 0,
};

export interface PageMetadata {
  readonly number: number;
  readonly size: number;
  readonly totalElements: number;
  readonly totalPages: number;
}

export interface PageResponse<T> {
  readonly content: readonly T[];
  readonly page: PageMetadata;
}

export interface Category {
  readonly id: string;
  readonly name: string;
  readonly description: string | null;
  readonly status: CatalogStatus;
  /** Nombre réel de produits ACTIFS rattachés (calculé par le serveur). */
  readonly activeProductCount: number;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly version: number;
}

export interface CategorySummary {
  readonly id: string;
  readonly name: string;
  readonly status: CatalogStatus;
}

export interface Product {
  readonly id: string;
  readonly name: string;
  readonly description: string | null;
  readonly sku: string | null;
  readonly barcode: string | null;
  readonly unit: MeasurementUnit;
  readonly category: CategorySummary | null;
  /** Absent sans COST_READ, ou si non renseigné. */
  readonly purchasePrice?: string;
  readonly salePrice: string;
  readonly currency: string;
  readonly status: CatalogStatus;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly version: number;
}

export interface CategoryInput {
  readonly name: string;
  readonly description: string | null;
  readonly version?: number;
}

export interface ProductInput {
  readonly name: string;
  readonly description: string | null;
  readonly sku: string | null;
  readonly barcode: string | null;
  readonly categoryId: string | null;
  readonly unit: MeasurementUnit;
  /** Envoyé uniquement avec COST_READ (ignoré par le serveur sinon) ; décimales de la devise au maximum. */
  readonly purchasePrice: string | null;
  readonly salePrice: string;
  readonly version?: number;
}

export type ProductSort =
  | 'name,asc'
  | 'name,desc'
  | 'createdAt,desc'
  | 'createdAt,asc'
  | 'salePrice,asc'
  | 'salePrice,desc';

export const PRODUCT_SORTS: readonly ProductSort[] = [
  'name,asc',
  'name,desc',
  'createdAt,desc',
  'createdAt,asc',
  'salePrice,asc',
  'salePrice,desc',
];

export interface ProductQuery {
  readonly q: string;
  readonly categoryId: string;
  readonly status: StatusFilter;
  readonly sort: ProductSort;
  readonly page: number;
  readonly size: number;
}
