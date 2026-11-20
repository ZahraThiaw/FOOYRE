package com.fooyre.gestion;

import static com.fooyre.support.TenantTestSupport.asUser;
import static com.fooyre.support.TenantTestSupport.runAsBusiness;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fooyre.business.application.MembershipService;
import com.fooyre.gestion.application.ProductService;
import com.fooyre.gestion.application.ProductService.ProductCommand;
import com.fooyre.gestion.application.StockService;
import com.fooyre.gestion.application.StockService.InitializeCommand;
import com.fooyre.gestion.application.StockService.MovementCommand;
import com.fooyre.gestion.domain.MeasurementUnit;
import com.fooyre.gestion.domain.StockMovementType;
import com.fooyre.identity.application.UserAccountService;
import com.fooyre.shared.error.ApiException;
import com.fooyre.shared.error.ErrorCode;
import com.fooyre.support.RealRolesEnvironment;
import java.math.BigDecimal;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Isolation RLS de {@code product_stock} et {@code stock_movement} (migration V4) avec le VRAI rôle
 * applicatif (ni superutilisateur, ni BYPASSRLS, ni propriétaire), pool d'une seule connexion.
 */
class StockRowLevelSecurityIntegrationTest {

    private static RealRolesEnvironment environment;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static StockService stock;
    private static UUID ownerA;
    private static UUID businessA;
    private static UUID businessB;
    private static UUID productA;
    private static UUID productB;

    @BeforeAll
    static void start() {
        environment = RealRolesEnvironment.start();
        jdbc = new JdbcTemplate(environment.bean(DataSource.class));
        transactions = new TransactionTemplate(environment.bean(PlatformTransactionManager.class));
        stock = environment.bean(StockService.class);
        ProductService products = environment.bean(ProductService.class);
        UserAccountService accounts = environment.bean(UserAccountService.class);
        MembershipService memberships = environment.bean(MembershipService.class);
        ownerA = accounts.createAccount("stock-a@test.fooyre.invalid", "Propriétaire A", "Mot-de-passe-a-1");
        UUID ownerB = accounts.createAccount("stock-b@test.fooyre.invalid", "Propriétaire B", "Mot-de-passe-b-1");
        businessA = memberships.createBusinessWithOwner("Commerce A", ownerA).businessId();
        businessB = memberships.createBusinessWithOwner("Commerce B", ownerB).businessId();
        productA = asUser(ownerA, businessA, () -> products.create(product("Sucre A"))).id();
        productB = asUser(ownerB, businessB, () -> products.create(product("Sucre B"))).id();
        asUser(ownerA, businessA, () -> stock.initialize(productA, UUID.randomUUID(), init("10")));
        asUser(ownerB, businessB, () -> stock.initialize(productB, UUID.randomUUID(), init("20")));
        asUser(ownerA, businessA, () -> stock.record(productA, UUID.randomUUID(),
                new MovementCommand(StockMovementType.EXIT, new BigDecimal("4"), "Vente au détail", null)));
    }

    @AfterAll
    static void stop() {
        environment.close();
    }

    private static ProductCommand product(String name) {
        return new ProductCommand(name, null, null, null, null, MeasurementUnit.KILOGRAM, new BigDecimal("500"),
                new BigDecimal("700"));
    }

    private static InitializeCommand init(String quantity) {
        return new InitializeCommand(new BigDecimal(quantity), null, null);
    }

    @Test
    void stockTablesAreProtectedForTheRealApplicationRole() {
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(RealRolesEnvironment.APP);
        assertThat(jdbc.queryForObject(
                "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class)).isFalse();
        assertThat(jdbc.queryForList("""
                SELECT tablename || ':' || cmd FROM pg_policies WHERE tablename IN ('product_stock', 'stock_movement')
                ORDER BY 1""", String.class)).containsExactly(
                "product_stock:INSERT", "product_stock:SELECT", "product_stock:UPDATE",
                "stock_movement:INSERT", "stock_movement:SELECT");
    }

    @Test
    void servicesWorkUnderRlsAndOnlySeeTheirOwnBusiness() {
        var page = asUser(ownerA, businessA, () -> stock.movements(null, PageRequest.of(0, 50)));
        assertThat(page.getContent()).extracting(m -> m.productId()).containsOnly(productA);
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(asUser(ownerA, businessA, () -> stock.get(productA)).quantity()).isEqualByComparingTo("6");
        assertThatThrownBy(() -> asUser(ownerA, businessA, () -> stock.get(productB))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> asUser(ownerA, businessA, () -> stock.record(productB, UUID.randomUUID(),
                new MovementCommand(StockMovementType.EXIT, BigDecimal.ONE, "Intrusion", null))))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void directSqlCannotReachOrAlterAnotherBusinessStock() {
        runAsBusiness(businessA, () -> transactions.executeWithoutResult(status -> {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM product_stock WHERE product_id = ?", Integer.class,
                    productB)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE product_id = ?", Integer.class,
                    productB)).isZero();
            assertThat(jdbc.update("UPDATE product_stock SET quantity = 0 WHERE product_id = ?", productB)).isZero();
            assertThat(jdbc.update("DELETE FROM product_stock WHERE product_id = ?", productB)).isZero();
        }));
        assertThatThrownBy(() -> runAsBusiness(businessA, () -> transactions.executeWithoutResult(status ->
                jdbc.update("""
                        INSERT INTO product_stock (id, business_id, product_id, quantity, created_at, updated_at)
                        VALUES (gen_random_uuid(), ?, ?, 1, now(), now())""", businessB, productB))))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("row-level security");
    }

    @Test
    void movementsAreInsertOnlyEvenForTheirOwnBusiness() {
        runAsBusiness(businessA, () -> transactions.executeWithoutResult(status -> {
            // Aucune politique UPDATE/DELETE : 0 ligne touchée pour le rôle applicatif.
            assertThat(jdbc.update("UPDATE stock_movement SET reason = 'falsifié' WHERE product_id = ?", productA)).isZero();
            assertThat(jdbc.update("DELETE FROM stock_movement WHERE product_id = ?", productA)).isZero();
        }));
        Integer count = asUser(ownerA, businessA, () -> transactions.execute(status ->
                jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE reason = 'falsifié'", Integer.class)));
        assertThat(count).isZero();
    }

    /**
     * Finalisation MVP-3, rôle applicatif réel et pool d'UNE connexion : le refus audité est validé
     * dans la même transaction (aucune seconde connexion, donc aucun blocage) ; le coût unitaire
     * d'une entrée est enregistré sous RLS. Entreprise B (les comptes de A restent inchangés).
     */
    @Test
    void refusedExitAuditAndEntryUnitCostWorkUnderRlsWithASingleConnection() {
        assertThatThrownBy(() -> asUser(ownerB(), businessB, () -> stock.record(productB, UUID.randomUUID(),
                new MovementCommand(StockMovementType.EXIT, new BigDecimal("999"), "Trop", null))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK));
        var entry = asUser(ownerB(), businessB, () -> stock.record(productB, UUID.randomUUID(),
                new MovementCommand(StockMovementType.ENTRY, new BigDecimal("2"), "Réception", null,
                        new BigDecimal("450"))));
        assertThat(entry.movement().unitCost()).isEqualByComparingTo("450");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_log WHERE business_id = ? AND action = 'STOCK_EXIT_REJECTED_INSUFFICIENT'
                AND outcome = 'DENIED' AND target_id = ?""", Integer.class, businessB, productB)).isEqualTo(1);
        BigDecimal quantityB = asUser(ownerB(), businessB, () -> stock.get(productB)).quantity();
        assertThat(quantityB).isEqualByComparingTo("22");
    }

    private static UUID ownerB() {
        return jdbc.queryForObject("SELECT user_id FROM business_membership WHERE business_id = ?", UUID.class,
                businessB);
    }

    @Test
    void withoutContextNothingIsVisibleAndTheSinglePooledConnectionDoesNotLeak() {
        for (int i = 0; i < 10; i++) {
            Integer visibleForA = asUser(ownerA, businessA, () -> transactions.execute(status ->
                    jdbc.queryForObject("SELECT count(*) FROM stock_movement", Integer.class)));
            assertThat(visibleForA).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM product_stock", Integer.class)).isZero();
        }
    }
}
