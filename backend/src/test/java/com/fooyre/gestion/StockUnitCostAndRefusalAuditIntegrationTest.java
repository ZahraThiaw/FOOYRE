package com.fooyre.gestion;

import static com.fooyre.gestion.CatalogTestClient.fields;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fooyre.IntegrationTest;
import com.fooyre.shared.security.BusinessRole;
import com.fooyre.support.TestAccounts;
import com.fooyre.support.TestAccounts.Account;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Finalisation MVP-3 : coût unitaire facultatif des ENTRÉES (V5) et audit des sorties refusées pour
 * stock insuffisant ({@code STOCK_EXIT_REJECTED_INSUFFICIENT}), par l'API réelle.
 */
@IntegrationTest
class StockUnitCostAndRefusalAuditIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private JdbcTemplate jdbc;

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

        String body(String path) throws Exception {
            return get(path).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        }

        ResultActions operate(String productId, String operation, Map<String, Object> body, String key)
                throws Exception {
            return mvc.perform(post("/api/v1/stock/" + productId + "/" + operation).header("Authorization", bearer)
                    .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                    .content(CatalogTestClient.json(body)));
        }

        ResultActions operate(String productId, String operation, Map<String, Object> body) throws Exception {
            return operate(productId, operation, body, UUID.randomUUID().toString());
        }

        void mvcPut(String path, Map<String, Object> body) throws Exception {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                    .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                    .content(CatalogTestClient.json(body))).andExpect(status().isOk());
        }

        String product(String name) throws Exception {
            return catalog.createProduct(fields("name", name, "unit", "PIECE", "salePrice", "1000",
                    "purchasePrice", "600"));
        }
    }

    private int movementCount(String product) {
        return jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE product_id = ?::uuid", Integer.class,
                product);
    }

    private String quantity(String product) {
        return jdbc.queryForObject("SELECT quantity::text FROM product_stock WHERE product_id = ?::uuid", String.class,
                product);
    }

    // ─── Coût unitaire ────────────────────────────────────────────────────────────────────────────

    @Test
    void entryAcceptsAnOptionalUnitCostInTheCurrencyPrecisionWithoutTouchingTheReferencePrice() throws Exception {
        Client owner = new Client(accounts.owner("cout-xof"));
        String product = owner.product("Huile 1 L cout");
        owner.operate(product, "initialize", fields("quantity", "5")).andExpect(status().isCreated());

        owner.operate(product, "entries", fields("quantity", "10", "reason", "Réception", "unitCost", "650"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.movement.unitCost").value("650"))
                .andExpect(jsonPath("$.stock.quantity").value("15"));
        assertThat(jdbc.queryForObject("SELECT unit_cost FROM stock_movement WHERE product_id = ?::uuid AND "
                + "movement_type = 'ENTRY'", BigDecimal.class, product)).isEqualByComparingTo("650");
        // Le prix d'achat de RÉFÉRENCE du produit n'est jamais modifié par une entrée.
        assertThat(jdbc.queryForObject("SELECT purchase_price FROM product WHERE id = ?::uuid", BigDecimal.class,
                product)).isEqualByComparingTo("600");

        // Sans coût : toujours possible, champ absent de la réponse.
        owner.operate(product, "entries", fields("quantity", "1", "reason", "Don fournisseur"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.movement.unitCost").doesNotExist());

        // XOF : aucune décimale ; strictement positif ; jamais arrondi.
        for (String invalid : new String[] {"650.5", "650.50", "0", "-10", "1000000000000000"}) {
            owner.operate(product, "entries", fields("quantity", "1", "reason", "Réception", "unitCost", invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("unitCost"))
                    .andExpect(jsonPath("$.errors[0].code").value("INVALID_VALUE"));
        }
        // Refusé hors entrées.
        owner.operate(product, "exits", fields("quantity", "1", "reason", "Sortie", "unitCost", "650"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("unitCost"));
        owner.operate(product, "adjustments",
                        fields("direction", "IN", "quantity", "1", "reason", "Ajustement", "unitCost", "650"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("unitCost"));
        assertThat(movementCount(product)).isEqualTo(3);
        assertThat(quantity(product)).isEqualTo("16.000");

        // La base refuse aussi un coût sur un autre type de mouvement, ou nul / négatif.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO stock_movement (id, business_id, product_id, movement_type, quantity, quantity_before,
                    quantity_after, reason, created_by, created_at, idempotency_key, request_hash, unit_cost)
                SELECT gen_random_uuid(), business_id, product_id, 'EXIT', 1, 16, 15, 'x', created_by, now(),
                    gen_random_uuid(), repeat('a', 64), 650 FROM stock_movement WHERE product_id = ?::uuid LIMIT 1""",
                product)).rootCause().hasMessageContaining("ck_stock_movement_unit_cost");
        // Immuable : aucune modification possible, même du coût.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.update("UPDATE stock_movement SET unit_cost = 1 WHERE product_id = ?::uuid", product))
                .rootCause().hasMessageContaining("insertion seule");
    }

    @Test
    void euroBusinessAcceptsTwoDecimalsOnly() throws Exception {
        Account account = accounts.owner("cout-eur");
        jdbc.update("UPDATE business SET currency = 'EUR' WHERE id = ?", account.membership().businessId());
        Client owner = new Client(account);
        String product = owner.catalog.createProduct(fields("name", "Thé cout EUR", "unit", "PIECE", "salePrice", "10"));
        owner.operate(product, "initialize", fields("quantity", "1")).andExpect(status().isCreated());
        owner.operate(product, "entries", fields("quantity", "2", "reason", "Réception", "unitCost", "4.50"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.movement.unitCost").value("4.5"));
        owner.operate(product, "entries", fields("quantity", "2", "reason", "Réception", "unitCost", "4.505"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("unitCost"));
    }

    @Test
    void unitCostIsPartOfIdempotency() throws Exception {
        Client owner = new Client(accounts.owner("cout-idem"));
        String product = owner.product("Riz cout idem");
        owner.operate(product, "initialize", fields("quantity", "1")).andExpect(status().isCreated());
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = fields("quantity", "3", "reason", "Réception", "unitCost", "700");
        owner.operate(product, "entries", body, key).andExpect(status().isCreated());
        owner.operate(product, "entries", body, key).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.movement.unitCost").value("700"));
        owner.operate(product, "entries", fields("quantity", "3", "reason", "Réception", "unitCost", "701"), key)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        owner.operate(product, "entries", fields("quantity", "3", "reason", "Réception"), key)
                .andExpect(status().isConflict());
        assertThat(movementCount(product)).isEqualTo(2);
    }

    @Test
    void unitCostIsNeverSentToUsersWithoutCostRead() throws Exception {
        Account ownerAccount = accounts.owner("cout-roles");
        Client owner = new Client(ownerAccount);
        Client manager = new Client(accounts.member(ownerAccount, BusinessRole.BUSINESS_MANAGER, "cout-gestion"));
        Client employee = new Client(accounts.member(ownerAccount, BusinessRole.EMPLOYEE, "cout-employe"));
        String product = owner.product("Savon cout roles");
        owner.operate(product, "initialize", fields("quantity", "2")).andExpect(status().isCreated());
        owner.operate(product, "entries", fields("quantity", "4", "reason", "Réception", "unitCost", "550"))
                .andExpect(status().isCreated());

        // Gestionnaire (COST_READ) : peut consulter et saisir le coût.
        manager.get("/api/v1/stock/" + product + "/movements").andExpect(jsonPath("$.content[0].unitCost").value("550"));
        manager.operate(product, "entries", fields("quantity", "1", "reason", "Réception", "unitCost", "560"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.movement.unitCost").value("560"));

        // Employée : historiques consultables, mais le coût n'apparaît JAMAIS dans les réponses.
        for (String path : new String[] {"/api/v1/stock/" + product + "/movements", "/api/v1/stock/movements",
                "/api/v1/stock/" + product, "/api/v1/stock", "/api/v1/stock/summary"}) {
            String json = employee.body(path);
            assertThat(json).doesNotContain("unitCost", "550", "560", "estimatedValue");
        }
        // … et aucune opération ne lui est permise (avec ou sans coût).
        employee.operate(product, "entries", fields("quantity", "1", "reason", "Réception", "unitCost", "1"))
                .andExpect(status().isForbidden());
        employee.operate(product, "entries", fields("quantity", "1", "reason", "Réception"))
                .andExpect(status().isForbidden());
        assertThat(movementCount(product)).isEqualTo(3);
    }

    // ─── Audit des sorties refusées ────────────────────────────────────────────────────────────────

    @Test
    void refusedExitIsAuditedWithoutMovementNorQuantityChangeNorSensitiveData() throws Exception {
        Account ownerAccount = accounts.owner("refus-audit");
        Client owner = new Client(ownerAccount);
        String product = owner.product("Farine refus audit");
        owner.operate(product, "initialize", fields("quantity", "3")).andExpect(status().isCreated());

        owner.operate(product, "exits", fields("quantity", "4", "reason", "Motif refus confidentiel",
                        "reference", "REF-SECRETE"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));

        // 1-3. Refusée, quantité inchangée, aucun mouvement créé.
        assertThat(quantity(product)).isEqualTo("3.000");
        assertThat(movementCount(product)).isEqualTo(1);
        // 4. Événement enregistré (malgré le refus) : entreprise, auteur, produit, code, date.
        Map<String, Object> event = jdbc.queryForMap("""
                SELECT business_id::text AS business, actor_user_id::text AS actor, target_type, target_id::text AS target,
                       outcome, details->>'REASON' AS reason, occurred_at FROM audit_log
                WHERE action = 'STOCK_EXIT_REJECTED_INSUFFICIENT' AND business_id = ?""",
                ownerAccount.membership().businessId());
        assertThat(event).containsEntry("business", ownerAccount.membership().businessId().toString())
                .containsEntry("actor", ownerAccount.userId().toString())
                .containsEntry("target_type", "PRODUCT")
                .containsEntry("target", product)
                .containsEntry("outcome", "DENIED")
                .containsEntry("reason", "INSUFFICIENT_STOCK");
        assertThat(event.get("occurred_at")).isNotNull();
        // 5. Aucune donnée sensible ni texte libre dans l'événement.
        String row = jdbc.queryForObject("SELECT row_to_json(a)::text FROM audit_log a WHERE business_id = ? AND "
                + "action = 'STOCK_EXIT_REJECTED_INSUFFICIENT'", String.class, ownerAccount.membership().businessId());
        assertThat(row).doesNotContain("confidentiel", "REF-SECRETE", "Farine", owner.bearer.substring(7, 40),
                ownerAccount.email(), "Bearer", "password", TestAccounts.PASSWORD);
        assertThat(jdbc.queryForObject("SELECT details::text FROM audit_log WHERE business_id = ? AND "
                + "action = 'STOCK_EXIT_REJECTED_INSUFFICIENT'", String.class, ownerAccount.membership().businessId()))
                .isEqualTo("{\"REASON\": \"INSUFFICIENT_STOCK\"}");

        // 6. Une sortie autorisée fonctionne normalement ensuite.
        owner.operate(product, "exits", fields("quantity", "2", "reason", "Vente"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.stock.quantity").value("1"));
        assertThat(rejections(ownerAccount)).isEqualTo(1);

        // Stock négatif autorisé : la sortie passe, aucun refus audité.
        owner.mvcPut("/api/v1/business/settings", fields("allowNegativeStock", true));
        owner.operate(product, "exits", fields("quantity", "5", "reason", "Vente avant réception"))
                .andExpect(status().isCreated());
        assertThat(rejections(ownerAccount)).isEqualTo(1);
    }

    @Test
    void unauthorizedAttemptsAreRejectedBeforeAnyAuditAndCannotBypassTheControls() throws Exception {
        Account ownerAccount = accounts.owner("refus-droits");
        Client owner = new Client(ownerAccount);
        Client employee = new Client(accounts.member(ownerAccount, BusinessRole.EMPLOYEE, "refus-employe"));
        Account intruderAccount = accounts.owner("refus-intrus");
        Client intruder = new Client(intruderAccount);
        String product = owner.product("Sucre refus droits");
        owner.operate(product, "initialize", fields("quantity", "1")).andExpect(status().isCreated());
        Map<String, Object> tooMuch = fields("quantity", "9", "reason", "Tentative");

        employee.operate(product, "exits", tooMuch).andExpect(status().isForbidden());
        intruder.operate(product, "exits", tooMuch).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/stock/" + product + "/exits").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(CatalogTestClient.json(tooMuch)))
                .andExpect(status().isUnauthorized());
        // Un ajustement à la baisse refusé n'est PAS une sortie : il reste refusé, sans événement de sortie.
        owner.operate(product, "adjustments", fields("direction", "OUT", "quantity", "9", "reason", "Perte"))
                .andExpect(status().isConflict());

        assertThat(rejections(ownerAccount)).isZero();
        assertThat(rejections(intruderAccount)).isZero();
        assertThat(quantity(product)).isEqualTo("1.000");
        assertThat(movementCount(product)).isEqualTo(1);
    }

    private int rejections(Account account) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE business_id = ? AND "
                + "action = 'STOCK_EXIT_REJECTED_INSUFFICIENT'", Integer.class, account.membership().businessId());
    }
}
