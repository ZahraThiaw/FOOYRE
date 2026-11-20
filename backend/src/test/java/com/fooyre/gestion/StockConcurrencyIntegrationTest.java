package com.fooyre.gestion;

import static com.fooyre.support.TenantTestSupport.asUser;
import static org.assertj.core.api.Assertions.assertThat;

import com.fooyre.IntegrationTest;
import com.fooyre.gestion.application.ProductService;
import com.fooyre.gestion.application.ProductService.ProductCommand;
import com.fooyre.gestion.application.StockService;
import com.fooyre.gestion.application.StockService.InitializeCommand;
import com.fooyre.gestion.application.StockService.MovementCommand;
import com.fooyre.gestion.domain.MeasurementUnit;
import com.fooyre.gestion.domain.StockMovementType;
import com.fooyre.shared.error.ApiException;
import com.fooyre.shared.error.ErrorCode;
import com.fooyre.support.TestAccounts;
import com.fooyre.support.TestAccounts.Account;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Opérations SIMULTANÉES sur un même produit (vrais fils d'exécution, vraies transactions,
 * verrou {@code SELECT … FOR UPDATE} sur PostgreSQL 18.7) : aucune sortie ne peut dépasser le stock,
 * aucune mise à jour n'est perdue, une même clé d'idempotence ne crée qu'un mouvement.
 */
@IntegrationTest
class StockConcurrencyIntegrationTest {

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private StockService stock;

    @Autowired
    private ProductService products;

    @Autowired
    private JdbcTemplate jdbc;

    private record Outcome(boolean success, ErrorCode error) {
    }

    private UUID productWithStock(Account owner, String name, String quantity) {
        UUID productId = asUser(owner.userId(), owner.membership().businessId(), () -> products.create(new ProductCommand(
                name, null, null, null, null, MeasurementUnit.PIECE, null, new BigDecimal("100")))).id();
        asUser(owner.userId(), owner.membership().businessId(), () -> stock.initialize(productId, UUID.randomUUID(),
                new InitializeCommand(new BigDecimal(quantity), null, null)));
        return productId;
    }

    /** Lance toutes les tâches en même temps (barrière) et collecte leurs résultats. */
    private List<Outcome> runConcurrently(Account owner, List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        asUser(owner.userId(), owner.membership().businessId(), () -> {
                            try {
                                return task.call();
                            } catch (RuntimeException e) {
                                throw e;
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                        });
                        return new Outcome(true, null);
                    } catch (ApiException refused) {
                        return new Outcome(false, refused.code());
                    }
                }));
            }
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get());
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private BigDecimal quantity(UUID productId) {
        return jdbc.queryForObject("SELECT quantity FROM product_stock WHERE product_id = ?", BigDecimal.class, productId);
    }

    @Test
    void twoSimultaneousExitsCannotExceedTheAvailableStock() throws Exception {
        Account owner = accounts.owner("concurrence-sorties");
        UUID product = productWithStock(owner, "Produit disputé", "10");
        List<Callable<Object>> exits = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            exits.add(() -> stock.record(product, UUID.randomUUID(),
                    new MovementCommand(StockMovementType.EXIT, new BigDecimal("7"), "Commande simultanée", null)));
        }
        List<Outcome> outcomes = runConcurrently(owner, exits);

        assertThat(outcomes).filteredOn(Outcome::success).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !o.success()).extracting(Outcome::error)
                .containsOnly(ErrorCode.INSUFFICIENT_STOCK);
        assertThat(quantity(product)).isEqualByComparingTo("3");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE product_id = ? AND movement_type = 'EXIT'",
                Integer.class, product)).isEqualTo(1);
    }

    @Test
    void simultaneousEntriesAreAllAppliedWithoutLostUpdate() throws Exception {
        Account owner = accounts.owner("concurrence-entrees");
        UUID product = productWithStock(owner, "Produit très demandé", "5");
        List<Callable<Object>> entries = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            entries.add(() -> stock.record(product, UUID.randomUUID(),
                    new MovementCommand(StockMovementType.ENTRY, new BigDecimal("1"), "Réception", null)));
        }
        assertThat(runConcurrently(owner, entries)).allMatch(Outcome::success);
        assertThat(quantity(product)).isEqualByComparingTo("17");
        // Chaîne cohérente : chaque mouvement part de la quantité laissée par le précédent.
        List<String> chain = jdbc.queryForList("""
                SELECT quantity_before::text || '>' || quantity_after::text FROM stock_movement
                WHERE product_id = ? ORDER BY quantity_after""", String.class, product);
        assertThat(chain).hasSize(13);
        for (int i = 1; i < chain.size(); i++) {
            assertThat(chain.get(i).split(">")[0]).isEqualTo(chain.get(i - 1).split(">")[1]);
        }
    }

    @Test
    void theSameKeySentConcurrentlyCreatesASingleMovement() throws Exception {
        Account owner = accounts.owner("concurrence-cle");
        UUID product = productWithStock(owner, "Produit rejoué", "20");
        UUID key = UUID.randomUUID();
        List<Callable<Object>> replays = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            replays.add(() -> stock.record(product, key,
                    new MovementCommand(StockMovementType.EXIT, new BigDecimal("2"), "Double clic", null)));
        }
        assertThat(runConcurrently(owner, replays)).allMatch(Outcome::success);
        assertThat(quantity(product)).isEqualByComparingTo("18");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE idempotency_key = ?", Integer.class, key))
                .isEqualTo(1);
    }

    @Test
    void simultaneousInitializationsCreateASingleStock() throws Exception {
        Account owner = accounts.owner("concurrence-init");
        UUID product = asUser(owner.userId(), owner.membership().businessId(), () -> products.create(new ProductCommand(
                "Produit à initialiser", null, null, null, null, MeasurementUnit.PIECE, null, new BigDecimal("1")))).id();
        List<Callable<Object>> inits = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            inits.add(() -> stock.initialize(product, UUID.randomUUID(),
                    new InitializeCommand(new BigDecimal("9"), null, null)));
        }
        List<Outcome> outcomes = runConcurrently(owner, inits);
        assertThat(outcomes).filteredOn(Outcome::success).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !o.success()).extracting(Outcome::error)
                .containsOnly(ErrorCode.STOCK_ALREADY_INITIALIZED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE product_id = ?", Integer.class, product))
                .isEqualTo(1);
    }
}
