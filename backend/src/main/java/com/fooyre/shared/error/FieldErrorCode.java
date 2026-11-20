package com.fooyre.shared.error;

/** Codes stables des erreurs de validation par champ (docs/api-specification.md §1.3). */
public enum FieldErrorCode {
    REQUIRED,
    INVALID_FORMAT,
    TOO_LONG,
    TOO_SHORT,
    INVALID_VALUE,
    ALREADY_EXISTS,
    /** Élément référencé archivé (ex. catégorie archivée choisie pour un produit), MVP-2. */
    ARCHIVED,
    /** Valeur verrouillée : ne peut plus être modifiée (ex. unité d'un produit dont le stock est initialisé), MVP-3. */
    LOCKED
}
