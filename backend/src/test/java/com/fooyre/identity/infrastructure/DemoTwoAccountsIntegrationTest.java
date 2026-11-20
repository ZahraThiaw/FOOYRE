package com.fooyre.identity.infrastructure;

import static com.fooyre.gestion.CatalogTestClient.fields;
import static com.fooyre.support.TestAccounts.loginRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fooyre.IntegrationTest;
import com.fooyre.gestion.CatalogTestClient;
import com.fooyre.identity.application.DemoAccountService;
import com.fooyre.support.TestAccounts;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Deux comptes propriétaires de démonstration sur PostgreSQL réel, créés par l'initialiseur via le
 * service officiel : deux entreprises distinctes, idempotence, mot de passe et données du premier
 * compte préservés, création du second indépendante du premier, isolation par l'API réelle.
 * (Mots de passe : valeurs de TEST uniquement.)
 */
@IntegrationTest
class DemoTwoAccountsIntegrationTest {

    private static final String FIRST_PASSWORD = "Mot-de-passe-test-premier-1";
    private static final String SECOND_PASSWORD = "Mot-de-passe-test-second-2";

    @Autowired
    private DemoAccountService demoAccounts;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private DemoAccountInitializer initializer(String firstEmail, String firstPassword, String secondEmail,
            String secondPassword) {
        return new DemoAccountInitializer(demoAccounts,
                new DemoAccountProperties(firstEmail, "Administrateur Démo", firstPassword, "Commerce Démo Test"),
                new DemoSecondAccountProperties(secondEmail, "Aminata Sarr", secondPassword, "Nafi Chic Test"));
    }

    private String ownedBusiness(String email) {
        return jdbc.queryForObject("""
                SELECT b.name || ':' || b.currency || ':' || m.role FROM business_membership m
                JOIN business b ON b.id = m.business_id JOIN app_user u ON u.id = m.user_id
                WHERE u.email = ? AND m.status = 'ACTIVE'""", String.class, email);
    }

    private String hash(String email) {
        return jdbc.queryForObject("SELECT password_hash FROM app_user WHERE email = ?", String.class, email);
    }

    private String bearer(String email, String password) throws Exception {
        String body = mvc.perform(loginRequest(email, password)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + com.jayway.jsonpath.JsonPath.read(body, "$.accessToken");
    }

    @Test
    void twoIndependentOwnersAreCreatedIdempotentlyAndIsolated() throws Exception {
        String first = TestAccounts.uniqueEmail("demo-un");
        String second = TestAccounts.uniqueEmail("aminata");

        // Création des deux comptes, chacun avec SA propre entreprise (XOF par défaut).
        initializer(first, FIRST_PASSWORD, second, SECOND_PASSWORD).run(new DefaultApplicationArguments());
        assertThat(ownedBusiness(first)).isEqualTo("Commerce Démo Test:XOF:BUSINESS_OWNER");
        assertThat(ownedBusiness(second)).isEqualTo("Nafi Chic Test:XOF:BUSINESS_OWNER");
        String firstHash = hash(first);
        String secondHash = hash(second);

        // Le premier crée un produit (données existantes à préserver).
        String firstBearer = bearer(first, FIRST_PASSWORD);
        String firstProduct = new CatalogTestClient(mvc, firstBearer)
                .createProduct(fields("name", "Produit du premier", "unit", "PIECE", "salePrice", "1000"));

        // Redémarrage simulé avec D'AUTRES mots de passe : rien n'est écrasé ni recréé.
        initializer(first, "Autre-mot-de-passe-ignore-1", second, "Autre-mot-de-passe-ignore-2")
                .run(new DefaultApplicationArguments());
        assertThat(hash(first)).isEqualTo(firstHash);
        assertThat(hash(second)).isEqualTo(secondHash);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM business_membership m JOIN app_user u ON u.id = m.user_id"
                + " WHERE u.email IN (?, ?)", Integer.class, first, second)).isEqualTo(2);
        mvc.perform(loginRequest(first, "Autre-mot-de-passe-ignore-1")).andExpect(status().isUnauthorized());
        String secondBearer = bearer(second, SECOND_PASSWORD);

        // Isolation : chaque propriétaire ne voit que son entreprise et ses produits.
        mvc.perform(get("/api/v1/me").header("Authorization", secondBearer))
                .andExpect(jsonPath("$.activeBusiness.name").value("Nafi Chic Test"))
                .andExpect(jsonPath("$.businesses.length()").value(1));
        mvc.perform(get("/api/v1/products/" + firstProduct).header("Authorization", secondBearer))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/stock/" + firstProduct).header("Authorization", secondBearer))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/stock/" + firstProduct + "/initialize").header("Authorization", secondBearer)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":\"5\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/products?status=ALL").header("Authorization", secondBearer))
                .andExpect(jsonPath("$.page.totalElements").value(0));
        String secondProduct = new CatalogTestClient(mvc, secondBearer)
                .createProduct(fields("name", "Robe longue wax test", "unit", "PIECE", "salePrice", "18000"));
        mvc.perform(get("/api/v1/products/" + secondProduct).header("Authorization", firstBearer))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/products?status=ALL").header("Authorization", firstBearer))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Produit du premier"));
        // Aucune vente ni donnée croisée dans le tableau de bord du premier.
        mvc.perform(get("/api/v1/dashboard").header("Authorization", firstBearer))
                .andExpect(jsonPath("$.salesCountMonth").value(0))
                .andExpect(jsonPath("$.trackedProducts").value(0));
        // Consentement IA NON activé automatiquement pour la nouvelle entreprise.
        assertThat(jdbc.queryForObject("SELECT ai_enabled FROM business WHERE name = 'Nafi Chic Test'", Boolean.class))
                .isFalse();
    }

    @Test
    void secondAccountIsCreatedEvenWhenTheFirstIsNotConfigured() {
        String second = TestAccounts.uniqueEmail("aminata-seule");
        initializer("", "", second, SECOND_PASSWORD).run(new DefaultApplicationArguments());
        assertThat(ownedBusiness(second)).isEqualTo("Nafi Chic Test:XOF:BUSINESS_OWNER");
    }
}
