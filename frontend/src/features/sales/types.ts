/**
 * Contrat API des ventes. Montants et quantités en CHAÎNES décimales, toujours calculés par le
 * serveur : le frontend n'envoie que produits, quantités et remises, et affiche les totaux reçus.
 */
export type SaleStatus = 'DRAFT' | 'CONFIRMED' | 'CANCELLED';
/** Calculé par le serveur ; aucun paiement n'est encore enregistré (Wave non connecté). */
export type PaymentStatus = 'UNPAID' | 'PARTIALLY_PAID' | 'PAID';

export interface SaleLineView {
  readonly productId: string;
  readonly productName: string;
  readonly sku: string | null;
  readonly unit: string;
  readonly quantity: string;
  readonly unitPrice: string;
  readonly lineDiscount: string;
  readonly globalDiscountShare: string;
  readonly totalExclTax: string;
  readonly taxAmount: string;
  readonly totalInclTax: string;
  /** Uniquement avec COST_READ (retiré par le serveur sinon) ; absent si coût inconnu. */
  readonly unitCost?: string | null;
}

export interface SaleQuote {
  readonly lines: readonly SaleLineView[];
  readonly currency: string;
  readonly globalDiscount: string;
  readonly vatEnabled: boolean | null;
  readonly vatRate: string | null;
  readonly priceEntryMode: string | null;
  readonly grossAmount: string;
  readonly discountAmount: string;
  readonly totalExclTax: string;
  readonly taxAmount: string;
  readonly totalInclTax: string;
  readonly discountWithinLimit: boolean;
}

export interface Sale {
  readonly id: string;
  readonly reference: string | null;
  readonly status: SaleStatus;
  readonly customerId: string | null;
  readonly customerName: string | null;
  readonly currency: string;
  readonly globalDiscount: string;
  readonly draftLines: readonly { productId: string; quantity: string; discount: string }[];
  readonly quote: SaleQuote | null;
  readonly paymentStatus: PaymentStatus | null;
  readonly paidAmount: string | null;
  readonly grossMargin: string | null;
  readonly costsComplete: boolean | null;
  readonly authorName: string | null;
  readonly createdAt: string;
  readonly confirmedAt: string | null;
  readonly version: number;
}

export interface SaleSummary {
  readonly id: string;
  readonly reference: string | null;
  readonly status: SaleStatus;
  readonly customerId: string | null;
  readonly customerName: string | null;
  readonly currency: string;
  readonly totalInclTax: string | null;
  readonly paymentStatus: PaymentStatus | null;
  readonly lineCount: number;
  readonly createdAt: string;
  readonly confirmedAt: string | null;
}

export interface SaleLineInput {
  readonly productId: string;
  readonly quantity: string;
  readonly discount: string | null;
}

export interface SaleInput {
  readonly customerId: string | null;
  readonly lines: readonly SaleLineInput[];
  readonly globalDiscount: string | null;
  readonly version?: number;
}

export interface SalesMetrics {
  readonly salesCount: number;
  readonly revenueInclTax: string;
  readonly revenueExclTax: string;
  readonly collections: string;
  readonly grossMargin: string | null;
  readonly marginReliable: boolean;
  readonly currency: string;
}

export interface Dashboard {
  readonly today: SalesMetrics | null;
  readonly month: SalesMetrics | null;
  readonly salesCountToday: number;
  readonly salesCountMonth: number;
  readonly activeCustomers: number;
  readonly outOfStock: number;
  readonly lowStock: number;
  readonly trackedProducts: number;
  readonly recentSales: readonly SaleSummary[];
  readonly financialsVisible: boolean;
  readonly paymentsConnected: boolean;
}
