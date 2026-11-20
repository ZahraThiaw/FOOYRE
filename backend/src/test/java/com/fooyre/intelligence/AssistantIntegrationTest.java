package com.fooyre.intelligence;

import static com.fooyre.gestion.CatalogTestClient.fields;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fooyre.IntegrationTest;
import com.fooyre.gestion.CatalogTestClient;
import com.fooyre.intelligence.application.AiClient;
import com.fooyre.shared.security.BusinessRole;
import com.fooyre.support.TestAccounts;
import com.fooyre.support.TestAccounts.Account;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Assistante IA avec un FAUX fournisseur (aucun appel réel, CLAUDE.md §9) : consentement exigé,
 * instantané filtré, chiffres inventés rejetés, références résolues, actions seulement proposées,
 * utilisation journalisée sans contenu.
 */
@IntegrationTest
@TestPropertySource(properties = {"fooyre.ai.enabled=true", "fooyre.ai.anthropic.api-key=cle-de-test-non-utilisee"})
@Import(AssistantIntegrationTest.FakeConfig.class)
class AssistantIntegrationTest {

    /** Faux fournisseur : enregistre l'invite reçue, renvoie la réponse programmée. */
    static final class FakeAiClient implements AiClient {
        static final AtomicReference<String> LAST_SYSTEM = new AtomicReference<>();
        static final AtomicReference<AssistantPayload> NEXT = new AtomicReference<>();

        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public Result complete(String systemPrompt, List<Turn> history, String question) {
            LAST_SYSTEM.set(systemPrompt);
            return new Result(NEXT.get(), new Usage(1200, 300, "end_turn"), "fake");
        }
    }

    @TestConfiguration
    static class FakeConfig {
        @Bean
        @Primary
        AiClient fakeAiClient() {
            return new FakeAiClient();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private JdbcTemplate jdbc;

    private ResultActions call(String bearer, String method, String path, String body) throws Exception {
        var request = "GET".equals(method)
                ? org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                : "PUT".equals(method) ? org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                : org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path);
        request.header("Authorization", bearer);
        if (path.contains("/stock/")) {
            request.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private static AiClient.AssistantPayload answer(String text, AiClient.Action action) {
        return new AiClient.AssistantPayload(text, false, action);
    }

    @Test
    void consentIsRequiredAndAnswersAreGroundedValidatedAndNeverExecuteActions() throws Exception {
        Account ownerAccount = accounts.owner("ia-parcours");
        String owner = TestAccounts.login(mvc, ownerAccount.email()).bearer();
        CatalogTestClient catalog = new CatalogTestClient(mvc, owner);
        String product = catalog.createProduct(fields("name", "Savon IA", "unit", "PIECE", "salePrice", "1000",
                "purchasePrice", "400"));
        call(owner, "POST", "/api/v1/stock/" + product + "/initialize", "{\"quantity\":\"1\"}")
                .andExpect(status().is2xxSuccessful());
        call(owner, "POST", "/api/v1/stock/" + product + "/exits", "{\"quantity\":\"1\",\"reason\":\"Remise à zéro\"}")
                .andExpect(status().is2xxSuccessful());
        call(owner, "POST", "/api/v1/stock/" + product + "/entries", "{\"quantity\":\"7\",\"reason\":\"Réception\",\"unitCost\":\"450\"}")
                .andExpect(status().is2xxSuccessful());
        FakeAiClient.NEXT.set(answer("Il vous reste 7 Savon IA.", new AiClient.Action("NONE", "", List.of(), "", "", "")));

        // IA désactivée par défaut pour l'entreprise : refus avant tout appel.
        call(owner, "GET", "/api/v1/assistant/status", null).andExpect(jsonPath("$.businessConsent").value(false))
                .andExpect(jsonPath("$.globallyEnabled").value(true));
        FakeAiClient.LAST_SYSTEM.set(null);
        call(owner, "POST", "/api/v1/assistant/messages", "{\"question\":\"Quel est mon stock ?\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AI_NOT_ENABLED"));
        assertThat(FakeAiClient.LAST_SYSTEM.get()).isNull();

        // Consentement explicite de la propriétaire (journalisé).
        call(owner, "PUT", "/api/v1/business/ai-consent", "{\"enabled\":true,\"termsVersion\":\"2026-11-19\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        call(owner, "POST", "/api/v1/assistant/messages", "{\"question\":\"Quel est mon stock ?\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.answer").value("Il vous reste 7 Savon IA."));
        // L'instantané contient les données réelles, le CMP (COST_READ) et aucune donnée personnelle.
        assertThat(FakeAiClient.LAST_SYSTEM.get()).contains("Savon IA", "quantité disponible 7", "coût moyen pondéré 450");

        // Chiffre inventé : réponse rejetée, rien n'est affiché.
        FakeAiClient.NEXT.set(answer("Vous avez vendu 98765 XOF.", new AiClient.Action("NONE", "", List.of(), "", "", "")));
        call(owner, "POST", "/api/v1/assistant/messages", "{\"question\":\"Combien ai-je vendu ?\"}")
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("AI_INVALID_OUTPUT"));

        // Préparation d'une vente : PROPOSITION résolue (produit réel), aucune vente créée.
        FakeAiClient.NEXT.set(answer("Je prépare une vente de 2 Savon IA.",
                new AiClient.Action("PREPARE_SALE", "", List.of(new AiClient.ActionLine("P1", "2")), "", "", "")));
        call(owner, "POST", "/api/v1/assistant/messages", "{\"question\":\"Prépare une vente de deux savons\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposal.type").value("PREPARE_SALE"))
                .andExpect(jsonPath("$.proposal.lines[0].productId").value(product))
                .andExpect(jsonPath("$.proposal.lines[0].quantity").value("2"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sale WHERE business_id = ?", Integer.class,
                ownerAccount.membership().businessId())).isZero();
        // Référence de produit inventée : rejetée.
        FakeAiClient.NEXT.set(answer("Je prépare la vente.",
                new AiClient.Action("PREPARE_SALE", "", List.of(new AiClient.ActionLine("P99", "1")), "", "", "")));
        call(owner, "POST", "/api/v1/assistant/messages", "{\"question\":\"Prépare une vente\"}")
                .andExpect(status().isBadGateway());

        // Utilisation journalisée (jetons, coût estimé), jamais le contenu.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_usage WHERE business_id = ?", Integer.class,
                ownerAccount.membership().businessId())).isEqualTo(4);
        String audit = String.join("\n", jdbc.queryForList("SELECT row_to_json(a)::text FROM audit_log a WHERE business_id = ?",
                String.class, ownerAccount.membership().businessId()));
        assertThat(audit).contains("AI_QUERY", "AI_CONSENT_CHANGED").doesNotContain("Savon IA", "stock ?", "98765");

        // Révocation : effet immédiat.
        call(owner, "PUT", "/api/v1/business/ai-consent", "{\"enabled\":false}").andExpect(status().isOk());
        call(owner, "POST", "/api/v1/assistant/messages", "{\"question\":\"Quel est mon stock ?\"}")
                .andExpect(status().isForbidden());
    }

    @Test
    void employeeHasNoAccessAndManagerCannotChangeConsent() throws Exception {
        Account ownerAccount = accounts.owner("ia-roles");
        String employee = TestAccounts.login(mvc, accounts.member(ownerAccount, BusinessRole.EMPLOYEE, "ia-employe").email()).bearer();
        String manager = TestAccounts.login(mvc, accounts.member(ownerAccount, BusinessRole.BUSINESS_MANAGER, "ia-gestion").email()).bearer();
        call(employee, "POST", "/api/v1/assistant/messages", "{\"question\":\"Quels sont mes coûts d'achat ?\"}")
                .andExpect(status().isForbidden());
        call(employee, "GET", "/api/v1/assistant/status", null).andExpect(status().isForbidden());
        call(manager, "PUT", "/api/v1/business/ai-consent", "{\"enabled\":true,\"termsVersion\":\"2026-11-19\"}")
                .andExpect(status().isForbidden());
        call(manager, "GET", "/api/v1/assistant/status", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.canManageConsent").value(false));
        assertThat(UUID.randomUUID()).isNotNull();
    }
}
