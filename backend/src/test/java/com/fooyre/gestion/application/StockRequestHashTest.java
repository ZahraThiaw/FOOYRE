package com.fooyre.gestion.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Empreinte d'idempotence : un mouvement SANS coût garde exactement l'empreinte calculée avant la
 * migration V5 (les rejeux des mouvements existants restent reconnus) ; le coût, s'il est
 * renseigné, distingue deux demandes.
 */
class StockRequestHashTest {

    private static final UUID PRODUCT = UUID.fromString("0b6f0d3e-3c1a-4b8e-9d1e-2a5b6c7d8e9f");

    @Test
    void hashWithoutUnitCostIsUnchangedFromTheV4Format() throws Exception {
        String v4Canonical = String.join("\u001f", PRODUCT.toString(), "ENTRY", "10", "", "Réception", "BL-1");
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(v4Canonical.getBytes(StandardCharsets.UTF_8)));
        assertThat(StockService.requestHash(PRODUCT, "ENTRY", new BigDecimal("10.000"), null, "Réception", "BL-1",
                null)).isEqualTo(expected);
    }

    @Test
    void unitCostDistinguishesRequestsAndIsNormalized() {
        String without = StockService.requestHash(PRODUCT, "ENTRY", BigDecimal.TEN, null, "R", null, null);
        String with = StockService.requestHash(PRODUCT, "ENTRY", BigDecimal.TEN, null, "R", null, new BigDecimal("650"));
        assertThat(with).isNotEqualTo(without)
                .isEqualTo(StockService.requestHash(PRODUCT, "ENTRY", BigDecimal.TEN, null, "R", null,
                        new BigDecimal("650.0000")));
        assertThat(with).isNotEqualTo(StockService.requestHash(PRODUCT, "ENTRY", BigDecimal.TEN, null, "R", null,
                new BigDecimal("651")));
    }
}
