package com.fooyre.commerce.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Vente renvoyée par le service. {@code quote} : montants FIGÉS (vente confirmée) ou calculés avec
 * les prix actuels (brouillon). Coûts et marge : uniquement avec COST_READ ({@code null} sinon).
 */
public record SaleView(
        UUID id,
        String reference,
        String status,
        UUID customerId,
        String customerName,
        String currency,
        BigDecimal globalDiscount,
        List<DraftLine> draftLines,
        Quote quote,
        String paymentStatus,
        BigDecimal paidAmount,
        BigDecimal grossMargin,
        Boolean costsComplete,
        UUID authorId,
        String authorName,
        Instant createdAt,
        Instant confirmedAt,
        int version) {

    public record DraftLine(UUID productId, BigDecimal quantity, BigDecimal discount) {
    }

    public record Line(UUID productId, String productName, String sku, String unit, BigDecimal quantity,
            BigDecimal unitPrice, BigDecimal lineDiscount, BigDecimal globalDiscountShare, BigDecimal totalExclTax,
            BigDecimal taxAmount, BigDecimal totalInclTax, BigDecimal unitCost) {
    }

    public record Quote(List<Line> lines, String currency, BigDecimal globalDiscount, Boolean vatEnabled,
            BigDecimal vatRate, String priceEntryMode, BigDecimal grossAmount, BigDecimal discountAmount,
            BigDecimal totalExclTax, BigDecimal taxAmount, BigDecimal totalInclTax, boolean discountWithinLimit) {
    }

    public record Summary(UUID id, String reference, String status, UUID customerId, String customerName,
            String currency, BigDecimal totalInclTax, String paymentStatus, long lineCount, Instant createdAt,
            Instant confirmedAt) {
    }
}
