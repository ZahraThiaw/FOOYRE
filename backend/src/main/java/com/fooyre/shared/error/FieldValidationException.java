package com.fooyre.shared.error;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Règle de validation métier non respectée sur un ou plusieurs champs (ex. SKU déjà utilisé,
 * catégorie archivée) : {@code 400 VALIDATION_ERROR} avec {@code errors[{field, code}]}, même
 * format que la validation Jakarta. La valeur refusée n'est jamais renvoyée.
 */
public class FieldValidationException extends RuntimeException {

    private final Map<String, FieldErrorCode> errors;

    public FieldValidationException(Map<String, FieldErrorCode> errors) {
        super("Validation métier refusée : " + errors.keySet());
        this.errors = Map.copyOf(errors);
    }

    public static FieldValidationException of(String field, FieldErrorCode code) {
        Map<String, FieldErrorCode> errors = new LinkedHashMap<>();
        errors.put(field, code);
        return new FieldValidationException(errors);
    }

    public Map<String, FieldErrorCode> errors() {
        return errors;
    }
}
