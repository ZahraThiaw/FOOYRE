package com.fooyre.commerce.application;

import com.fooyre.commerce.domain.SaleRepository;
import com.fooyre.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Données BRUTES des ventes CONFIRMÉES (jamais les brouillons) pour le module finance, le tableau
 * de bord et l'assistante. Les définitions des indicateurs vivent dans
 * {@code finance.FinancialMetricsService}.
 */
@Service
public class SalesQueryService {

    public record Totals(long count, BigDecimal totalInclTax, BigDecimal totalExclTax) {
    }

    public record ProductSales(UUID productId, String productName, BigDecimal quantity, BigDecimal totalInclTax) {
    }

    /** Coût des marchandises vendues ; {@code linesWithUnknownCost > 0} : coût INCOMPLET. */
    public record CostOfGoods(BigDecimal knownCost, long linesWithUnknownCost) {
    }

    private final SaleRepository sales;

    SalesQueryService(SaleRepository sales) {
        this.sales = sales;
    }

    @Transactional(readOnly = true)
    public Totals confirmedTotals(Instant from, Instant to) {
        Object[] row = first(sales.confirmedTotals(TenantContext.requireBusinessId(), from, to));
        return new Totals(((Number) row[0]).longValue(), (BigDecimal) row[1], (BigDecimal) row[2]);
    }

    @Transactional(readOnly = true)
    public List<ProductSales> topProducts(Instant from, Instant to, int limit) {
        return sales.topProducts(TenantContext.requireBusinessId(), from, to, PageRequest.of(0, limit)).stream()
                .map(row -> new ProductSales((UUID) row[0], (String) row[1], (BigDecimal) row[2], (BigDecimal) row[3]))
                .toList();
    }

    @Transactional(readOnly = true)
    public CostOfGoods costOfGoodsSold(Instant from, Instant to) {
        Object[] row = first(sales.costOfGoodsSold(TenantContext.requireBusinessId(), from, to));
        return new CostOfGoods((BigDecimal) row[0], row[1] == null ? 0 : ((Number) row[1]).longValue());
    }

    private static Object[] first(List<Object[]> rows) {
        return rows.get(0);
    }
}
