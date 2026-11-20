package com.fooyre.finance.application;

import com.fooyre.business.application.BusinessProfileService;
import com.fooyre.commerce.application.SalesQueryService;
import com.fooyre.commerce.application.SalesQueryService.CostOfGoods;
import com.fooyre.commerce.application.SalesQueryService.Totals;
import com.fooyre.shared.money.CurrencyAmounts;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SEULE source des indicateurs financiers (CLAUDE.md §4, cahier des charges §3). MVP :
 * <ul>
 *   <li>chiffre d'affaires = total TTC des ventes CONFIRMÉES de la période (date de confirmation,
 *       fuseau de l'entreprise) — brouillons et ventes abandonnées exclus ; aucune annulation de
 *       vente confirmée n'existe encore (ventes brutes = ventes nettes ajustées) ;</li>
 *   <li>encaissements = 0 : aucun paiement n'est enregistré (Wave non connecté) — présenté comme tel ;</li>
 *   <li>marge brute (jamais un bénéfice) = HT − coût des marchandises vendues, UNIQUEMENT si le coût
 *       de toutes les lignes est connu ; sinon {@code null} (marge non fiable, non présentée).</li>
 * </ul>
 */
@Service
public class FinancialMetricsService {

    public record Period(Instant from, Instant to) {
    }

    public record SalesMetrics(long salesCount, BigDecimal revenueInclTax, BigDecimal revenueExclTax,
            BigDecimal collections, BigDecimal grossMargin, boolean marginReliable, String currency) {
    }

    private final SalesQueryService sales;
    private final BusinessProfileService profiles;
    private final Clock clock;

    FinancialMetricsService(SalesQueryService sales, BusinessProfileService profiles, Clock clock) {
        this.sales = sales;
        this.profiles = profiles;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Period today() {
        ZoneId zone = zone();
        LocalDate day = LocalDate.now(clock.withZone(zone));
        return new Period(day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant());
    }

    @Transactional(readOnly = true)
    public Period currentMonth() {
        ZoneId zone = zone();
        LocalDate first = LocalDate.now(clock.withZone(zone)).withDayOfMonth(1);
        return new Period(first.atStartOfDay(zone).toInstant(), first.plusMonths(1).atStartOfDay(zone).toInstant());
    }

    /** @param withMargin calcul de la marge (appelant autorisé COST_READ uniquement) */
    @Transactional(readOnly = true)
    public SalesMetrics salesMetrics(Period period, boolean withMargin) {
        String currency = profiles.activeBusinessProfile().currency();
        Totals totals = sales.confirmedTotals(period.from(), period.to());
        BigDecimal margin = null;
        boolean reliable = false;
        if (withMargin && totals.count() > 0) {
            CostOfGoods cost = sales.costOfGoodsSold(period.from(), period.to());
            reliable = cost.linesWithUnknownCost() == 0;
            if (reliable) {
                margin = totals.totalExclTax().subtract(
                        cost.knownCost().setScale(CurrencyAmounts.fractionDigits(currency), RoundingMode.HALF_UP));
            }
        }
        int scale = CurrencyAmounts.fractionDigits(currency);
        return new SalesMetrics(totals.count(), totals.totalInclTax().setScale(scale, RoundingMode.HALF_UP),
                totals.totalExclTax().setScale(scale, RoundingMode.HALF_UP), BigDecimal.ZERO.setScale(scale),
                margin == null ? null : margin.setScale(scale, RoundingMode.HALF_UP), reliable, currency);
    }

    private ZoneId zone() {
        try {
            return ZoneId.of(profiles.activeBusinessProfile().timezone());
        } catch (RuntimeException invalid) {
            return ZoneId.of("Africa/Dakar");
        }
    }
}
