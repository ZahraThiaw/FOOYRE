package com.fooyre.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fooyre.commerce.domain.TaxCalculator;
import com.fooyre.commerce.domain.TaxCalculator.LineInput;
import com.fooyre.commerce.domain.TaxCalculator.Result;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Calcul des montants (règles PROVISOIRES du §3.2, O2) : TVA désactivée, HT, TTC, remises, arrondis. */
class TaxCalculatorTest {

    private static LineInput line(String price, String quantity, String discount) {
        return new LineInput(new BigDecimal(price), new BigDecimal(quantity), new BigDecimal(discount));
    }

    @Test
    void vatDisabledMeansExclEqualsInclEqualsNetAfterDiscounts() {
        Result r = TaxCalculator.compute(List.of(line("1000", "2", "100"), line("1500", "1", "0")),
                new BigDecimal("300"), false, new BigDecimal("18"), "TTC", 0);
        assertThat(r.gross()).isEqualByComparingTo("3500");
        assertThat(r.discount()).isEqualByComparingTo("400");
        assertThat(r.totalExclTax()).isEqualByComparingTo("3100");
        assertThat(r.totalInclTax()).isEqualByComparingTo("3100");
        assertThat(r.taxAmount()).isEqualByComparingTo("0");
        // Remise globale 300 répartie au prorata des nets (1900 / 1500) : 168 + 132.
        assertThat(r.lines().get(0).globalDiscountShare()).isEqualByComparingTo("168");
        assertThat(r.lines().get(1).globalDiscountShare()).isEqualByComparingTo("132");
    }

    @Test
    void htModeAddsRoundedTaxPerLineAndTotalsAreSumsOfLines() {
        Result r = TaxCalculator.compute(List.of(line("999", "1", "0"), line("333", "3", "0")),
                BigDecimal.ZERO, true, new BigDecimal("18"), "HT", 0);
        // 999 × 18 % = 179.82 → 180 ; 999 × 18 % = 179.82 → 180.
        assertThat(r.lines().get(0).taxAmount()).isEqualByComparingTo("180");
        assertThat(r.taxAmount()).isEqualByComparingTo("360");
        assertThat(r.totalInclTax()).isEqualByComparingTo("2358");
    }

    @Test
    void ttcModeExtractsTaxFromInclusivePrice() {
        Result r = TaxCalculator.compute(List.of(line("1180", "1", "0")), BigDecimal.ZERO, true,
                new BigDecimal("18"), "TTC", 0);
        assertThat(r.totalExclTax()).isEqualByComparingTo("1000");
        assertThat(r.taxAmount()).isEqualByComparingTo("180");
        assertThat(r.totalInclTax()).isEqualByComparingTo("1180");
    }

    @Test
    void eurosKeepTwoDecimalsAndHalfUpRounding() {
        Result r = TaxCalculator.compute(List.of(line("10.05", "1.5", "0")), BigDecimal.ZERO, false,
                BigDecimal.ZERO, "TTC", 2);
        assertThat(r.totalInclTax()).isEqualByComparingTo("15.08"); // 15.075 → 15.08
    }

    @Test
    void discountsAboveTheAmountAreRefused() {
        assertThatThrownBy(() -> TaxCalculator.compute(List.of(line("100", "1", "101")), BigDecimal.ZERO, false,
                BigDecimal.ZERO, "TTC", 0)).isInstanceOf(TaxCalculator.InvalidDiscountException.class);
        assertThatThrownBy(() -> TaxCalculator.compute(List.of(line("100", "1", "50")), new BigDecimal("51"), false,
                BigDecimal.ZERO, "TTC", 0)).isInstanceOf(TaxCalculator.InvalidDiscountException.class);
    }
}
