package com.fooyre.gestion;

import static com.fooyre.gestion.CatalogTestClient.fields;
import static com.fooyre.gestion.CatalogTestClient.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fooyre.IntegrationTest;
import com.fooyre.support.TestAccounts;
import com.fooyre.support.TestAccounts.Account;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** Catégories (MVP-2) : règles métier, validation, archivage, isolation entre entreprises. */
@IntegrationTest
class CategoryIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TestAccounts accounts;

    private CatalogTestClient clientOf(Account account) throws Exception {
        return new CatalogTestClient(mvc, TestAccounts.login(mvc, account.email()).bearer());
    }

    @Test
    void createReadUpdateAndArchive() throws Exception {
        CatalogTestClient client = clientOf(accounts.owner("categorie-crud"));

        String id = CatalogTestClient.read(client.postJson("/api/v1/categories",
                        fields("name", "  Produits   d'entretien ", "description", " Savons, lessives "))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/categories/")))
                .andExpect(jsonPath("$.name").value("Produits d'entretien"))
                .andExpect(jsonPath("$.description").value("Savons, lessives"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.activeProductCount").value(0))
                .andExpect(jsonPath("$.version").value(0)), "$.id");

        client.getJson("/api/v1/categories/" + id).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Produits d'entretien"));

        client.putJson("/api/v1/categories/" + id, fields("name", "Hygiène", "description", null, "version", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Hygiène"))
                .andExpect(jsonPath("$.description").isEmpty())
                .andExpect(jsonPath("$.version").value(1));

        client.delete("/api/v1/categories/" + id).andExpect(status().isNoContent());
        client.getJson("/api/v1/categories/" + id).andExpect(jsonPath("$.status").value("ARCHIVED"));
        client.delete("/api/v1/categories/" + id).andExpect(status().isNoContent()); // idempotent
        client.getJson("/api/v1/categories").andExpect(jsonPath("$.content").isEmpty());
        client.getJson("/api/v1/categories?status=ARCHIVED").andExpect(jsonPath("$.content[0].id").value(id));
    }

    @Test
    void nameIsRequiredAndLimited() throws Exception {
        CatalogTestClient client = clientOf(accounts.owner("categorie-nom"));
        client.postJson("/api/v1/categories", fields("name", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        client.postJson("/api/v1/categories", fields("name", "x".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
    }

    @Test
    void duplicateNamesAreRefusedInTheSameBusinessIgnoringCaseAndSpaces() throws Exception {
        CatalogTestClient client = clientOf(accounts.owner("categorie-doublon"));
        String first = client.createCategory("Boissons");
        client.postJson("/api/v1/categories", fields("name", "  BOISSONS "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].code").value("ALREADY_EXISTS"));
        String other = client.createCategory("Épicerie");
        client.putJson("/api/v1/categories/" + other, fields("name", "boissons", "version", 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("ALREADY_EXISTS"));

        // Un nom archivé est de nouveau disponible.
        client.delete("/api/v1/categories/" + first).andExpect(status().isNoContent());
        client.postJson("/api/v1/categories", fields("name", "Boissons")).andExpect(status().isCreated());
    }

    @Test
    void twoBusinessesMayUseTheSameName() throws Exception {
        clientOf(accounts.owner("categorie-nom-a")).postJson("/api/v1/categories", fields("name", "Cosmétiques"))
                .andExpect(status().isCreated());
        clientOf(accounts.owner("categorie-nom-b")).postJson("/api/v1/categories", fields("name", "Cosmétiques"))
                .andExpect(status().isCreated());
    }

    @Test
    void archivingKeepsTheAssociationWithProductsAndRefusesNewAssignments() throws Exception {
        CatalogTestClient client = clientOf(accounts.owner("categorie-produits"));
        String category = client.createCategory("Tissus");
        var withCategory = product("Wax 6 yards", "12000");
        withCategory.put("categoryId", category);
        String productId = client.createProduct(withCategory);
        client.getJson("/api/v1/categories/" + category).andExpect(jsonPath("$.activeProductCount").value(1));

        client.delete("/api/v1/categories/" + category).andExpect(status().isNoContent());

        // L'association est conservée (historique) et visible.
        client.getJson("/api/v1/products/" + productId)
                .andExpect(jsonPath("$.category.id").value(category))
                .andExpect(jsonPath("$.category.status").value("ARCHIVED"));
        // Le produit reste modifiable en conservant sa catégorie archivée…
        var unchanged = product("Wax 6 yards premium", "13000");
        unchanged.put("categoryId", category);
        unchanged.put("version", 0);
        client.putJson("/api/v1/products/" + productId, unchanged).andExpect(status().isOk());
        // … mais aucune nouvelle association à une catégorie archivée n'est possible.
        var fresh = product("Bazin", "8000");
        fresh.put("categoryId", category);
        client.postJson("/api/v1/products", fresh)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("categoryId"))
                .andExpect(jsonPath("$.errors[0].code").value("ARCHIVED"));
    }

    @Test
    void anArchivedCategoryCannotBeModified() throws Exception {
        CatalogTestClient client = clientOf(accounts.owner("categorie-archivee"));
        String id = client.createCategory("Saisonnier");
        client.delete("/api/v1/categories/" + id);
        client.putJson("/api/v1/categories/" + id, fields("name", "Autre", "version", 1))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    void staleVersionIsAConflict() throws Exception {
        CatalogTestClient client = clientOf(accounts.owner("categorie-version"));
        String id = client.createCategory("Accessoires");
        client.putJson("/api/v1/categories/" + id, fields("name", "Accessoires mode", "version", 0)).andExpect(status().isOk());
        client.putJson("/api/v1/categories/" + id, fields("name", "Accessoires maison", "version", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK"));
        client.putJson("/api/v1/categories/" + id, fields("name", "Accessoires maison"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("version"));
    }

    @Test
    void listIsSortedByNameAndPaginated() throws Exception {
        CatalogTestClient client = clientOf(accounts.owner("categorie-liste"));
        for (String name : new String[] {"céréales", "Bijoux", "alimentation"}) {
            client.createCategory(name);
        }
        client.getJson("/api/v1/categories?size=2")
                .andExpect(jsonPath("$.content[*].name", contains("alimentation", "Bijoux")))
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.totalPages").value(2));
        client.getJson("/api/v1/categories?size=101").andExpect(status().isBadRequest());
        client.getJson("/api/v1/categories?sort=description").andExpect(status().isBadRequest());
        client.getJson("/api/v1/categories?status=SUPPRIME").andExpect(status().isBadRequest());
    }

    @Test
    void anotherBusinessCategoryIsNotFound() throws Exception {
        CatalogTestClient a = clientOf(accounts.owner("categorie-tenant-a"));
        CatalogTestClient b = clientOf(accounts.owner("categorie-tenant-b"));
        String bCategory = b.createCategory("Confidentiel B");

        a.getJson("/api/v1/categories/" + bCategory).andExpect(status().isNotFound());
        a.putJson("/api/v1/categories/" + bCategory, fields("name", "Piraté", "version", 0)).andExpect(status().isNotFound());
        a.delete("/api/v1/categories/" + bCategory).andExpect(status().isNotFound());
        a.getJson("/api/v1/categories?status=ALL").andExpect(jsonPath("$.content").isEmpty());
        b.getJson("/api/v1/categories/" + bCategory)
                .andExpect(jsonPath("$.name").value("Confidentiel B"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(bCategory).isNotBlank();
    }
}
