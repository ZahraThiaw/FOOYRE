package com.fooyre.shared.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * Échelles monétaires (CLAUDE.md §4, ADR-06) : montants à l'échelle de la devise (XOF : 0 décimale,
 * norme ISO 4217). Aucune conversion en {@code double}.
 * Les montants saisis qui dépassent l'échelle autorisée sont REFUSÉS par les appelants (jamais
 * arrondis en silence) ; {@link #toCurrencyScale} ne fait qu'ajuster l'affichage d'une valeur
 * déjà valide (ex. {@code 1500.00} → {@code 1500}).
 */
public final class CurrencyAmounts {

    private CurrencyAmounts() {
    }

    /** Nombre de décimales de la devise (ISO 4217), ex. 0 pour XOF. */
    public static int fractionDigits(String currencyCode) {
        int digits = Currency.getInstance(currencyCode).getDefaultFractionDigits();
        return Math.max(digits, 0);
    }

    /** Nombre de décimales significatives (zéros finaux ignorés). */
    public static int significantScale(BigDecimal value) {
        return Math.max(value.stripTrailingZeros().scale(), 0);
    }

    /** Vrai si le montant n'a pas plus de décimales significatives que la devise n'en permet. */
    public static boolean fitsCurrency(BigDecimal amount, String currencyCode) {
        return significantScale(amount) <= fractionDigits(currencyCode);
    }

    /** Montant VALIDE présenté à l'échelle de la devise (aucun arrondi effectif). */
    public static BigDecimal toCurrencyScale(BigDecimal amount, String currencyCode) {
        return amount.setScale(fractionDigits(currencyCode), RoundingMode.UNNECESSARY);
    }

    /** Coût présenté sans perte : décimales significatives conservées, au moins celles de la devise. */
    public static BigDecimal toUnitCostScale(BigDecimal cost, String currencyCode) {
        int scale = Math.max(significantScale(cost), fractionDigits(currencyCode));
        return cost.setScale(scale, RoundingMode.UNNECESSARY);
    }
}
