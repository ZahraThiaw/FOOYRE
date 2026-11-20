package com.fooyre.gestion;

import static com.fooyre.gestion.CatalogTestClient.fields;
import static org.assertj.core.api.Assertions.assertThat;

import com.fooyre.IntegrationTest;
import com.fooyre.support.TestAccounts;
import com.fooyre.support.TestAccounts.Account;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Audit de la précision monétaire du catalogue (règles EN VIGUEUR, vérifiées côté backend) :
 * pour chaque devise, type de prix et valeur, statut HTTP, erreur, valeur persistée et valeur
 * renvoyée. Règle (décision de la porteuse du projet, 2026-11-19) : prix de vente ET prix d'achat de
 * référence ≤ décimales de la devise (ISO 4217 : XOF 0, EUR 2), refusés sinon, jamais arrondis.
 */
@IntegrationTest
class MonetaryPrecisionAuditTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private JdbcTemplate jdbc;

    record Observation(String currency, String priceType, String sent, int status, String error, String persisted,
            String returned) {

        String row() {
            return "| " + currency + " | " + priceType + " | " + sent + " | " + status + " | " + error + " | "
                    + persisted + " | " + returned + " |";
        }
    }

    private Observation create(CatalogTestClient client, String currency, String priceType, String value) throws Exception {
        Map<String, Object> body = fields("name", "Audit " + priceType + " " + value, "unit", "PIECE",
                "salePrice", priceType.equals("vente") ? value : "1000",
                "purchasePrice", priceType.equals("achat") ? value : null);
        MvcResult result = client.postJson("/api/v1/products", body).andReturn();
        String json = result.getResponse().getContentAsString();
        int status = result.getResponse().getStatus();
        if (status != 201) {
            String error = JsonPath.read(json, "$.code") + " " + JsonPath.read(json, "$.errors[0].field") + "="
                    + JsonPath.read(json, "$.errors[0].code");
            return new Observation(currency, priceType, value, status, error, "—", "—");
        }
        String id = JsonPath.read(json, "$.id");
        String column = priceType.equals("vente") ? "sale_price" : "purchase_price";
        String persisted = jdbc.queryForObject("SELECT " + column + "::text FROM product WHERE id = ?::uuid",
                String.class, id);
        String returned = JsonPath.read(json, priceType.equals("vente") ? "$.salePrice" : "$.purchasePrice");
        return new Observation(currency, priceType, value, status, "—", persisted, returned);
    }

    @Test
    void observeAndPinTheCurrentPrecisionRules() throws Exception {
        Account xofOwner = accounts.owner("precision-xof");
        Account eurOwner = accounts.owner("precision-eur");
        // Aucune API ne permet encore de changer la devise : fixture de test directe (base de test).
        jdbc.update("UPDATE business SET currency = 'EUR' WHERE id = ?", eurOwner.membership().businessId());
        CatalogTestClient xof = new CatalogTestClient(mvc, TestAccounts.login(mvc, xofOwner.email()).bearer());
        CatalogTestClient eur = new CatalogTestClient(mvc, TestAccounts.login(mvc, eurOwner.email()).bearer());

        List<Observation> observations = new ArrayList<>();
        for (String type : List.of("vente", "achat")) {
            for (String value : List.of("1800", "1800.0", "1800.5", "1800.50", "1800.1234")) {
                observations.add(create(xof, "XOF", type, value));
            }
            for (String value : List.of("1800", "1800.50", "1800.123")) {
                observations.add(create(eur, "EUR", type, value));
            }
        }
        System.out.println("=== AUDIT PRECISION MONETAIRE ===");
        System.out.println("| Devise | Prix | Envoyé | HTTP | Erreur | Persisté | Renvoyé |");
        observations.forEach(o -> System.out.println(o.row()));
        System.out.println("=== FIN AUDIT ===");

        // Règles en vigueur, figées par ce test (toute évolution de règle devra le mettre à jour).
        assertThat(observations).extracting(Observation::row).containsExactly(
                "| XOF | vente | 1800 | 201 | — | 1800.00 | 1800 |",
                "| XOF | vente | 1800.0 | 201 | — | 1800.00 | 1800 |",
                "| XOF | vente | 1800.5 | 400 | VALIDATION_ERROR salePrice=INVALID_VALUE | — | — |",
                "| XOF | vente | 1800.50 | 400 | VALIDATION_ERROR salePrice=INVALID_VALUE | — | — |",
                "| XOF | vente | 1800.1234 | 400 | VALIDATION_ERROR salePrice=INVALID_VALUE | — | — |",
                "| EUR | vente | 1800 | 201 | — | 1800.00 | 1800.00 |",
                "| EUR | vente | 1800.50 | 201 | — | 1800.50 | 1800.50 |",
                "| EUR | vente | 1800.123 | 400 | VALIDATION_ERROR salePrice=INVALID_VALUE | — | — |",
                "| XOF | achat | 1800 | 201 | — | 1800.0000 | 1800 |",
                "| XOF | achat | 1800.0 | 201 | — | 1800.0000 | 1800 |",
                "| XOF | achat | 1800.5 | 400 | VALIDATION_ERROR purchasePrice=INVALID_VALUE | — | — |",
                "| XOF | achat | 1800.50 | 400 | VALIDATION_ERROR purchasePrice=INVALID_VALUE | — | — |",
                "| XOF | achat | 1800.1234 | 400 | VALIDATION_ERROR purchasePrice=INVALID_VALUE | — | — |",
                "| EUR | achat | 1800 | 201 | — | 1800.0000 | 1800.00 |",
                "| EUR | achat | 1800.50 | 201 | — | 1800.5000 | 1800.50 |",
                "| EUR | achat | 1800.123 | 400 | VALIDATION_ERROR purchasePrice=INVALID_VALUE | — | — |");
    }

    @Test
    void theSameRuleAppliesOnUpdateAndPreExistingValuesAreReturnedUnchanged() throws Exception {
        Account owner = accounts.owner("precision-modif");
        CatalogTestClient client = new CatalogTestClient(mvc, TestAccounts.login(mvc, owner.email()).bearer());
        String id = client.createProduct(fields("name", "Modifié", "unit", "PIECE", "salePrice", "1000",
                "purchasePrice", "800"));
        Map<String, Object> update = fields("name", "Modifié", "unit", "PIECE", "salePrice", "1000",
                "purchasePrice", "800.5", "version", 0);
        String body = client.putJson("/api/v1/products/" + id, update).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"field\":\"purchasePrice\"", "INVALID_VALUE");
        update.put("salePrice", "1000.5");
        update.put("purchasePrice", "800");
        body = client.putJson("/api/v1/products/" + id, update).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"field\":\"salePrice\"", "INVALID_VALUE");

        // Valeur enregistrée AVANT la règle (comme les produits « Thé vert 100 g » de la démonstration) :
        // renvoyée telle quelle, sans arrondi ni erreur ; aucune donnée n'est modifiée.
        jdbc.update("UPDATE product SET purchase_price = 1800.5 WHERE id = ?::uuid", id);
        client.getJson("/api/v1/products/" + id)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.purchasePrice")
                        .value("1800.5"));
        assertThat(jdbc.queryForObject("SELECT purchase_price::text FROM product WHERE id = ?::uuid", String.class, id))
                .isEqualTo("1800.5000");
    }
}
