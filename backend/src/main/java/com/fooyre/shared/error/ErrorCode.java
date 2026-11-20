package com.fooyre.shared.error;

import org.springframework.http.HttpStatus;

/**
 * Codes d'erreur STABLES du contrat API (docs/api-specification.md §1.3) utilisés par le
 * socle B1. Le frontend les traduit : leur libellé ne doit jamais changer. Les codes métier
 * (INSUFFICIENT_STOCK…) seront ajoutés par les lots qui les produisent.
 */
public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    SESSION_REVOKED(HttpStatus.UNAUTHORIZED),
    /** Connexion refusée : e-mail inconnu, mot de passe erroné ou compte inactif (indistinguables). */
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    /** Route à cookie sans en-tête {@code Origin} attendu ou sans {@code X-Requested-With: fooyre} (ADR-04). */
    CSRF_CHECK_FAILED(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    /** Trop de tentatives (ex. connexion) : réponse générique + {@code Retry-After}, MVP-1.1. */
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    /** Ressource modifiée entre‑temps (version périmée, docs/api-specification.md §1.5). */
    OPTIMISTIC_LOCK(HttpStatus.CONFLICT),
    /** Action impossible dans l'état actuel (ex. modifier un élément archivé). */
    INVALID_STATE_TRANSITION(HttpStatus.UNPROCESSABLE_CONTENT),
    /** Stock (MVP-3) : la sortie ou l'ajustement rendrait le stock négatif alors que c'est interdit (D5). */
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT),
    /** Même clé d'idempotence réutilisée pour une demande différente (§1.4). */
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT),
    /** Stock déjà initialisé : toute correction passe par un ajustement. */
    STOCK_ALREADY_INITIALIZED(HttpStatus.CONFLICT),
    /** Stock non initialisé : initialiser avant toute entrée, sortie ou ajustement. */
    STOCK_NOT_INITIALIZED(HttpStatus.UNPROCESSABLE_CONTENT),
    /** Produit archivé : aucune nouvelle opération de stock. */
    PRODUCT_ARCHIVED(HttpStatus.UNPROCESSABLE_CONTENT),
    /** Élément déjà archivé (ex. client) : nouvel archivage refusé. */
    ALREADY_ARCHIVED(HttpStatus.CONFLICT),
    /** Vente déjà confirmée par une autre demande. */
    SALE_ALREADY_CONFIRMED(HttpStatus.CONFLICT),
    /** Remise au-delà de la limite de l'employé (D14). */
    DISCOUNT_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT),
    /** IA désactivée globalement (AI_ENABLED) ou non approuvée en production. */
    AI_DISABLED(HttpStatus.SERVICE_UNAVAILABLE),
    /** Connexion au fournisseur IA non configurée (clé absente). */
    AI_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE),
    /** Clé du fournisseur IA refusée (401/403) : configuration serveur à vérifier. */
    AI_CREDENTIALS_REJECTED(HttpStatus.SERVICE_UNAVAILABLE),
    /** IA non activée par le propriétaire de l'entreprise (consentement D15). */
    AI_NOT_ENABLED(HttpStatus.FORBIDDEN),
    /** Quota d'utilisation de l'IA de l'entreprise atteint. */
    AI_QUOTA_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS),
    /** Fournisseur IA indisponible (réseau, délai, erreur du service). */
    AI_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    /** Réponse de l'IA rejetée par la validation (format ou chiffres non vérifiables). */
    AI_INVALID_OUTPUT(HttpStatus.BAD_GATEWAY),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
