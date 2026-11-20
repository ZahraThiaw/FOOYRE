package com.fooyre.gestion;

import static com.fooyre.support.TenantTestSupport.asBusiness;
import static com.fooyre.support.TenantTestSupport.asUser;
import static com.fooyre.support.TenantTestSupport.runAsBusiness;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fooyre.business.application.MembershipService;
import com.fooyre.gestion.application.CategoryService;
import com.fooyre.gestion.application.CategoryService.CategoryCommand;
import com.fooyre.gestion.application.CategoryView;
import com.fooyre.gestion.application.ProductService;
import com.fooyre.gestion.application.ProductService.ProductCommand;
import com.fooyre.gestion.application.ProductView;
import com.fooyre.gestion.domain.MeasurementUnit;
import com.fooyre.gestion.domain.ProductSearch;
import com.fooyre.identity.application.UserAccountService;
import com.fooyre.shared.error.ApiException;
import com.fooyre.support.RealRolesEnvironment;
import java.math.BigDecimal;
import java.util.List;
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
 * Isolation RLS des tables {@code category} et {@code product} (migration V3) avec le VRAI rôle
 * applicatif : services du catalogue exécutés pour deux entreprises, puis accès SQL directs qui
 * tentent de contourner l'application (lecture, insertion, modification, suppression croisées,
 * association à la catégorie d'une autre entreprise, absence de contexte, réutilisation de la
 * connexion unique du pool).
 */
class CatalogRowLevelSecurityIntegrationTest {

    private static RealRolesEnvironment environment;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static CategoryService categories;
    private static ProductService products;
    private static UUID businessA;
    private static UUID businessB;
    private static UUID categoryA;
    private static UUID categoryB;
    private static UUID productA;
    private static UUID productB;

    @BeforeAll
    static void start() {
        environment = RealRolesEnvironment.start();
        jdbc = new JdbcTemplate(environment.bean(DataSource.class));
        transactions = new TransactionTemplate(environment.bean(PlatformTransactionManager.class));
        categories = environment.bean(CategoryService.class);
        products = environment.bean(ProductService.class);
        UserAccountService accounts = environment.bean(UserAccountService.class);
        MembershipService memberships = environment.bean(MembershipService.class);
        UUID ownerA = accounts.createAccount("catalogue-a@test.fooyre.invalid", "Propriétaire A", "Mot-de-passe-a-1");
        UUID ownerB = accounts.createAccount("catalogue-b@test.fooyre.invalid", "Propriétaire B", "Mot-de-passe-b-1");
        businessA = memberships.createBusinessWithOwner("Commerce A", ownerA).businessId();
        businessB = memberships.createBusinessWithOwner("Commerce B", ownerB).businessId();

        categoryA = asUser(ownerA, businessA, () -> categories.create(new CategoryCommand("Épicerie", null))).id();
        categoryB = asUser(ownerB, businessB, () -> categories.create(new CategoryCommand("Épicerie", null))).id();
        productA = asUser(ownerA, businessA, () -> products.create(command("Sucre A", categoryA, "SKU-1"))).id();
        productB = asUser(ownerB, businessB, () -> products.create(command("Sucre B", categoryB, "SKU-1"))).id();
    }

    @AfterAll
    static void stop() {
        environment.close();
    }

    private static ProductCommand command(String name, UUID category, String sku) {
        return new ProductCommand(name, null, sku, null, category, MeasurementUnit.KILOGRAM, new BigDecimal("600"),
                new BigDecimal("750"));
    }

    private static Integer countAs(UUID business, String table) {
        return asBusiness(business, () -> transactions.execute(status ->
                jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)));
    }

    @Test
    void theApplicationRoleIsSubjectToRowLevelSecurityOnCatalogTables() {
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(RealRolesEnvironment.APP);
        assertThat(jdbc.queryForObject(
                "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class)).isFalse();
        assertThat(jdbc.queryForList("""
                SELECT relname FROM pg_class WHERE relname IN ('category', 'product') AND relrowsecurity
                ORDER BY relname""", String.class)).containsExactly("category", "product");
        assertThat(jdbc.queryForList("""
                SELECT tablename || ':' || cmd FROM pg_policies WHERE tablename IN ('category', 'product')
                ORDER BY 1""", String.class)).containsExactly(
                "category:INSERT", "category:SELECT", "category:UPDATE",
                "product:INSERT", "product:SELECT", "product:UPDATE");
    }

    @Test
    void servicesOnlySeeTheirOwnBusinessData() {
        List<ProductView> seenByA = asBusiness(businessA, () -> products.search(
                new ProductSearch(null, null, null), PageRequest.of(0, 50)).getContent());
        assertThat(seenByA).extracting(ProductView::id).containsExactly(productA);
        List<CategoryView> categoriesOfB = asBusiness(businessB, () -> categories.list(null, PageRequest.of(0, 50)).getContent());
        assertThat(categoriesOfB).extracting(CategoryView::id).containsExactly(categoryB);
        assertThatThrownBy(() -> asBusiness(businessA, () -> products.get(productB))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> asBusiness(businessA, () -> products.create(command("Intrus", categoryB, null))))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void sqlCannotReadUpdateOrDeleteAnotherBusinessRows() {
        runAsBusiness(businessA, () -> transactions.executeWithoutResult(status -> {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM product WHERE id = ?", Integer.class, productB)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM category WHERE id = ?", Integer.class, categoryB)).isZero();
            assertThat(jdbc.update("UPDATE product SET sale_price = 1 WHERE id = ?", productB)).isZero();
            assertThat(jdbc.update("UPDATE category SET name = 'Piraté' WHERE id = ?", categoryB)).isZero();
            assertThat(jdbc.update("DELETE FROM product WHERE id = ?", productB)).isZero();
            assertThat(jdbc.update("DELETE FROM category WHERE id = ?", categoryB)).isZero();
        }));
        String nameOfB = asBusiness(businessB, () -> transactions.execute(status ->
                jdbc.queryForObject("SELECT name FROM product WHERE id = ?", String.class, productB)));
        assertThat(nameOfB).isEqualTo("Sucre B");
    }

    @Test
    void noPhysicalDeletionEvenOfOwnRows() {
        runAsBusiness(businessA, () -> transactions.executeWithoutResult(status ->
                assertThat(jdbc.update("DELETE FROM product WHERE id = ?", productA)).isZero()));
        assertThat(countAs(businessA, "product")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void insertingForAnotherBusinessOrLinkingItsCategoryIsRefused() {
        assertThatThrownBy(() -> runAsBusiness(businessA, () -> transactions.executeWithoutResult(status ->
                jdbc.update("""
                        INSERT INTO category (id, business_id, name, status, created_at, updated_at)
                        VALUES (?, ?, 'Intruse', 'ACTIVE', now(), now())""", UUID.randomUUID(), businessB))))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("row-level security");
        // Produit de A rattaché à la catégorie de B : refusé par la FK composite (business_id, category_id).
        assertThatThrownBy(() -> runAsBusiness(businessA, () -> transactions.executeWithoutResult(status ->
                jdbc.update("""
                        INSERT INTO product (id, business_id, category_id, name, unit, sale_price, status, created_at, updated_at)
                        VALUES (?, ?, ?, 'Intrus', 'PIECE', 1, 'ACTIVE', now(), now())""",
                        UUID.randomUUID(), businessA, categoryB))))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("fk_product_category");
        // Déplacer une ligne de A vers B par UPDATE : refusé (WITH CHECK).
        assertThatThrownBy(() -> runAsBusiness(businessA, () -> transactions.executeWithoutResult(status ->
                jdbc.update("UPDATE category SET business_id = ? WHERE id = ?", businessB, categoryA))))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void withoutContextNothingIsVisibleAndTheSinglePooledConnectionDoesNotLeak() {
        for (int i = 0; i < 10; i++) {
            assertThat(countAs(businessA, "product")).isGreaterThanOrEqualTo(1);
            // Même et unique connexion, juste après : plus aucun contexte.
            assertThat(jdbc.queryForObject("SELECT count(*) FROM product", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM category", Integer.class)).isZero();
            Integer inTransactionWithoutContext = transactions.execute(status ->
                    jdbc.queryForObject("SELECT count(*) FROM product", Integer.class));
            assertThat(inTransactionWithoutContext).isZero();
        }
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO category (id, business_id, name, status, created_at, updated_at)
                VALUES (?, ?, 'Sans contexte', 'ACTIVE', now(), now())""", UUID.randomUUID(), businessA))
                .isInstanceOf(DataAccessException.class);
    }
}
