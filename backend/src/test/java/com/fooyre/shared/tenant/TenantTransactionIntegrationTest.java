package com.fooyre.shared.tenant;

import static com.fooyre.support.TenantTestSupport.asBusiness;
import static org.assertj.core.api.Assertions.assertThat;

import com.fooyre.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Le contexte d'entreprise est transmis à PostgreSQL au début de CHAQUE transaction, localement. */
@IntegrationTest
class TenantTransactionIntegrationTest {

    private static final String READ_SETTING = "SELECT current_setting('app.business_id', true)";

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    private String settingInTransaction() {
        return new TransactionTemplate(transactionManager).execute(status -> jdbc.queryForObject(READ_SETTING, String.class));
    }

    @Test
    void theApplicationUsesTheTenantAwareTransactionManager() {
        assertThat(transactionManager).isInstanceOf(TenantAwareJpaTransactionManager.class);
    }

    @Test
    void activeBusinessIsVisibleInsideTheTransaction() {
        UUID business = UUID.randomUUID();
        assertThat(asBusiness(business, this::settingInTransaction)).isEqualTo(business.toString());
    }

    @Test
    void withoutActiveBusinessTheSettingIsEmpty() {
        assertThat(settingInTransaction()).isEmpty();
    }

    @Test
    void theSettingNeverOutlivesItsTransaction() {
        UUID business = UUID.randomUUID();
        asBusiness(business, this::settingInTransaction);
        // Hors transaction (auto‑commit) et dans une transaction sans contexte : aucune trace de A.
        for (int i = 0; i < 20; i++) {
            String outside = jdbc.queryForObject(READ_SETTING, String.class);
            assertThat(outside == null ? "" : outside).isEmpty();
            assertThat(settingInTransaction()).isEmpty();
        }
    }
}
