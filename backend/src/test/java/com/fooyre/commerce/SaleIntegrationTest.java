package com.fooyre.commerce;

import static com.fooyre.gestion.CatalogTestClient.fields;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fooyre.IntegrationTest;
import com.fooyre.gestion.CatalogTestClient;
import com.fooyre.shared.security.BusinessRole;
import com.fooyre.support.TestAccounts;
import com.fooyre.support.TestAccounts.Account;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Ventes par l'API réelle : brouillon, calcul serveur, confirmation atomique (stock, numéro, CMP
 * figé), idempotence et concurrence, refus sans sortie partielle, limite de remise, isolation,
 * protection des coûts, tableau de bord.
 */
@IntegrationTest
class SaleIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private JdbcTemplate jdbc;

    final class Client {
        final String bearer;
        final CatalogTestClient catalog;

        Client(Account account) throws Exception {
            this.bearer = TestAccounts.login(mvc, account.email()).bearer();
            this.catalog = new CatalogTestClient(mvc, bearer);
        }

        ResultActions get(String path) throws Exception {
            return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                    .header("Authorization", bearer));
        }

        ResultActions post(String path, Map<String, Object> body, String key) throws Exception {
            var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                    .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                    .content(CatalogTestClient.json(body));
            if (key != null) {
                request.header("Idempotency-Key", key);
            }
            return mvc.perform(request);
        }

        String product(String name, String price, String initialStock, String entryQty, String unitCost) throws Exception {
            String id = catalog.createProduct(fields("name", name, "unit", "PIECE", "salePrice", price, "purchasePrice", "1"));
            // Initialisation strictement positive (règle MVP-3) ; pour un CMP CONNU : stock ramené à 0 puis entrée valorisée.
            post("/api/v1/stock/" + id + "/initialize", fields("quantity", entryQty != null ? "1" : initialStock),
                    UUID.randomUUID().toString()).andExpect(status().isCreated());
            if (entryQty != null) {
                post("/api/v1/stock/" + id + "/exits", fields("quantity", "1", "reason", "Remise à zéro de test"),
                        UUID.randomUUID().toString()).andExpect(status().isCreated());
                post("/api/v1/stock/" + id + "/entries", fields("quantity", entryQty, "reason", "Réception",
                        "unitCost", unitCost), UUID.randomUUID().toString()).andExpect(status().isCreated());
            }
            return id;
        }

        String draft(Map<String, Object> body) throws Exception {
            var result = post("/api/v1/sales", body, UUID.randomUUID().toString()).andReturn().getResponse();
            assertThat(result.getStatus()).as(result.getContentAsString()).isEqualTo(201);
            return com.jayway.jsonpath.JsonPath.read(result.getContentAsString(), "$.id");
        }

        ResultActions confirm(String saleId, String key) throws Exception {
            return post("/api/v1/sales/" + saleId + "/confirm", Map.of(), key);
        }
    }

    private static Map<String, Object> sale(Object customerId, Object globalDiscount, Object... lines) {
        Map<String, Object> body = new java.util.HashMap<>();
        if (customerId != null) {
            body.put("customerId", customerId);
        }
        if (globalDiscount != null) {
            body.put("globalDiscount", globalDiscount);
        }
        body.put("lines", List.of(lines));
        return body;
    }

    private static Map<String, Object> line(String productId, String quantity, String discount) {
        return discount == null ? fields("productId", productId, "quantity", quantity)
                : fields("productId", productId, "quantity", quantity, "discount", discount);
    }

    private String quantity(String productId) {
        return jdbc.queryForObject("SELECT quantity::text FROM product_stock WHERE product_id = ?::uuid", String.class,
                productId);
    }

    @Test
    void fullSaleFlowWithCustomerTwoProductsServerAmountsStockExitAndFrozenCost() throws Exception {
        Client owner = new Client(accounts.owner("vente-parcours"));
        // Produit A : stock initial 0 puis entrée de 10 à 600 → CMP connu (600).
        String a = owner.product("Savon vente", "1000", "0", "10", "600");
        // Produit B : stock initial 5 SANS coût → CMP inconnu.
        String b = owner.product("Huile vente", "1500", "5", null, null);
        String customer = CatalogTestClient.read(owner.post("/api/v1/customers", fields("name", "Awa Vente"), null)
                .andExpect(status().isCreated()), "$.id");

        owner.post("/api/v1/sales/quote", sale(customer, "100", line(a, "2", "0"), line(b, "1", null)), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grossAmount").value(3500))
                .andExpect(jsonPath("$.totalInclTax").value(3400))
                .andExpect(jsonPath("$.taxAmount").value(0));

        String saleId = owner.draft(sale(customer, "100", line(a, "2", "0"), line(b, "1", null)));
        owner.get("/api/v1/sales/" + saleId).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.reference").doesNotExist())
                .andExpect(jsonPath("$.quote.totalInclTax").value(3400));
        assertThat(quantity(a)).isEqualTo("10.000"); // un brouillon ne touche pas au stock

        String key = UUID.randomUUID().toString();
        owner.confirm(saleId, key).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.reference").value("V-000001"))
                .andExpect(jsonPath("$.customerName").value("Awa Vente"))
                .andExpect(jsonPath("$.paymentStatus").value("UNPAID"))
                .andExpect(jsonPath("$.quote.totalInclTax").value(3400))
                .andExpect(jsonPath("$.quote.lines[0].unitCost").value(600))
                .andExpect(jsonPath("$.quote.lines[1].unitCost").doesNotExist())
                // Coût de B inconnu : aucune marge présentée.
                .andExpect(jsonPath("$.costsComplete").value(false))
                .andExpect(jsonPath("$.grossMargin").doesNotExist());
        assertThat(quantity(a)).isEqualTo("8.000");
        assertThat(quantity(b)).isEqualTo("4.000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE movement_type = 'SALE_OUT' AND reference = 'V-000001'"
                + " AND product_id IN (?::uuid, ?::uuid)", Integer.class, a, b)).isEqualTo(2);
        // Rejeu de la confirmation : rien n'est refait.
        owner.confirm(saleId, key).andExpect(status().isOk()).andExpect(header().string("Idempotent-Replay", "true"));
        owner.confirm(saleId, UUID.randomUUID().toString()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SALE_ALREADY_CONFIRMED"));
        assertThat(quantity(a)).isEqualTo("8.000");

        // Une nouvelle entrée ne réécrit pas la vente passée (coût figé).
        owner.post("/api/v1/stock/" + a + "/entries", fields("quantity", "2", "reason", "Réception", "unitCost", "900"),
                UUID.randomUUID().toString()).andExpect(status().isCreated());
        owner.get("/api/v1/sales/" + saleId).andExpect(jsonPath("$.quote.lines[0].unitCost").value(600));
        owner.get("/api/v1/stock/" + a).andExpect(jsonPath("$.averageCost").value(660)); // (8×600 + 2×900) / 10 = 660

        // Historique : liste, historique client, une vente confirmée n'est plus modifiable.
        owner.get("/api/v1/sales?status=CONFIRMED").andExpect(jsonPath("$.content[0].reference").value("V-000001"));
        owner.get("/api/v1/sales?customerId=" + customer).andExpect(jsonPath("$.page.totalElements").value(1));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/sales/" + saleId)
                        .header("Authorization", owner.bearer).contentType(MediaType.APPLICATION_JSON)
                        .content(CatalogTestClient.json(Map.of("lines", List.of(line(a, "1", null)), "version", 0))))
                .andExpect(status().isConflict());
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.update("UPDATE sale_line SET quantity = 99 WHERE sale_id = ?::uuid", saleId))
                .rootCause().hasMessageContaining("vente confirmée");

        // Tableau de bord : chiffre d'affaires réel, encaissements nuls, aucune marge non fiable.
        owner.get("/api/v1/dashboard").andExpect(status().isOk())
                .andExpect(jsonPath("$.today.revenueInclTax").value(3400))
                .andExpect(jsonPath("$.today.collections").value(0))
                .andExpect(jsonPath("$.today.marginReliable").value(false))
                .andExpect(jsonPath("$.salesCountToday").value(1))
                .andExpect(jsonPath("$.paymentsConnected").value(false));
    }

    @Test
    void insufficientStockRefusesTheWholeSaleWithoutAnyPartialExit() throws Exception {
        Client owner = new Client(accounts.owner("vente-refus"));
        String a = owner.product("Riz vente", "500", "10", null, null);
        String b = owner.product("Sucre vente", "700", "1", null, null);
        String saleId = owner.draft(sale(null, null, line(a, "3", null), line(b, "2", null)));
        owner.confirm(saleId, UUID.randomUUID().toString()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        assertThat(quantity(a)).isEqualTo("10.000");
        assertThat(quantity(b)).isEqualTo("1.000");
        owner.get("/api/v1/sales/" + saleId).andExpect(jsonPath("$.status").value("DRAFT"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM business_sequence bs JOIN sale s ON s.business_id = bs.business_id"
                + " WHERE s.id = ?::uuid", Integer.class, saleId)).isZero(); // aucun numéro consommé
    }

    @Test
    void concurrentConfirmationsNeverExitStockTwiceAndNumbersAreUnique() throws Exception {
        Client owner = new Client(accounts.owner("vente-concurrence"));
        String a = owner.product("Thé vente", "100", "100", null, null);
        String sale1 = owner.draft(sale(null, null, line(a, "1", null)));
        String sale2 = owner.draft(sale(null, null, line(a, "1", null)));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (String id : List.of(sale1, sale1, sale2, sale2)) {
            tasks.add(() -> {
                start.await();
                return owner.confirm(id, UUID.randomUUID().toString()).andReturn().getResponse().getStatus();
            });
        }
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> task : tasks) {
            futures.add(pool.submit(task));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get());
        }
        pool.shutdown();
        assertThat(statuses).containsExactlyInAnyOrder(200, 409, 200, 409);
        assertThat(quantity(a)).isEqualTo("98.000");
        assertThat(jdbc.queryForList("SELECT number FROM sale WHERE id IN (?::uuid, ?::uuid) ORDER BY number", Long.class,
                sale1, sale2)).containsExactly(1L, 2L);
    }

    @Test
    void employeeDiscountLimitIsEnforcedAndAuditedAndCostsAreHidden() throws Exception {
        Account ownerAccount = accounts.owner("vente-remise");
        Client owner = new Client(ownerAccount);
        Client employee = new Client(accounts.member(ownerAccount, BusinessRole.EMPLOYEE, "vente-employe"));
        String a = owner.product("Pagne vente", "1000", "0", "10", "400");

        // Limite par défaut 0 % : toute remise de l'employée est refusée et journalisée.
        employee.post("/api/v1/sales", sale(null, "50", line(a, "1", null)), UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("DISCOUNT_LIMIT_EXCEEDED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE business_id = ? AND action = 'DISCOUNT_LIMIT_EXCEEDED'",
                Integer.class, ownerAccount.membership().businessId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sale WHERE business_id = ?", Integer.class,
                ownerAccount.membership().businessId())).isZero();

        // Limite portée à 10 % par la propriétaire : 100 sur 1000 accepté, 101 refusé.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/business/sales-policy")
                        .header("Authorization", owner.bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeMaxDiscountRate\":\"10\"}"))
                .andExpect(status().isOk());
        employee.post("/api/v1/sales", sale(null, "101", line(a, "1", null)), UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableContent());
        String saleId = employee.draft(sale(null, "100", line(a, "1", null)));
        employee.confirm(saleId, UUID.randomUUID().toString()).andExpect(status().isOk())
                .andExpect(jsonPath("$.quote.totalInclTax").value(900))
                .andExpect(jsonPath("$.quote.lines[0].unitCost").doesNotExist())
                .andExpect(jsonPath("$.grossMargin").doesNotExist());
        // Propriétaire : marge brute fiable (coût connu) = 900 − 400.
        owner.get("/api/v1/sales/" + saleId).andExpect(jsonPath("$.grossMargin").value(500))
                .andExpect(jsonPath("$.costsComplete").value(true));
        // Employée : tableau de bord sans montants.
        employee.get("/api/v1/dashboard").andExpect(status().isOk())
                .andExpect(jsonPath("$.today").doesNotExist())
                .andExpect(jsonPath("$.financialsVisible").value(false));
        employee.get("/api/v1/stock/" + a).andExpect(jsonPath("$.averageCost").doesNotExist());
    }

    @Test
    void anotherBusinessCannotSeeConfirmOrUseForeignProductsAndCustomers() throws Exception {
        Client alpha = new Client(accounts.owner("vente-alpha"));
        Client beta = new Client(accounts.owner("vente-beta"));
        String productAlpha = alpha.product("Produit alpha", "100", "5", null, null);
        String saleAlpha = alpha.draft(sale(null, null, line(productAlpha, "1", null)));
        String customerAlpha = CatalogTestClient.read(alpha.post("/api/v1/customers", fields("name", "Cliente alpha"), null)
                .andExpect(status().isCreated()), "$.id");
        String productBeta = beta.product("Produit beta", "100", "5", null, null);

        beta.get("/api/v1/sales/" + saleAlpha).andExpect(status().isNotFound());
        beta.confirm(saleAlpha, UUID.randomUUID().toString()).andExpect(status().isNotFound());
        beta.post("/api/v1/sales", sale(null, null, line(productAlpha, "1", null)), UUID.randomUUID().toString())
                .andExpect(status().isNotFound());
        beta.post("/api/v1/sales", sale(customerAlpha, null, line(productBeta, "1", null)), UUID.randomUUID().toString())
                .andExpect(status().isNotFound());
        beta.get("/api/v1/sales").andExpect(jsonPath("$.page.totalElements").value(0));
        assertThat(quantity(productAlpha)).isEqualTo("5.000");
        mvc.perform(post("/api/v1/sales").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
