package com.fooyre.gestion;

import static com.fooyre.gestion.CatalogTestClient.fields;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fooyre.IntegrationTest;
import com.fooyre.shared.security.BusinessRole;
import com.fooyre.support.TestAccounts;
import com.fooyre.support.TestAccounts.Account;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Stock (MVP-3) par l'API réelle : initialisation, entrées, sorties, ajustements, stock négatif,
 * précision selon l'unité, motifs, historique immuable, idempotence, alertes, permissions,
 * isolation entre entreprises, audit, produits archivés.
 */
@IntegrationTest
class StockIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private JdbcTemplate jdbc;

    /** Client authentifié ; chaque opération porte sa propre clé d'idempotence sauf si fournie. */
    private final class Client {
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

        ResultActions operate(String productId, String operation, Map<String, Object> body, String key) throws Exception {
            var request = post("/api/v1/stock/" + productId + "/" + operation).header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON).content(CatalogTestClient.json(body));
            if (key != null) {
                request.header("Idempotency-Key", key);
            }
            return mvc.perform(request);
        }

        ResultActions operate(String productId, String operation, Map<String, Object> body) throws Exception {
            return operate(productId, operation, body, UUID.randomUUID().toString());
        }

        ResultActions put(String path, Map<String, Object> body) throws Exception {
            return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                    .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                    .content(CatalogTestClient.json(body)));
        }

        String product(String name, String unit) throws Exception {
            return catalog.createProduct(fields("name", name, "unit", unit, "salePrice", "1000", "purchasePrice", "600"));
        }

        String quantity(String productId) throws Exception {
            return CatalogTestClient.read(get("/api/v1/stock/" + productId), "$.quantity");
        }
    }

    private Client ownerClient(String label) throws Exception {
        return new Client(accounts.owner(label));
    }

    @Test
    void initializeEnterExitAdjustAndKeepAnImmutableHistory() throws Exception {
        Client owner = ownerClient("stock-parcours");
        String product = owner.product("Savon liquide 500 mL", "PIECE");

        owner.get("/api/v1/stock/" + product)
                .andExpect(jsonPath("$.initialized").value(false))
                .andExpect(jsonPath("$.status").value("NOT_INITIALIZED"));
        owner.operate(product, "initialize", fields("quantity", "20", "note", "Inventaire de départ"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.movement.type").value("INITIAL"))
                .andExpect(jsonPath("$.movement.quantityBefore").value("0"))
                .andExpect(jsonPath("$.movement.quantityAfter").value("20"))
                .andExpect(jsonPath("$.movement.author.fullName").value("Propriétaire stock-parcours"))
                .andExpect(jsonPath("$.stock.quantity").value("20"))
                .andExpect(jsonPath("$.stock.minimumQuantity").value("0"));
        owner.operate(product, "initialize", fields("quantity", "5"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_ALREADY_INITIALIZED"));

        owner.operate(product, "entries", fields("quantity", "10", "reason", "Réception fournisseur", "reference", "BL-001"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.stock.quantity").value("30"));
        owner.operate(product, "exits", fields("quantity", "5", "reason", "Usage interne"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.stock.quantity").value("25"));
        owner.operate(product, "adjustments", fields("direction", "OUT", "quantity", "3", "reason", "Casse"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.movement.type").value("ADJUSTMENT_OUT"))
                .andExpect(jsonPath("$.stock.quantity").value("22"));
        owner.operate(product, "adjustments", fields("direction", "IN", "quantity", "1", "reason", "Erreur de comptage"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.stock.quantity").value("23"));

        owner.get("/api/v1/stock/" + product + "/movements")
                .andExpect(jsonPath("$.page.totalElements").value(5))
                .andExpect(jsonPath("$.content[*].type",
                        contains("ADJUSTMENT_IN", "ADJUSTMENT_OUT", "EXIT", "ENTRY", "INITIAL")))
                .andExpect(jsonPath("$.content[3].reference").value("BL-001"))
                .andExpect(jsonPath("$.content[0].quantityBefore").value("22"))
                .andExpect(jsonPath("$.content[0].quantityAfter").value("23"));

        // Historique immuable : aucune route de modification, base refusant UPDATE/DELETE.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/stock/" + product + "/movements").header("Authorization", owner.bearer))
                .andExpect(status().isMethodNotAllowed());
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        jdbc.update("UPDATE stock_movement SET quantity = 999 WHERE product_id = ?::uuid", product))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("insertion seule");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        jdbc.update("DELETE FROM stock_movement WHERE product_id = ?::uuid", product))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void exitBeyondStockIsRefusedUnlessNegativeStockIsAllowedByTheOwner() throws Exception {
        Client owner = ownerClient("stock-negatif");
        String product = owner.product("Farine 1 kg", "PACK");
        owner.operate(product, "initialize", fields("quantity", "3")).andExpect(status().isCreated());

        owner.operate(product, "exits", fields("quantity", "4", "reason", "Vente au comptoir"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        owner.operate(product, "adjustments", fields("direction", "OUT", "quantity", "4", "reason", "Perte"))
                .andExpect(status().isConflict());
        assertThat(owner.quantity(product)).isEqualTo("3");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE product_id = ?::uuid", Integer.class,
                product)).isEqualTo(1);

        owner.get("/api/v1/business/settings").andExpect(jsonPath("$.allowNegativeStock").value(false));
        owner.put("/api/v1/business/settings", fields("allowNegativeStock", true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allowNegativeStock").value(true));
        owner.operate(product, "exits", fields("quantity", "4", "reason", "Vente avant réception"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stock.quantity").value("-1"))
                .andExpect(jsonPath("$.stock.status").value("OUT_OF_STOCK"));
        // Désactivation : relue au moment de l'opération ; une entrée reste toujours possible.
        owner.put("/api/v1/business/settings", fields("allowNegativeStock", false)).andExpect(status().isOk());
        owner.operate(product, "exits", fields("quantity", "1", "reason", "Essai"))
                .andExpect(status().isConflict());
        owner.operate(product, "entries", fields("quantity", "2", "reason", "Réception"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.stock.quantity").value("1"));
    }

    @Test
    void quantitiesFollowTheUnitPrecisionAndAreNeverRounded() throws Exception {
        Client owner = ownerClient("stock-precision");
        String pieces = owner.product("Bougie parfumée", "PIECE");
        String kilos = owner.product("Riz brisé", "KILOGRAM");
        String litres = owner.product("Huile d'arachide", "LITRE");

        owner.operate(pieces, "initialize", fields("quantity", "2.5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("quantity"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_VALUE"));
        owner.operate(pieces, "initialize", fields("quantity", "2.000")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.stock.quantity").value("2"));

        owner.operate(kilos, "initialize", fields("quantity", "12.5")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.stock.quantity").value("12.5"));
        owner.operate(kilos, "exits", fields("quantity", "0.375", "reason", "Échantillons"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.stock.quantity").value("12.125"));
        owner.operate(kilos, "exits", fields("quantity", "0.0001", "reason", "Trop précis"))
                .andExpect(status().isBadRequest());
        owner.operate(litres, "initialize", fields("quantity", "1.2345")).andExpect(status().isBadRequest());

        for (String invalid : List.of("0", "-1", "0.000")) {
            owner.operate(kilos, "entries", fields("quantity", invalid, "reason", "Réception"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].code").value("INVALID_VALUE"));
        }
        owner.operate(kilos, "entries", fields("quantity", "abc", "reason", "x")).andExpect(status().isBadRequest());
        owner.operate(kilos, "entries", fields("quantity", "1" + "0".repeat(16), "reason", "x"))
                .andExpect(status().isBadRequest());
        owner.put("/api/v1/stock/" + kilos + "/minimum-quantity", fields("minimumQuantity", "-1"))
                .andExpect(status().isBadRequest());
        owner.put("/api/v1/stock/" + pieces + "/minimum-quantity", fields("minimumQuantity", "1.5"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reasonIsRequiredForEntriesExitsAndAdjustments() throws Exception {
        Client owner = ownerClient("stock-motif");
        String product = owner.product("Lait en poudre", "PACK");
        owner.operate(product, "initialize", fields("quantity", "10")).andExpect(status().isCreated());
        for (String operation : List.of("entries", "exits")) {
            owner.operate(product, operation, fields("quantity", "1", "reason", "   "))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("reason"))
                    .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        }
        owner.operate(product, "adjustments", fields("direction", "IN", "quantity", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
        assertThat(owner.quantity(product)).isEqualTo("10");
    }

    @Test
    void operationsBeforeInitializationAndOnArchivedProductsAreRefused() throws Exception {
        Client owner = ownerClient("stock-etats");
        String product = owner.product("Thé à la menthe", "PACK");
        owner.operate(product, "entries", fields("quantity", "1", "reason", "Réception"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("STOCK_NOT_INITIALIZED"));
        owner.put("/api/v1/stock/" + product + "/minimum-quantity", fields("minimumQuantity", "2"))
                .andExpect(status().isUnprocessableContent());

        owner.operate(product, "initialize", fields("quantity", "4")).andExpect(status().isCreated());
        owner.catalog.delete("/api/v1/products/" + product).andExpect(status().isNoContent());
        for (String operation : List.of("entries", "exits")) {
            owner.operate(product, operation, fields("quantity", "1", "reason", "Essai"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("PRODUCT_ARCHIVED"));
        }
        String archivedNew = owner.product("Produit archivé avant stock", "PIECE");
        owner.catalog.delete("/api/v1/products/" + archivedNew);
        owner.operate(archivedNew, "initialize", fields("quantity", "1"))
                .andExpect(jsonPath("$.code").value("PRODUCT_ARCHIVED"));
        // Historique et lecture toujours possibles ; plus listé dans le stock actif.
        owner.get("/api/v1/stock/" + product).andExpect(jsonPath("$.productStatus").value("ARCHIVED"));
        owner.get("/api/v1/stock?q=Thé à la menthe").andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void theSameOperationSentTwiceIsRecordedOnce() throws Exception {
        Client owner = ownerClient("stock-idempotence");
        String product = owner.product("Cahiers 96 pages", "PIECE");
        owner.operate(product, "initialize", fields("quantity", "50")).andExpect(status().isCreated());
        String key = UUID.randomUUID().toString();
        Map<String, Object> exit = fields("quantity", "5", "reason", "Fournitures du bureau");

        String first = owner.operate(product, "exits", exit, key).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String replay = owner.operate(product, "exits", exit, key)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andReturn().getResponse().getContentAsString();
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(replay, "$.movement.id"))
                .isEqualTo(com.jayway.jsonpath.JsonPath.<String>read(first, "$.movement.id"));
        assertThat(owner.quantity(product)).isEqualTo("45");

        owner.operate(product, "exits", fields("quantity", "6", "reason", "Fournitures du bureau"), key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        owner.operate(product, "exits", exit, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
        owner.operate(product, "exits", exit, "pas-un-uuid").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE product_id = ?::uuid", Integer.class,
                product)).isEqualTo(2);
    }

    @Test
    void alertsAndStatusesFollowTheMinimumQuantity() throws Exception {
        Client owner = ownerClient("stock-alertes");
        String a = owner.product("Article A", "PIECE");
        String b = owner.product("Article B", "PIECE");
        String c = owner.product("Article C", "PIECE");
        owner.product("Article D non suivi", "PIECE");
        owner.operate(a, "initialize", fields("quantity", "22", "minimumQuantity", "10")).andExpect(status().isCreated());
        owner.operate(b, "initialize", fields("quantity", "7")).andExpect(status().isCreated());
        owner.operate(c, "initialize", fields("quantity", "1")).andExpect(status().isCreated());
        owner.operate(c, "exits", fields("quantity", "1", "reason", "Dernier exemplaire"));

        owner.get("/api/v1/stock/" + a).andExpect(jsonPath("$.status").value("IN_STOCK"));
        owner.get("/api/v1/stock/" + b).andExpect(jsonPath("$.status").value("IN_STOCK")); // seuil 0 : pas d'alerte avant rupture
        owner.put("/api/v1/stock/" + b + "/minimum-quantity", fields("minimumQuantity", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOW_STOCK"));
        owner.get("/api/v1/stock/" + c).andExpect(jsonPath("$.status").value("OUT_OF_STOCK"));

        owner.get("/api/v1/stock/alerts").andExpect(jsonPath("$.content[*].productName",
                contains("Article C", "Article B")));
        owner.get("/api/v1/stock?status=LOW_STOCK").andExpect(jsonPath("$.content[*].productName", contains("Article B")));
        owner.get("/api/v1/stock?status=NOT_INITIALIZED")
                .andExpect(jsonPath("$.content[*].productName", contains("Article D non suivi")));
        owner.get("/api/v1/stock?q=article b").andExpect(jsonPath("$.content[*].productName", contains("Article B")));
        owner.get("/api/v1/stock?sort=quantity,desc&status=TRACKED")
                .andExpect(jsonPath("$.content[*].productName", contains("Article A", "Article B", "Article C")));
        owner.get("/api/v1/stock?size=2").andExpect(jsonPath("$.page.totalElements").value(4))
                .andExpect(jsonPath("$.page.totalPages").value(2));
        owner.get("/api/v1/stock/summary")
                .andExpect(jsonPath("$.trackedProducts").value(3))
                .andExpect(jsonPath("$.notInitializedProducts").value(1))
                .andExpect(jsonPath("$.inStock").value(1))
                .andExpect(jsonPath("$.lowStock").value(1))
                .andExpect(jsonPath("$.outOfStock").value(1))
                // Estimation au prix d'achat de référence : (22 + 7) × 600.
                .andExpect(jsonPath("$.estimatedValue.amount").value("17400"))
                .andExpect(jsonPath("$.estimatedValue.currency").value("XOF"));
        owner.get("/api/v1/stock?status=SUPPRIME").andExpect(status().isBadRequest());
    }

    @Test
    void permissionsByRoleAndCostProtection() throws Exception {
        Account ownerAccount = accounts.owner("stock-roles");
        Client owner = new Client(ownerAccount);
        String product = owner.product("Gel douche", "PIECE");
        owner.operate(product, "initialize", fields("quantity", "8")).andExpect(status().isCreated());
        Client manager = new Client(accounts.member(ownerAccount, BusinessRole.BUSINESS_MANAGER, "gestionnaire-stock"));
        Client employee = new Client(accounts.member(ownerAccount, BusinessRole.EMPLOYEE, "employe-stock"));

        manager.operate(product, "entries", fields("quantity", "2", "reason", "Réception")).andExpect(status().isCreated());
        manager.get("/api/v1/business/settings").andExpect(status().isOk());
        manager.put("/api/v1/business/settings", fields("allowNegativeStock", true)).andExpect(status().isForbidden());

        employee.get("/api/v1/stock").andExpect(status().isOk());
        employee.get("/api/v1/stock/" + product + "/movements").andExpect(status().isOk());
        String summary = employee.get("/api/v1/stock/summary").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(summary).doesNotContain("estimatedValue", "600");
        employee.operate(product, "exits", fields("quantity", "1", "reason", "x")).andExpect(status().isForbidden());
        employee.operate(product, "adjustments", fields("direction", "OUT", "quantity", "1", "reason", "x"))
                .andExpect(status().isForbidden());
        employee.put("/api/v1/stock/" + product + "/minimum-quantity", fields("minimumQuantity", "1"))
                .andExpect(status().isForbidden());
        employee.get("/api/v1/business/settings").andExpect(status().isForbidden());
        employee.put("/api/v1/business/settings", fields("allowNegativeStock", true)).andExpect(status().isForbidden());
        assertThat(owner.quantity(product)).isEqualTo("10");
        mvc.perform(get("/api/v1/stock")).andExpect(status().isUnauthorized());
    }

    @Test
    void anotherBusinessStockIsInvisibleAndUntouchable() throws Exception {
        Account accountB = accounts.owner("stock-tenant-b");
        Client a = ownerClient("stock-tenant-a");
        Client b = new Client(accountB);
        String productB = b.product("Produit confidentiel B", "PIECE");
        b.operate(productB, "initialize", fields("quantity", "5")).andExpect(status().isCreated());

        a.get("/api/v1/stock/" + productB).andExpect(status().isNotFound());
        a.get("/api/v1/stock/" + productB + "/movements").andExpect(status().isNotFound());
        a.get("/api/v1/stock/movements?productId=" + productB).andExpect(status().isNotFound());
        a.operate(productB, "exits", fields("quantity", "1", "reason", "Piratage")).andExpect(status().isNotFound());
        a.operate(productB, "initialize", fields("quantity", "1")).andExpect(status().isNotFound());
        a.put("/api/v1/stock/" + productB + "/minimum-quantity", fields("minimumQuantity", "1"))
                .andExpect(status().isNotFound());
        a.get("/api/v1/stock/movements").andExpect(jsonPath("$.content").isEmpty());
        mvc.perform(get("/api/v1/stock").param("businessId", accountB.membership().businessId().toString())
                        .header("X-Business-Id", accountB.membership().businessId().toString())
                        .header("Authorization", a.bearer))
                .andExpect(jsonPath("$.content").isEmpty());
        // Paramètre d'une autre entreprise inchangé.
        a.put("/api/v1/business/settings", fields("allowNegativeStock", true)).andExpect(status().isOk());
        b.get("/api/v1/business/settings").andExpect(jsonPath("$.allowNegativeStock").value(false));
        assertThat(b.quantity(productB)).isEqualTo("5");
    }

    @Test
    void unitCannotChangeOnceStockIsInitialized() throws Exception {
        Client owner = ownerClient("stock-unite");
        String product = owner.product("Tissu au mètre", "METRE");
        owner.operate(product, "initialize", fields("quantity", "12.5")).andExpect(status().isCreated());
        owner.catalog.putJson("/api/v1/products/" + product, fields("name", "Tissu au mètre", "unit", "PIECE",
                        "salePrice", "1000", "version", 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("unit"))
                .andExpect(jsonPath("$.errors[0].code").value("LOCKED"));
        owner.catalog.putJson("/api/v1/products/" + product, fields("name", "Tissu wax au mètre", "unit", "METRE",
                "salePrice", "1200", "version", 0)).andExpect(status().isOk());
    }

    @Test
    void everyOperationIsAuditedWithoutQuantitiesOrReasons() throws Exception {
        Account account = accounts.owner("stock-audit");
        Client owner = new Client(account);
        String product = owner.product("Produit audité stock", "PIECE");
        owner.operate(product, "initialize", fields("quantity", "40", "note", "Note initiale secrète"));
        owner.operate(product, "entries", fields("quantity", "11", "reason", "Motif entrée confidentiel"));
        owner.operate(product, "exits", fields("quantity", "3", "reason", "Motif sortie confidentiel"));
        owner.operate(product, "adjustments", fields("direction", "IN", "quantity", "2", "reason", "Motif ajustement"));
        owner.put("/api/v1/stock/" + product + "/minimum-quantity", fields("minimumQuantity", "9"));
        owner.put("/api/v1/business/settings", fields("allowNegativeStock", true));

        List<String> actions = jdbc.queryForList("""
                SELECT action || ':' || coalesce(details->>'REASON', '') FROM audit_log
                WHERE business_id = ? AND (action LIKE 'STOCK_%' OR action = 'SETTINGS_CHANGED') ORDER BY occurred_at""",
                String.class, account.membership().businessId());
        assertThat(actions).containsExactly("STOCK_INITIALIZED:INITIAL", "STOCK_ENTRY_RECORDED:ENTRY",
                "STOCK_EXIT_RECORDED:EXIT", "STOCK_ADJUSTED:ADJUSTMENT_IN", "STOCK_MINIMUM_CHANGED:",
                "SETTINGS_CHANGED:ALLOW_NEGATIVE_STOCK_ENABLED");
        String all = String.join("\n", jdbc.queryForList("SELECT row_to_json(a)::text FROM audit_log a WHERE business_id = ?",
                String.class, account.membership().businessId()));
        assertThat(all).doesNotContain("secrète", "confidentiel", "Motif ajustement", "Produit audité stock");
        // Les mouvements, eux, conservent l'historique métier complet.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE product_id = ?::uuid", Integer.class,
                product)).isEqualTo(4);
    }
}
