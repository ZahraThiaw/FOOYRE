package com.fooyre.gestion.domain;

/** Normalisation des textes saisis du catalogue. */
public final class CatalogText {

    private CatalogText() {
    }

    /** Espaces superflus supprimés (début, fin, répétitions internes). */
    public static String normalizeName(String value) {
        return value == null ? null : value.strip().replaceAll("\\s+", " ");
    }

    /** Texte facultatif : {@code null} si absent ou composé uniquement d'espaces. */
    public static String optional(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
