/**
 * Module GESTION — MVP-2 : catalogue (catégories et produits), données isolées par entreprise
 * (TenantScopedEntity, requêtes filtrées par business_id, RLS PostgreSQL). Stocks,
 * approvisionnements, clients et fournisseurs : lots suivants.
 * Couches : api -> application -> domain ; infrastructure pour les adaptateurs. Aucun accès aux
 * repositories ou entités d'un autre module (règles ArchUnit).
 */
package com.fooyre.gestion;
