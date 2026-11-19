/**
 * Contrat de GET /api/v1/me (docs/api-specification.md §2.1, « Contrat de /me »).
 *
 * Les rôles et permissions servent UNIQUEMENT à adapter l'affichage. Le backend reste seul
 * juge des autorisations : il vérifie chaque requête à partir du jeton (appartenance active,
 * rôle, permissions recalculées), jamais à partir de ce que le frontend croit savoir.
 */

/** Rôles portés par une appartenance à une entreprise (ADR‑05). */
export type BusinessRole = 'BUSINESS_OWNER' | 'BUSINESS_MANAGER' | 'EMPLOYEE';

/** Permissions nommées (docs/architecture.md §5.3). */
export const PERMISSIONS = [
  'BUSINESS_READ',
  'BUSINESS_PROFILE_UPDATE',
  'BUSINESS_SETTINGS_READ',
  'BUSINESS_SETTINGS_UPDATE',
  'SALES_POLICY_MANAGE',
  'MEMBER_MANAGE',
  'SUPPORT_ACCESS_GRANT',
  'AI_CONSENT_MANAGE',
  'STOREFRONT_MANAGE',
  'CATALOG_READ',
  'CATALOG_WRITE',
  'COST_READ',
  'STOCK_READ',
  'STOCK_ADJUST',
  'SUPPLIER_MANAGE',
  'PURCHASE_MANAGE',
  'CUSTOMER_READ',
  'CUSTOMER_WRITE',
  'CUSTOMER_BALANCE_READ',
  'CUSTOMER_ARCHIVE',
  'SALE_CREATE',
  'PAYMENT_CREATE',
  'SALE_READ_ALL',
  'SALE_PRICE_OVERRIDE',
  'SALE_DISCOUNT_UNLIMITED',
  'SALE_DISCOUNT_LIMITED',
  'SALE_CANCEL',
  'PAYMENT_VOID',
  'REFUND_CREATE',
  'CUSTOMER_CREDIT_USE',
  'ORDER_REQUEST_MANAGE',
  'EXPENSE_MANAGE',
  'TREASURY_MANAGE',
  'TREASURY_OWNER_MOVEMENT',
  'FINANCE_READ',
  'REPORT_EXPORT',
  'DASHBOARD_FULL',
  'DASHBOARD_OWN_DAY',
  'AI_USE',
  'PLATFORM_BUSINESS_MANAGE',
  'PLATFORM_PLAN_MANAGE',
  'PLATFORM_AUDIT_READ',
  'PLATFORM_AI_USAGE_READ',
] as const;
export type Permission = (typeof PERMISSIONS)[number];

/** Appartenance ACTIVE de l'utilisateur à une entreprise. */
export interface MeBusiness {
  readonly id: string;
  readonly name: string;
  readonly role: BusinessRole;
}

export interface MeActiveBusiness {
  readonly id: string;
  readonly name: string;
  /** Code ISO 4217 (XOF au lancement). */
  readonly currency: string;
}

export interface MeActiveMembership {
  readonly id: string;
  readonly role: BusinessRole;
  /** Limite de remise de l'EMPLOYÉ (D14), chaîne décimale ; `null` pour les autres rôles. */
  readonly employeeMaxDiscountRate: string | null;
}

export interface MeResponse {
  readonly id: string;
  readonly email: string;
  readonly fullName: string;
  readonly locale: string;
  /** Attribut GLOBAL de l'utilisateur (ADR‑05), indépendant des entreprises. */
  readonly platformAdmin: boolean;
  /** Entreprise active. `activeBusiness` et `activeMembership` sont tous deux renseignés ou tous deux `null`. */
  readonly activeBusiness: MeActiveBusiness | null;
  readonly activeMembership: MeActiveMembership | null;
  /**
   * Permissions effectives dans le contexte actif (calculées par le backend). Type `string`
   * pour tolérer une permission future inconnue du frontend ; vérifier avec `hasPermission`.
   */
  readonly permissions: readonly string[];
  /** Appartenances ACTIVES (pour proposer un changement d'entreprise). */
  readonly businesses: readonly MeBusiness[];
}

/** Réponse de POST /api/v1/auth/switch-business (le refresh token tourne via le cookie). */
export interface SwitchBusinessResponse {
  readonly accessToken: string;
  /** Durée de validité du jeton d'accès, en secondes. */
  readonly expiresIn: number;
}
