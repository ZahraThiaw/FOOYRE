package com.fooyre.gestion;

import static com.fooyre.support.TenantTestSupport.asUser;
import static com.fooyre.support.TenantTestSupport.runAsBusiness;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fooyre.business.application.MembershipService;
import com.fooyre.gestion.application.CustomerService;
import com.fooyre.gestion.application.CustomerService.CustomerCommand;
import com.fooyre.identity.application.UserAccountService;
import com.fooyre.shared.error.ApiException;
import com.fooyre.support.RealRolesEnvironment;
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
 * Isolation RLS de {@code customer} (migration V6) avec le VRAI rôle applicatif (ni
 * superutilisateur, ni BYPASSRLS, ni propriétaire), pool d'une seule connexion.
 */
class CustomerRowLevelSecurityIntegrationTest {

    private static RealRolesEnvironment environment;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static CustomerService customers;
    private static UUID ownerA;
    private static UUID ownerB;
    private static UUID businessA;
    private static UUID businessB;
    private static UUID customerA;
    private static UUID customerB;

    @BeforeAll
    static void start() {
        environment = RealRolesEnvironment.start();
        jdbc = new JdbcTemplate(environment.bean(DataSource.class));
        transactions = new TransactionTemplate(environment.bean(PlatformTransactionManager.class));
        customers = environment.bean(CustomerService.class);
        UserAccountService accounts = environment.bean(UserAccountService.class);
        MembershipService memberships = environment.bean(MembershipService.class);
        ownerA = accounts.createAccount("client-rls-a@test.fooyre.invalid", "Propriétaire A", "Mot-de-passe-a-1");
        ownerB = accounts.createAccount("client-rls-b@test.fooyre.invalid", "Propriétaire B", "Mot-de-passe-b-1");
        businessA = memberships.createBusinessWithOwner("Commerce A", ownerA).businessId();
        businessB = memberships.createBusinessWithOwner("Commerce B", ownerB).businessId();
        customerA = asUser(ownerA, businessA, () -> customers.create(
                new CustomerCommand("Cliente A", "77 100 00 01", null, null, null), null)).customer().id();
        customerB = asUser(ownerB, businessB, () -> customers.create(
                new CustomerCommand("Cliente B", "77 100 00 02", null, null, null), null)).customer().id();
    }

    @AfterAll
    static void stop() {
        environment.close();
    }

    @Test
    void customerTableIsProtectedForTheRealApplicationRole() {
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(RealRolesEnvironment.APP);
        assertThat(jdbc.queryForObject(
                "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT relrowsecurity FROM pg_class WHERE relname = 'customer'", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForList("SELECT cmd FROM pg_policies WHERE tablename = 'customer' ORDER BY 1", String.class))
                .containsExactly("INSERT", "SELECT", "UPDATE");
    }

    @Test
    void servicesWorkUnderRlsAndOnlySeeTheirOwnBusiness() {
        var page = asUser(ownerA, businessA, () -> customers.search(null, null, PageRequest.of(0, 50)));
        assertThat(page.getContent()).extracting(c -> c.id()).containsExactly(customerA);
        // Recherche par le numéro de B depuis A : rien.
        assertThat(asUser(ownerA, businessA, () -> customers.search("77 100 00 02", null, PageRequest.of(0, 50)))
                .getTotalElements()).isZero();
        assertThatThrownBy(() -> asUser(ownerA, businessA, () -> customers.get(customerB)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> asUser(ownerA, businessA, () -> {
            customers.archive(customerB);
            return null;
        })).isInstanceOf(ApiException.class);
        assertThat(asUser(ownerB, businessB, () -> customers.get(customerB)).status().name()).isEqualTo("ACTIVE");
    }

    @Test
    void directSqlCannotReachAlterOrDeleteAnotherBusinessCustomers() {
        runAsBusiness(businessA, () -> transactions.executeWithoutResult(status -> {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM customer WHERE id = ?", Integer.class, customerB))
                    .isZero();
            assertThat(jdbc.update("UPDATE customer SET name = 'piratée' WHERE id = ?", customerB)).isZero();
            assertThat(jdbc.update("DELETE FROM customer WHERE id = ?", customerB)).isZero();
            // Aucune politique DELETE : même ses propres clients ne peuvent être supprimés physiquement.
            assertThat(jdbc.update("DELETE FROM customer WHERE id = ?", customerA)).isZero();
            // Déplacer un client vers une autre entreprise : refusé (WITH CHECK).
            assertThatThrownBy(() -> jdbc.update("UPDATE customer SET business_id = ? WHERE id = ?", businessB,
                    customerA)).isInstanceOf(DataAccessException.class);
        }));
        assertThatThrownBy(() -> runAsBusiness(businessA, () -> transactions.executeWithoutResult(status ->
                jdbc.update("""
                        INSERT INTO customer (id, business_id, name, created_at, updated_at)
                        VALUES (gen_random_uuid(), ?, 'Intruse', now(), now())""", businessB))))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("row-level security");
        assertThat(asUser(ownerB, businessB, () -> customers.get(customerB)).name()).isEqualTo("Cliente B");
        assertThat(asUser(ownerA, businessA, () -> customers.get(customerA)).name()).isEqualTo("Cliente A");
    }

    @Test
    void withoutContextNothingIsVisibleAndTheSinglePooledConnectionDoesNotLeak() {
        for (int i = 0; i < 10; i++) {
            Integer visibleForA = asUser(ownerA, businessA, () -> transactions.execute(status ->
                    jdbc.queryForObject("SELECT count(*) FROM customer", Integer.class)));
            assertThat(visibleForA).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM customer", Integer.class)).isZero();
        }
    }
}
