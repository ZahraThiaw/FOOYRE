package com.fooyre.commerce.domain;

/** Numérotation séquentielle des ventes PAR ENTREPRISE, sûre en concurrence (port). */
public interface SaleNumberGenerator {

    /** Numéro suivant de l'entreprise active ; la ligne de séquence reste verrouillée jusqu'à la fin de la transaction. */
    long next();
}
