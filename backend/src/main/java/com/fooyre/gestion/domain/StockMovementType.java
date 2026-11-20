package com.fooyre.gestion.domain;

/**
 * Types de mouvement de stock (MVP-3). La quantité d'un mouvement est toujours positive ; le sens
 * est porté par le type. Les mouvements liés aux achats et aux ventes (PURCHASE_IN, SALE_OUT…)
 * s'ajouteront avec leurs modules.
 */
public enum StockMovementType {
    /** Stock initial (une seule fois par produit). */
    INITIAL(true, false),
    /** Entrée : réception de marchandises (motif obligatoire). */
    ENTRY(true, true),
    /** Sortie : perte, casse, usage interne… (motif obligatoire). */
    EXIT(false, true),
    /** Correction à la hausse (motif obligatoire). */
    ADJUSTMENT_IN(true, true),
    /** Correction à la baisse (motif obligatoire). */
    ADJUSTMENT_OUT(false, true),
    /** Sortie liée à une vente confirmée (créée UNIQUEMENT par la confirmation de la vente). */
    SALE_OUT(false, true);

    private final boolean increases;
    private final boolean reasonRequired;

    StockMovementType(boolean increases, boolean reasonRequired) {
        this.increases = increases;
        this.reasonRequired = reasonRequired;
    }

    public boolean increases() {
        return increases;
    }

    public boolean reasonRequired() {
        return reasonRequired;
    }
}
