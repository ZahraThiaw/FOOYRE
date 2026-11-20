package com.fooyre.commerce.infrastructure;

import com.fooyre.commerce.domain.SaleNumberGenerator;
import com.fooyre.shared.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Séquence par entreprise ({@code business_sequence}) : {@code INSERT … ON CONFLICT DO UPDATE}
 * verrouille la ligne jusqu'à la fin de la transaction de confirmation → deux confirmations
 * simultanées obtiennent des numéros distincts ; une transaction annulée ne consomme pas de numéro
 * (sans trou). Requête paramétrée, entreprise issue uniquement du contexte vérifié.
 */
@Component
class JdbcSaleNumberGenerator implements SaleNumberGenerator {

    private final JdbcTemplate jdbc;

    JdbcSaleNumberGenerator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long next() {
        Long value = jdbc.queryForObject("""
                INSERT INTO business_sequence (business_id, name, next_value) VALUES (?, 'SALE', 2)
                ON CONFLICT (business_id, name) DO UPDATE SET next_value = business_sequence.next_value + 1
                RETURNING next_value - 1""", Long.class, TenantContext.requireBusinessId());
        if (value == null) {
            throw new IllegalStateException("séquence de vente indisponible");
        }
        return value;
    }
}
