package com.fooyre.shared.audit;

/**
 * Actions journalisées (liste fermée, alignée sur la contrainte {@code ck_audit_log_action}).
 * Ajouter une action = nouvelle migration Flyway + mise à jour de docs/architecture.md.
 */
public enum AuditAction {
    /** Connexion réussie (session ouverte). */
    LOGIN_SUCCEEDED,
    /** Identifiants refusés (compte inconnu, mot de passe erroné ou compte inactif). */
    LOGIN_FAILED,
    /** Tentative refusée avant vérification : trop d'échecs récents. */
    LOGIN_RATE_LIMITED,
    /** Déconnexion : session révoquée côté serveur. */
    LOGOUT,
    /** Session révoquée par le serveur (compte désactivé, appartenance révoquée…). */
    SESSION_REVOKED,
    /** Refresh token déjà remplacé présenté hors de la fenêtre de tolérance : session révoquée. */
    TOKEN_REUSE_DETECTED,
    /** Compte utilisateur créé. */
    ACCOUNT_CREATED,
    /** Entreprise créée (avec son appartenance propriétaire). */
    BUSINESS_CREATED,
    /** Catalogue (MVP-2, migration V3) : aucune donnée commerciale (prix) dans l'événement. */
    CATEGORY_CREATED,
    CATEGORY_UPDATED,
    CATEGORY_ARCHIVED,
    PRODUCT_CREATED,
    PRODUCT_UPDATED,
    PRODUCT_ARCHIVED,
    /** Stock (MVP-3, migration V4) : quantités et motifs dans le mouvement, jamais dans l'événement. */
    STOCK_INITIALIZED,
    STOCK_ENTRY_RECORDED,
    STOCK_EXIT_RECORDED,
    STOCK_ADJUSTED,
    STOCK_MINIMUM_CHANGED,
    /** Paramètre sensible de l'entreprise modifié (ex. stock négatif, D5/D20). */
    SETTINGS_CHANGED,
    /**
     * Sortie de stock refusée pour stock insuffisant (migration V5) : produit en cible, motif codé
     * INSUFFICIENT_STOCK, résultat DENIED. Ni quantité, ni texte saisi.
     */
    STOCK_EXIT_REJECTED_INSUFFICIENT,
    /** Clients (MVP-4A, migration V6) : aucune donnée personnelle dans l'événement. */
    CUSTOMER_CREATED,
    CUSTOMER_UPDATED,
    CUSTOMER_ARCHIVED,
    /** Ventes (migration V7). Aucun montant ni nom de client dans l'événement. */
    SALE_DRAFT_SAVED,
    SALE_CONFIRMED,
    SALE_DRAFT_CANCELLED,
    DISCOUNT_APPLIED,
    DISCOUNT_LIMIT_EXCEEDED,
    SALES_POLICY_CHANGED,
    /** IA (D15) : consentement et requêtes (jamais le contenu de la question ni de la réponse). */
    AI_CONSENT_CHANGED,
    AI_QUERY
}
