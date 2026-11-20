package com.fooyre.gestion;

import static com.fooyre.gestion.CatalogTestClient.fields;
import static com.fooyre.gestion.CatalogTestClient.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Produits (MVP-2) : création, consultation, modification, archivage, prix, SKU, code-barres,
 * catégories, pagination, recherche, filtres, tri, confidentialité du prix d'achat, isolation et
 * audit.
 */
@IntegrationTest
class ProductIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private JdbcTemplate jdbc;

    private CatalogTestClient clientOf(Account account) throws Exception {
        return new CatalogTestClient(mvc, TestAccounts.login(mvc, account.email()).bearer());
    }

    private static Map<String, Object> full(String name, String sku, String barcode, String purchase, String sale) {
        return fields("name", name, "description", "Description de " + name, "sku", sku, "barcode", barcode,
                "unit", "PIECE", "purchasePrice", purchase, "salePrice", sale);
    }

    @Test
    void createReadUpdateAndArchive() throws Exception {
        CatalogTestClient client = clientOf(accounts.owner("produit-crud"));
        String category = client.createCategory("Alimentation");
        Map<String, Object> request = full("  Riz   parfumé 5 kg ", "RIZ-5", "3017620422003", "2750", "3500");
        request.put("categoryId", category);
        request.put("unit", "PACK");

        String id = CatalogTestClient.read(client.postJson("/api/v1/products", request)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Riz parfumé 5 kg"))
                .andExpect(jsonPath("$.sku").value("RIZ-5"))
                .andExpect(jsonPath("$.barcode").value("3017620422003"))
                .andExpect(jsonPath("$.unit").value("PACK"))
                .andExpect(jsonPath("$.category.id").value(category))
                .andExpect(jsonPath("$.category.name").value("Alimentation"))
                .andExpect(jsonPath("$.purchasePrice").value("2750"))
                .andExpect(jsonPath("$.salePrice").value("3500"))
                .andExpect(jsonPath("$.currency").value("XOF"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(0)), "$.id");

        client.getJson("/api/v1/products/" + id).andExpect(status().isOk()).andExpect(jsonPath("$.salePrice").value("3500"));

        Map<String, Object> update = full("Riz parfumé 5 kg", "RIZ-5", null, null, "3600");
        update.put("version", 0);
        client.putJson("/api/v1/products/" + id, update)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salePrice").value("3600"))
                .andExpect(jsonPath("$.barcode").isEmpty())
                .andExpect(jsonPath("$.purchasePrice").doesNotExist())
                .andExpect(jsonPath("$.category").isEmpty())
                .andExpect(jsonPath("$.version").value(1));

        client.delete("/api/v1/products/" + id).andExpect(status().isNoContent());
        client.getJson("/api/v1/products/" + id).andExpect(jsonPath("$.status").value("ARCHIVED"));
        client.getJson("/api/v1/products").andExpect(jsonPath("$.content").isEmpty());
        client.getJson("/api/v1/products?status=ARCHIVED").andExpect(jsonPath("$.content[0].id").value(id));
        update.put("version", 2);
        client.putJson("/api/v1/products/" + id, update)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    void pricesAreValidated() throws Exception {
        CatalogTestClient client = clientOf(accounts.owner("produit-prix"));
        client.postJson("/api/v1/products", product("Gratuit", "0")).andExpect(status().isCreated());
        client.postJson("/api/v1/products", product("Négatif", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("salePrice"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_VALUE"));
        // XOF n'a pas de décimales : 1500.50 est refusé (jamais arrondi en silence) ; 1500.00 est accepté.
        client.postJson("/api/v1/products", product("Décimales", "1500.50"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("salePrice"));
        client.postJson("/api/v1/products", product("Décimales nulles", "1500.00"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.salePrice").value("1500"));
        Map<String, Object> negativeCost = product("Coût négatif", "100");
        negativeCost.put("purchasePrice", "-5");
        Map<String, Object> fractionalCost = product("Coût à décimales", "100");
        fractionalCost.put("purchasePrice", "99.5"); // XOF : aucune décimale, même règle que le prix de vente
        client.postJson("/api/v1/products", fractionalCost)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("purchasePrice"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_VALUE"));
        client.postJson("/api/v1/products", negativeCost)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("purchasePrice"));
        client.postJson("/api/v1/products", fields("name", "Sans prix", "unit", "PIECE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("salePrice"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        client.postJson("/api/v1/products", fields("name", "Prix texte", "unit", "PIECE", "salePrice", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("salePrice"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_FORMAT"));
        client.postJson("/api/v1/products", fields("name", "Unité inconnue", "unit", "TONNE", "salePrice", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("unit"));
        client.postJson("/api/v1/products", product("  ", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void skuAndBarcodeAreUniqueAmongActiveProductsOfTheBusiness() throws Exception {
        CatalogTestClient client = clientOf(accounts.owner("produit-codes"));
        String first = client.createProduct(full("Savon", "SAV-01", "1234567890123", null, "500"));

        client.postJson("/api/v1/products", full("Autre", "sav-01", null, null, "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("sku"))
                .andExpect(jsonPath("$.errors[0].code").value("ALREADY_EXISTS"));
        client.postJson("/api/v1/products", full("Autre", null, "1234567890123", null, "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("barcode"))
                .andExpect(jsonPath("$.errors[0].code").value("ALREADY_EXISTS"));
        client.postJson("/api/v1/products", full("Code invalide", null, "12 34", null, "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_FORMAT"));
        // Un produit peut garder ses propres codes lors d'une modification.
        Map<String, Object> self = full("Savon de Marseille", "SAV-01", "1234567890123", null, "550");
        self.put("version", 0);
        client.putJson("/api/v1/products/" + first, self).andExpect(status().isOk());
        // Archivé : ses codes sont de nouveau disponibles.
        client.delete("/api/v1/products/" + first).andExpect(status().isNoContent());
        client.postJson("/api/v1/products", full("Savon nouveau", "SAV-01", "1234567890123", null, "600"))
                .andExpect(status().isCreated());
        // Une autre entreprise peut utiliser les mêmes codes.
        clientOf(accounts.owner("produit-codes-b")).postJson("/api/v1/products",
                full("Savon B", "SAV-01", "1234567890123", null, "500")).andExpect(status().isCreated());
    }

    @Test
    void searchFilterSortAndPaginate() throws Exception {
        CatalogTestClient client = clientOf(accounts.owner("produit-recherche"));
        String drinks = client.createCategory("Boissons");
        String crafts = client.createCategory("Artisanat");
        Map<String, Object> bissap = full("Jus de bissap 1 L", "JUS-BIS", "6111111111111", "400", "1000");
        bissap.put("categoryId", drinks);
        Map<String, Object> ginger = full("Jus de gingembre 1 L", "JUS-GIN", "6222222222222", "450", "1200");
        ginger.put("categoryId", drinks);
        Map<String, Object> basket = full("Panier tressé", "ART-PAN", "6333333333333", "3000", "7500");
        basket.put("categoryId", crafts);
        String bissapId = client.createProduct(bissap);
        client.createProduct(ginger);
        client.createProduct(basket);
        String archived = client.createProduct(full("Jus archivé", "JUS-OLD", null, null, "800"));
        client.delete("/api/v1/products/" + archived);

        client.getJson("/api/v1/products?q=jus").andExpect(jsonPath("$.content[*].name",
                contains("Jus de bissap 1 L", "Jus de gingembre 1 L")));
        client.getJson("/api/v1/products?q=art-pan").andExpect(jsonPath("$.content[*].name", contains("Panier tressé")));
        client.getJson("/api/v1/products?q=6222222").andExpect(jsonPath("$.content[*].name", contains("Jus de gingembre 1 L")));
        client.getJson("/api/v1/products?q=%25").andExpect(jsonPath("$.content").isEmpty()); // « % » cherché tel quel
        client.getJson("/api/v1/products?categoryId=" + crafts).andExpect(jsonPath("$.content[*].name", contains("Panier tressé")));
        client.getJson("/api/v1/products?status=ARCHIVED").andExpect(jsonPath("$.content[*].name", contains("Jus archivé")));
        client.getJson("/api/v1/products?status=ALL").andExpect(jsonPath("$.page.totalElements").value(4));

        client.getJson("/api/v1/products?sort=salePrice,desc").andExpect(jsonPath("$.content[*].salePrice",
                contains("7500", "1200", "1000")));
        client.getJson("/api/v1/products?sort=createdAt,asc").andExpect(jsonPath("$.content[0].id").value(bissapId));
        client.getJson("/api/v1/products?sort=name,desc").andExpect(jsonPath("$.content[0].name").value("Panier tressé"));

        client.getJson("/api/v1/products?size=2&page=1")
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.page.size").value(2))
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.totalPages").value(2));
        client.getJson("/api/v1/products?size=0").andExpect(status().isBadRequest());
        client.getJson("/api/v1/products?page=-1").andExpect(status().isBadRequest());
        client.getJson("/api/v1/products?sort=purchasePrice,asc").andExpect(status().isBadRequest());
        client.getJson("/api/v1/products?sort=name;drop").andExpect(status().isBadRequest());
        client.getJson("/api/v1/products?q=" + "x".repeat(101)).andExpect(status().isBadRequest());
    }

    @Test
    void anUnknownOrForeignCategoryIsNotFound() throws Exception {
        CatalogTestClient a = clientOf(accounts.owner("produit-categorie-a"));
        CatalogTestClient b = clientOf(accounts.owner("produit-categorie-b"));
        String bCategory = b.createCategory("Catégorie de B");

        Map<String, Object> foreign = product("Produit A", "100");
        foreign.put("categoryId", bCategory);
        a.postJson("/api/v1/products", foreign).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        foreign.put("categoryId", UUID.randomUUID().toString());
        a.postJson("/api/v1/products", foreign).andExpect(status().isNotFound());
        // Filtrer sur la catégorie de B ne révèle rien.
        a.getJson("/api/v1/products?categoryId=" + bCategory).andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void anotherBusinessProductIsInvisibleAndUntouchable() throws Exception {
        Account ownerA = accounts.owner("produit-tenant-a");
        Account ownerB = accounts.owner("produit-tenant-b");
        CatalogTestClient a = clientOf(ownerA);
        CatalogTestClient b = clientOf(ownerB);
        String bProduct = b.createProduct(full("Secret B", "B-1", null, "100", "200"));

        a.getJson("/api/v1/products/" + bProduct).andExpect(status().isNotFound());
        Map<String, Object> hijack = product("Piraté", "1");
        hijack.put("version", 0);
        a.putJson("/api/v1/products/" + bProduct, hijack).andExpect(status().isNotFound());
        a.delete("/api/v1/products/" + bProduct).andExpect(status().isNotFound());
        a.getJson("/api/v1/products?status=ALL&q=Secret").andExpect(jsonPath("$.content").isEmpty());
        // Un identifiant d'entreprise fourni par le client est ignoré.
        mvc.perform(get("/api/v1/products").param("businessId", ownerB.membership().businessId().toString())
                        .header("X-Business-Id", ownerB.membership().businessId().toString())
                        .header("Authorization", TestAccounts.login(mvc, ownerA.email()).bearer()))
                .andExpect(jsonPath("$.content").isEmpty());
        b.getJson("/api/v1/products/" + bProduct)
                .andExpect(jsonPath("$.name").value("Secret B"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void employeesReadTheCatalogWithoutPurchasePricesAndCannotWrite() throws Exception {
        Account owner = accounts.owner("produit-employe");
        CatalogTestClient ownerClient = clientOf(owner);
        String id = ownerClient.createProduct(full("Crème hydratante", "CRE-1", null, "1250", "2500"));
        Account employee = accounts.member(owner, BusinessRole.EMPLOYEE, "employe-catalogue");
        CatalogTestClient employeeClient = clientOf(employee);

        String detail = employeeClient.getJson("/api/v1/products/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salePrice").value("2500"))
                .andExpect(jsonPath("$.purchasePrice").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(detail).doesNotContain("1250", "purchasePrice");
        String list = employeeClient.getJson("/api/v1/products").andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain("1250", "purchasePrice");

        employeeClient.postJson("/api/v1/products", product("Interdit", "1"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        employeeClient.postJson("/api/v1/categories", fields("name", "Interdite")).andExpect(status().isForbidden());
        employeeClient.delete("/api/v1/products/" + id).andExpect(status().isForbidden());
        ownerClient.getJson("/api/v1/products/" + id).andExpect(jsonPath("$.purchasePrice").value("1250"));
    }

    @Test
    void authenticationAndBusinessContextAreRequired() throws Exception {
        mvc.perform(get("/api/v1/products")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/categories")).andExpect(status().isUnauthorized());
        CatalogTestClient lonely = clientOf(accounts.withoutBusiness("produit-sans-entreprise"));
        lonely.getJson("/api/v1/products").andExpect(status().isForbidden());
        lonely.postJson("/api/v1/categories", fields("name", "X")).andExpect(status().isForbidden());
    }

    @Test
    void operationsAreAuditedWithoutCommercialData() throws Exception {
        Account owner = accounts.owner("produit-audit");
        CatalogTestClient client = clientOf(owner);
        String category = client.createCategory("Audit catégorie");
        client.putJson("/api/v1/categories/" + category, fields("name", "Audit catégorie 2", "version", 0));
        client.delete("/api/v1/categories/" + category);
        String product = client.createProduct(full("Produit audité", "AUD-1", "999888777", "456789", "98765"));
        Map<String, Object> update = full("Produit audité 2", "AUD-1", "999888777", "456789", "98766");
        update.put("version", 0);
        client.putJson("/api/v1/products/" + product, update);
        client.delete("/api/v1/products/" + product);

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT action, target_type, target_id, actor_user_id, business_id, details::text AS details
                FROM audit_log WHERE business_id = ? AND action LIKE 'CATEGORY_%' OR (business_id = ? AND action LIKE 'PRODUCT_%')
                ORDER BY occurred_at""", owner.membership().businessId(), owner.membership().businessId());
        assertThat(rows).extracting(row -> row.get("action")).containsExactly(
                "CATEGORY_CREATED", "CATEGORY_UPDATED", "CATEGORY_ARCHIVED",
                "PRODUCT_CREATED", "PRODUCT_UPDATED", "PRODUCT_ARCHIVED");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("actor_user_id")).isEqualTo(owner.userId());
            assertThat(row.get("details")).isEqualTo("{}");
        });
        assertThat(rows).filteredOn(row -> "PRODUCT".equals(row.get("target_type")))
                .extracting(row -> row.get("target_id").toString()).containsOnly(product);
        String all = String.join("\n", jdbc.queryForList(
                "SELECT row_to_json(a)::text FROM audit_log a WHERE business_id = ?", String.class,
                owner.membership().businessId()));
        assertThat(all).doesNotContain("456789", "98765", "98766", "Produit audité", "AUD-1", "999888777");
    }

    @Test
    void unitsListIsClosed() throws Exception {
        CatalogTestClient client = clientOf(accounts.owner("produit-unites"));
        for (String unit : List.of("PIECE", "UNIT", "KILOGRAM", "GRAM", "LITRE", "MILLILITRE", "METRE", "PACK", "CARTON", "LOT")) {
            client.postJson("/api/v1/products", fields("name", "Article " + unit, "unit", unit, "salePrice", "1"))
                    .andExpect(status().isCreated());
        }
        client.getJson("/api/v1/products?size=100").andExpect(jsonPath("$.content[*].unit", containsInAnyOrder(
                "PIECE", "UNIT", "KILOGRAM", "GRAM", "LITRE", "MILLILITRE", "METRE", "PACK", "CARTON", "LOT")));
    }
}
