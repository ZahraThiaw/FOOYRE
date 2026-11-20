package com.fooyre.commerce.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * SEUL point de calcul des montants d'une vente (CLAUDE.md §4, cahier des charges §3.2).
 *
 * <p>⚠️ Algorithme PROVISOIRE, non validé fiscalement (point ouvert O2) : calcul par ligne,
 * remise globale répartie au prorata des montants nets de ligne (reliquat d'arrondi affecté à la
 * ligne de plus fort montant), arrondi {@code HALF_UP} à l'échelle de la devise, totaux = somme
 * des lignes arrondies. Toute correction issue de la validation comptable se fait ICI uniquement.
 *
 * <ul>
 *   <li>TVA désactivée : HT = TTC = net, TVA = 0 ;</li>
 *   <li>prix saisis HT : HT = net, TVA = arrondi(HT × taux), TTC = HT + TVA ;</li>
 *   <li>prix saisis TTC : TTC = net, HT = arrondi(TTC / (1 + taux)), TVA = TTC − HT.</li>
 * </ul>
 */
public final class TaxCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private TaxCalculator() {
    }

    public record LineInput(BigDecimal unitPrice, BigDecimal quantity, BigDecimal lineDiscount) {
    }

    public record LineAmounts(BigDecimal gross, BigDecimal lineDiscount, BigDecimal globalDiscountShare,
            BigDecimal totalExclTax, BigDecimal taxAmount, BigDecimal totalInclTax) {

        public BigDecimal net() {
            return gross.subtract(lineDiscount).subtract(globalDiscountShare);
        }
    }

    public record Result(List<LineAmounts> lines, BigDecimal gross, BigDecimal discount, BigDecimal totalExclTax,
            BigDecimal taxAmount, BigDecimal totalInclTax) {
    }

    /** Montants invalides (remise supérieure au montant) : refusés, jamais corrigés en silence. */
    public static final class InvalidDiscountException extends RuntimeException {
        private final int lineIndex;

        InvalidDiscountException(int lineIndex) {
            super("remise invalide");
            this.lineIndex = lineIndex;
        }

        /** -1 : remise globale. */
        public int lineIndex() {
            return lineIndex;
        }
    }

    public static Result compute(List<LineInput> inputs, BigDecimal globalDiscount, boolean vatEnabled,
            BigDecimal vatRate, String priceEntryMode, int scale) {
        List<BigDecimal> grosses = new ArrayList<>();
        List<BigDecimal> nets = new ArrayList<>();
        BigDecimal sumNet = BigDecimal.ZERO;
        for (int i = 0; i < inputs.size(); i++) {
            LineInput input = inputs.get(i);
            BigDecimal gross = input.unitPrice().multiply(input.quantity()).setScale(scale, RoundingMode.HALF_UP);
            BigDecimal discount = input.lineDiscount() == null ? BigDecimal.ZERO : input.lineDiscount();
            if (discount.signum() < 0 || discount.compareTo(gross) > 0) {
                throw new InvalidDiscountException(i);
            }
            grosses.add(gross);
            BigDecimal net = gross.subtract(discount);
            nets.add(net);
            sumNet = sumNet.add(net);
        }
        BigDecimal global = globalDiscount == null ? BigDecimal.ZERO : globalDiscount;
        if (global.signum() < 0 || global.compareTo(sumNet) > 0) {
            throw new InvalidDiscountException(-1);
        }
        // Répartition de la remise globale au prorata des montants nets de ligne.
        List<BigDecimal> shares = new ArrayList<>();
        BigDecimal distributed = BigDecimal.ZERO;
        int largest = 0;
        for (int i = 0; i < nets.size(); i++) {
            BigDecimal share = global.signum() == 0 || sumNet.signum() == 0 ? BigDecimal.ZERO
                    : global.multiply(nets.get(i)).divide(sumNet, scale, RoundingMode.HALF_UP);
            shares.add(share);
            distributed = distributed.add(share);
            if (nets.get(i).compareTo(nets.get(largest)) > 0) {
                largest = i;
            }
        }
        if (!nets.isEmpty()) {
            shares.set(largest, shares.get(largest).add(global.subtract(distributed)));
        }

        BigDecimal rate = vatRate == null ? BigDecimal.ZERO : vatRate.divide(HUNDRED, MathContext.DECIMAL128);
        List<LineAmounts> lines = new ArrayList<>();
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal discount = BigDecimal.ZERO;
        BigDecimal ht = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal ttc = BigDecimal.ZERO;
        for (int i = 0; i < inputs.size(); i++) {
            BigDecimal lineDiscount = grosses.get(i).subtract(nets.get(i));
            BigDecimal net = nets.get(i).subtract(shares.get(i));
            if (net.signum() < 0) {
                throw new InvalidDiscountException(-1);
            }
            BigDecimal lineHt;
            BigDecimal lineTax;
            BigDecimal lineTtc;
            if (!vatEnabled) {
                lineHt = net;
                lineTax = BigDecimal.ZERO.setScale(scale);
                lineTtc = net;
            } else if ("HT".equals(priceEntryMode)) {
                lineHt = net;
                lineTax = net.multiply(rate).setScale(scale, RoundingMode.HALF_UP);
                lineTtc = lineHt.add(lineTax);
            } else {
                lineTtc = net;
                lineHt = net.divide(BigDecimal.ONE.add(rate), MathContext.DECIMAL128).setScale(scale, RoundingMode.HALF_UP);
                lineTax = lineTtc.subtract(lineHt);
            }
            lines.add(new LineAmounts(grosses.get(i), lineDiscount.setScale(scale), shares.get(i).setScale(scale),
                    lineHt.setScale(scale), lineTax.setScale(scale), lineTtc.setScale(scale)));
            gross = gross.add(grosses.get(i));
            discount = discount.add(lineDiscount).add(shares.get(i));
            ht = ht.add(lineHt);
            tax = tax.add(lineTax);
            ttc = ttc.add(lineTtc);
        }
        return new Result(lines, gross.setScale(scale), discount.setScale(scale), ht.setScale(scale),
                tax.setScale(scale), ttc.setScale(scale));
    }
}
