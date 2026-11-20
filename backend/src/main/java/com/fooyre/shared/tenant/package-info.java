/**
 * Contexte multi-entreprises : TenantContext (MVP-1, businessId issu uniquement du jeton validé
 * et revérifié en base). TenantScopedEntity, FK composites généralisées et RLS : avec les
 * premières tables métier portant business_id (CLAUDE.md §3).
 */
package com.fooyre.shared.tenant;
