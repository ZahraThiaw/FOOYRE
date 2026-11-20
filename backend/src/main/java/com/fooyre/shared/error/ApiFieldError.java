package com.fooyre.shared.error;

/**
 * Erreur de validation d'un champ : chemin du champ, code stable, message français facultatif.
 * La valeur rejetée n'est jamais renvoyée (elle peut contenir des données personnelles).
 */
public record ApiFieldError(String field, FieldErrorCode code, String message) {
}
