package com.fooyre.gestion;

import static com.fooyre.gestion.CatalogTestClient.fields;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fooyre.IntegrationTest;
import com.fooyre.shared.security.BusinessRole;
import com.fooyre.support.TestAccounts;
import com.fooyre.support.TestAccounts.Account;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Clients (MVP-4A) par l'API réelle : création, consultation, liste paginée, recherche (nom,
 * téléphone, e-mail), modification, archivage, client archivé, idempotence, permissions par rôle,
 * isolation entre entreprises, audit sans données personnelles.
 */
@IntegrationTest
class CustomerIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private JdbcTemplate jdbc;

    private final class Client {
        final String bearer;

        Client(Account account) throws Exception {
            this.bearer = TestAccounts.login(mvc, account.email()).bearer();
        }

        ResultActions get(String path) throws Exception {
            return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                    .header("Authorization", bearer));
        }

        ResultActions create(Map<String, Object> body, String key) throws Exception {
            var request = post("/api/v1/customers").header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON).content(CatalogTestClient.json(body));
            if (key != null) {
                request.header("Idempotency-Key", key);
            }
            return mvc.perform(request);
        }

        ResultActions create(Map<String, Object> body) throws Exception {
            return create(body, null);
        }

        String createId(Map<String, Object> body) throws Exception {
            return CatalogTestClient.read(create(body).andExpect(status().isCreated()), "$.id");
        }

        ResultActions update(String id, Map<String, Object> body) throws Exception {
            return mvc.perform(put("/api/v1/customers/" + id).header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON).content(CatalogTestClient.json(body)));
        }

        ResultActions archive(String id) throws Exception {
            return mvc.perform(delete("/api/v1/customers/" + id).header("Authorization", bearer));
        }
    }

    private static Map<String, Object> with(Map<String, Object> base, Object... keyValues) {
        Map<String, Object> copy = new HashMap<>(base);
        copy.putAll(fields(keyValues));
        return copy;
    }

    @Test
    void createReadUpdateAndArchiveACustomer() throws Exception {
        Client owner = new Client(accounts.owner("client-parcours"));
        owner.create(fields("name", "  Aïssatou   Ndiaye ", "phone", " +221 77 123 45 67 ", "email", "Aissatou.NDIAYE@Exemple.SN",
                        "address", "Médina, rue 11", "notes", "Préfère être appelée le matin"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/customers/")))
                .andExpect(jsonPath("$.name").value("Aïssatou Ndiaye"))
                .andExpect(jsonPath("$.phone").value("+221 77 123 45 67"))
                .andExpect(jsonPath("$.email").value("aissatou.ndiaye@exemple.sn"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(0));
        String id = jdbc.queryForObject("SELECT id::text FROM customer WHERE name = 'Aïssatou Ndiaye'", String.class);
        assertThat(jdbc.queryForObject("SELECT phone_digits FROM customer WHERE id = ?::uuid", String.class, id))
                .isEqualTo("221771234567");

        owner.get("/api/v1/customers/" + id).andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value("Médina, rue 11"))
                .andExpect(jsonPath("$.notes").value("Préfère être appelée le matin"));

        owner.update(id, fields("name", "Aïssatou Ndiaye Diallo", "phone", "77 123 45 67", "version", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Aïssatou Ndiaye Diallo"))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.version").value(1));
        // Version périmée : conflit, rien n'est écrasé.
        owner.update(id, fields("name", "Écrasement", "version", 0))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK"));
        owner.update(id, fields("name", "Sans version")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("version"));

        owner.archive(id).andExpect(status().isNoContent());
        owner.get("/api/v1/customers/" + id).andExpect(jsonPath("$.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.archivedAt").isNotEmpty());
        // Client déjà archivé : nouvel archivage refusé, modification refusée, toujours consultable.
        owner.archive(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_ARCHIVED"));
        owner.update(id, fields("name", "Réactivation détournée", "version", 2))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer WHERE id = ?::uuid", Integer.class, id)).isOne();
        // Aucune suppression physique, même par SQL direct pour l'application ? (RLS : voir le test dédié)
        owner.get("/api/v1/customers?status=ARCHIVED").andExpect(jsonPath("$.content[*].id", contains(id)));
        owner.get("/api/v1/customers").andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    void onlyANameOrAPhoneIsRequiredAndInputsAreValidatedWithoutBeingTooStrict() throws Exception {
        Client owner = new Client(accounts.owner("client-validation"));
        // Téléphone seul, nom seul : acceptés.
        owner.create(fields("phone", "77 000 11 22")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").doesNotExist());
        owner.create(fields("name", "Boutique Keur Yaay")).andExpect(status().isCreated());
        // Formats internationaux usuels.
        for (String phone : new String[] {"+221771234567", "00221 77 123 45 67", "(+33) 6 12 34 56 78", "33-822-12-34",
                "76.555.44.33"}) {
            owner.create(fields("phone", phone)).andExpect(status().isCreated());
        }
        // Ni nom ni téléphone (espaces seulement) : refusé.
        owner.create(fields("name", "   ", "email", "seule@exemple.sn")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        for (String phone : new String[] {"77 12", "abc 77 123 45", "+221 77 123 45 67 89 01 2", "77#1234567"}) {
            owner.create(fields("phone", phone)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("phone"));
        }
        owner.create(fields("name", "Email invalide", "email", "pas-un-email")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_FORMAT"));
        owner.create(fields("name", "x".repeat(151))).andExpect(status().isBadRequest());
        owner.create(fields("name", "Notes longues", "notes", "n".repeat(2001))).andExpect(status().isBadRequest());

        // Aucune unicité : homonymes et numéro partagé sont acceptés (même entreprise).
        owner.create(fields("name", "Fatou Diop", "phone", "77 999 88 77")).andExpect(status().isCreated());
        owner.create(fields("name", "Fatou Diop", "phone", "77 999 88 77")).andExpect(status().isCreated());
        // Champs inconnus ignorés : jamais d'entreprise choisie par le client.
        Account other = accounts.owner("client-validation-autre");
        owner.create(fields("name", "Tentative", "businessId", other.membership().businessId().toString()))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer WHERE business_id = ?", Integer.class,
                other.membership().businessId())).isZero();
    }

    @Test
    void listIsPaginatedSortedAndSearchableByNamePhoneAndEmail() throws Exception {
        Client owner = new Client(accounts.owner("client-recherche"));
        owner.createId(fields("name", "Mariama Ba", "phone", "+221 77 123 45 67"));
        owner.createId(fields("name", "Awa Sow", "email", "awa.sow@commerce.sn"));
        owner.createId(fields("name", "Ndeye Fall", "phone", "70 555 66 77"));
        owner.createId(fields("phone", "76 222 33 44"));
        for (int i = 0; i < 22; i++) {
            owner.createId(fields("name", String.format("Cliente %02d", i)));
        }

        owner.get("/api/v1/customers?size=10").andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(10))
                .andExpect(jsonPath("$.page.totalElements").value(26))
                .andExpect(jsonPath("$.page.totalPages").value(3))
                .andExpect(jsonPath("$.content[0].name").value("Awa Sow"));
        owner.get("/api/v1/customers?size=10&page=2").andExpect(jsonPath("$.content.length()").value(6));

        // Nom (casse ignorée), partie du nom.
        owner.get("/api/v1/customers?q=mariama").andExpect(jsonPath("$.content[*].name", contains("Mariama Ba")));
        // Téléphone : quelle que soit la mise en forme saisie.
        for (String q : new String[] {"771234567", "77 123 45", "+221 77 123", "00221771234567"}) {
            mvc.perform(get("/api/v1/customers").param("q", q).header("Authorization", owner.bearer))
                    .andExpect(jsonPath("$.content[*].name", contains("Mariama Ba")));
        }
        owner.get("/api/v1/customers?q=76 222").andExpect(jsonPath("$.page.totalElements").value(1));
        // E-mail.
        owner.get("/api/v1/customers?q=COMMERCE.SN").andExpect(jsonPath("$.content[*].name", contains("Awa Sow")));
        // Caractères génériques pris littéralement ; aucune correspondance.
        owner.get("/api/v1/customers?q=%25").andExpect(jsonPath("$.page.totalElements").value(0));
        owner.get("/api/v1/customers?q=inexistant").andExpect(jsonPath("$.page.totalElements").value(0));
        // Limites : recherche trop longue, tri hors liste blanche, taille de page.
        owner.get("/api/v1/customers?q=" + "a".repeat(101)).andExpect(status().isBadRequest());
        owner.get("/api/v1/customers?sort=phone,asc").andExpect(status().isBadRequest());
        owner.get("/api/v1/customers?size=101").andExpect(status().isBadRequest());
        owner.get("/api/v1/customers?sort=createdAt,desc&size=1")
                .andExpect(jsonPath("$.content[0].name").value("Cliente 21"));
    }

    @Test
    void creationWithTheSameIdempotencyKeyIsRecordedOnce() throws Exception {
        Client owner = new Client(accounts.owner("client-idempotence"));
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = fields("name", "Khady Sarr", "phone", "77 321 00 00");
        String id = CatalogTestClient.read(owner.create(body, key).andExpect(status().isCreated()), "$.id");
        owner.create(body, key).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.id").value(id));
        owner.create(with(body, "phone", "77 321 00 01"), key)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        owner.create(body, "pas-un-uuid").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer WHERE name = 'Khady Sarr'", Integer.class)).isOne();
    }

    @Test
    void permissionsFollowTheRoleMatrix() throws Exception {
        Account ownerAccount = accounts.owner("client-roles");
        Client owner = new Client(ownerAccount);
        Client manager = new Client(accounts.member(ownerAccount, BusinessRole.BUSINESS_MANAGER, "client-gestion"));
        Client employee = new Client(accounts.member(ownerAccount, BusinessRole.EMPLOYEE, "client-employe"));
        String id = owner.createId(fields("name", "Cliente roles"));

        // Gestionnaire : gestion complète.
        String managed = manager.createId(fields("name", "Cliente du gestionnaire"));
        manager.update(managed, fields("name", "Cliente modifiée", "version", 0)).andExpect(status().isOk());
        manager.archive(managed).andExpect(status().isNoContent());
        // Employé (matrice D3 : CUSTOMER_READ + CUSTOMER_WRITE, sans CUSTOMER_ARCHIVE).
        employee.get("/api/v1/customers").andExpect(status().isOk());
        employee.get("/api/v1/customers/" + id).andExpect(status().isOk());
        String created = CatalogTestClient.read(employee.create(fields("name", "Cliente comptoir"))
                .andExpect(status().isCreated()), "$.id");
        employee.update(created, fields("name", "Cliente comptoir corrigée", "version", 0)).andExpect(status().isOk());
        employee.archive(id).andExpect(status().isForbidden());
        owner.get("/api/v1/customers/" + id).andExpect(jsonPath("$.status").value("ACTIVE"));

        // Sans session, sans entreprise : refus.
        mvc.perform(get("/api/v1/customers")).andExpect(status().isUnauthorized());
        Client orphan = new Client(accounts.withoutBusiness("client-sans-entreprise"));
        orphan.get("/api/v1/customers").andExpect(status().isForbidden());
    }

    @Test
    void anotherBusinessCustomersAreInvisibleAndUntouchable() throws Exception {
        Client alpha = new Client(accounts.owner("client-alpha"));
        Client beta = new Client(accounts.owner("client-beta"));
        String alphaCustomer = alpha.createId(fields("name", "Cliente Alpha", "phone", "77 111 11 11"));
        beta.createId(fields("name", "Cliente Alpha", "phone", "77 111 11 11")); // même nom, même numéro : permis

        beta.get("/api/v1/customers/" + alphaCustomer).andExpect(status().isNotFound());
        beta.update(alphaCustomer, fields("name", "Piratée", "version", 0)).andExpect(status().isNotFound());
        beta.archive(alphaCustomer).andExpect(status().isNotFound());
        beta.get("/api/v1/customers?q=77 111").andExpect(jsonPath("$.page.totalElements").value(1));
        beta.get("/api/v1/customers?status=ALL").andExpect(jsonPath("$.content[*].id",
                org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(alphaCustomer))));
        // En-têtes d'entreprise du client : ignorés.
        mvc.perform(get("/api/v1/customers/" + alphaCustomer).header("Authorization", beta.bearer)
                .header("X-Business-Id", "ignored")).andExpect(status().isNotFound());
        alpha.get("/api/v1/customers/" + alphaCustomer).andExpect(jsonPath("$.name").value("Cliente Alpha"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void operationsAreAuditedWithoutPersonalData() throws Exception {
        Account account = accounts.owner("client-audit");
        Client owner = new Client(account);
        String id = owner.createId(fields("name", "Cliente Confidentielle", "phone", "77 404 40 40",
                "email", "secret@exemple.sn", "address", "Adresse privée", "notes", "Note privée"));
        owner.update(id, fields("name", "Cliente Confidentielle Bis", "phone", "77 404 40 41", "version", 0));
        owner.archive(id);
        owner.create(fields("name", "Rejouée"), "6a0f1a3e-1f2b-4c5d-8e9f-0a1b2c3d4e5f");
        owner.create(fields("name", "Rejouée"), "6a0f1a3e-1f2b-4c5d-8e9f-0a1b2c3d4e5f"); // rejeu : pas d'audit

        List<String> actions = jdbc.queryForList("""
                SELECT action || ':' || target_type || ':' || target_id FROM audit_log
                WHERE business_id = ? AND action LIKE 'CUSTOMER_%' ORDER BY occurred_at""", String.class,
                account.membership().businessId());
        assertThat(actions).hasSize(4).startsWith("CUSTOMER_CREATED:CUSTOMER:" + id,
                "CUSTOMER_UPDATED:CUSTOMER:" + id, "CUSTOMER_ARCHIVED:CUSTOMER:" + id);
        String all = String.join("\n", jdbc.queryForList(
                "SELECT row_to_json(a)::text FROM audit_log a WHERE business_id = ?", String.class,
                account.membership().businessId()));
        assertThat(all).doesNotContain("Confidentielle", "77 404 40", "774044040", "secret@", "Adresse privée", "Note privée", "Rejouée");
    }
}
