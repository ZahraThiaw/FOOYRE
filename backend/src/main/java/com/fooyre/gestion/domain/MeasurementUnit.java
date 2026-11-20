package com.fooyre.gestion.domain;

/**
 * Unités de mesure d'un produit (liste fermée, codes stables du contrat API). Ajouter une unité =
 * nouvelle constante + migration Flyway mettant à jour {@code ck_product_unit} + traduction
 * frontend.
 * <p>
 * Précision des QUANTITÉS (stock, MVP-3) : unités dénombrables → quantités entières ; unités de
 * mesure continues (masse, volume, longueur) → au plus 3 décimales (colonne NUMERIC(19,3)). Une
 * quantité plus précise est refusée, jamais arrondie.
 */
public enum MeasurementUnit {
    PIECE(0),
    UNIT(0),
    KILOGRAM(3),
    GRAM(3),
    LITRE(3),
    MILLILITRE(3),
    METRE(3),
    PACK(0),
    CARTON(0),
    LOT(0);

    private final int quantityDecimals;

    MeasurementUnit(int quantityDecimals) {
        this.quantityDecimals = quantityDecimals;
    }

    /** Nombre maximal de décimales d'une quantité exprimée dans cette unité. */
    public int quantityDecimals() {
        return quantityDecimals;
    }
}
