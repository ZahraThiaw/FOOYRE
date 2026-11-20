package com.fooyre.shared.audit;

import static com.fooyre.support.TestAccounts.PASSWORD;
import static com.fooyre.support.TestAccounts.authPost;
import static com.fooyre.support.TestAccounts.loginRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fooyre.IntegrationTest;
import com.fooyre.support.TestAccounts;
import com.fooyre.support.TestAccounts.Account;
import com.fooyre.support.TestAccounts.Session;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Journal d'audit (MVP-1.1) : contenu utile, aucune donnée sensible, cohérence transactionnelle. */
@IntegrationTest
class AuditIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuditService audit;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private List<Map<String, Object>> events(String where, Object... args) {
        return jdbc.queryForList("""
                SELECT action, outcome, actor_user_id, business_id, target_type, target_id, trace_id, ip_hash,
                       details::text AS details
                FROM audit_log
                """ + " WHERE " + where + " ORDER BY occurred_at", args);
    }

    @Test
    void successfulLoginIsRecordedWithActorBusinessSessionAndTrace() throws Exception {
        Account owner = accounts.owner("audit-connexion");
        MvcResult result = mvc.perform(loginRequest(owner.email(), PASSWORD)).andReturn();
        Session session = TestAccounts.sessionOf(result);
        UUID sessionId = jdbc.queryForObject(
                "SELECT family_id FROM refresh_token WHERE token_hash = encode(sha256(?::bytea), 'hex')",
                UUID.class, session.refreshToken());

        List<Map<String, Object>> rows = events("action = 'LOGIN_SUCCEEDED' AND actor_user_id = ?", owner.userId());
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.get("outcome")).isEqualTo("SUCCESS");
            assertThat(row.get("business_id")).isEqualTo(owner.membership().businessId());
            assertThat(row.get("target_type")).isEqualTo("SESSION");
            assertThat(row.get("target_id")).isEqualTo(sessionId);
            assertThat(row.get("trace_id")).isEqualTo(result.getResponse().getHeader("X-Trace-Id"));
            assertThat((String) row.get("ip_hash")).hasSize(64).doesNotContain("127.0.0.1");
            assertThat(row.get("details")).isEqualTo("{}");
        });
    }

    @Test
    void failedLoginWithoutKnownActorIsRecordedDespiteTheRefusal() throws Exception {
        String unknown = TestAccounts.uniqueEmail("audit-inconnu");
        MvcResult result = mvc.perform(loginRequest(unknown, "Mauvais-mot-de-passe-1")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(401);

        List<Map<String, Object>> rows = events("action = 'LOGIN_FAILED' AND trace_id = ?",
                result.getResponse().getHeader("X-Trace-Id"));
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.get("outcome")).isEqualTo("FAILURE");
            assertThat(row.get("actor_user_id")).isNull();
            assertThat(row.get("business_id")).isNull();
            assertThat(row.get("target_id")).isNull();
            assertThat(row.get("details")).isEqualTo("{\"REASON\": \"UNKNOWN_ACCOUNT\"}");
        });
    }

    @Test
    void failedLoginOnAnExistingAccountTargetsTheAccountAndRateLimitIsRecorded() throws Exception {
        Account owner = accounts.owner("audit-echec");
        for (int i = 0; i < 5; i++) {
            mvc.perform(loginRequest(owner.email(), "Mauvais-mot-de-passe-1").with(r -> {
                r.setRemoteAddr("203.0.113.77");
                return r;
            }));
        }
        MvcResult limited = mvc.perform(loginRequest(owner.email(), PASSWORD).with(r -> {
            r.setRemoteAddr("203.0.113.77");
            return r;
        })).andReturn();
        assertThat(limited.getResponse().getStatus()).isEqualTo(429);

        assertThat(events("action = 'LOGIN_FAILED' AND target_id = ?", owner.userId()))
                .hasSize(5)
                .allSatisfy(row -> assertThat(row.get("details")).isEqualTo("{\"REASON\": \"BAD_PASSWORD\"}"));
        assertThat(events("action = 'LOGIN_RATE_LIMITED' AND trace_id = ?", limited.getResponse().getHeader("X-Trace-Id")))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.get("outcome")).isEqualTo("DENIED");
                    assertThat(row.get("details")).isEqualTo("{\"LIMIT\": \"ACCOUNT_IP\"}");
                });
    }

    @Test
    void logoutAndTokenReuseAreRecordedForTheSessionBusiness() throws Exception {
        Account owner = accounts.owner("audit-session");
        Session first = TestAccounts.login(mvc, owner.email());
        Session second = TestAccounts.sessionOf(mvc.perform(authPost("/api/v1/auth/refresh").cookie(first.refreshCookie())).andReturn());
        jdbc.update("UPDATE refresh_token SET replaced_at = replaced_at - interval '1 minute' "
                + "WHERE token_hash = encode(sha256(?::bytea), 'hex')", first.refreshToken());
        mvc.perform(authPost("/api/v1/auth/refresh").cookie(first.refreshCookie()));

        assertThat(events("action = 'TOKEN_REUSE_DETECTED' AND actor_user_id = ?", owner.userId()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.get("business_id")).isEqualTo(owner.membership().businessId());
                    assertThat(row.get("details")).isEqualTo("{\"REASON\": \"REUSE_DETECTED\"}");
                });

        Session other = TestAccounts.login(mvc, owner.email());
        mvc.perform(authPost("/api/v1/auth/logout").cookie(other.refreshCookie()));
        mvc.perform(authPost("/api/v1/auth/logout").cookie(other.refreshCookie())); // déjà révoquée : pas de second événement
        assertThat(events("action = 'LOGOUT' AND actor_user_id = ?", owner.userId())).singleElement()
                .satisfies(row -> assertThat(row.get("business_id")).isEqualTo(owner.membership().businessId()));
        assertThat(second.accessToken()).isNotBlank();
    }

    @Test
    void accountAndBusinessCreationAreRecorded() {
        Account owner = accounts.owner("audit-creation");
        assertThat(events("action = 'ACCOUNT_CREATED' AND target_id = ?", owner.userId())).hasSize(1);
        assertThat(events("action = 'BUSINESS_CREATED' AND business_id = ?", owner.membership().businessId()))
                .singleElement()
                .satisfies(row -> assertThat(row.get("details")).isEqualTo("{\"ROLE\": \"BUSINESS_OWNER\"}"));
    }

    @Test
    void eventsOfOneBusinessNeverReferenceAnother() throws Exception {
        Account a = accounts.owner("audit-a");
        Account b = accounts.owner("audit-b");
        TestAccounts.login(mvc, a.email());
        TestAccounts.login(mvc, b.email());
        assertThat(events("actor_user_id = ?", a.userId()))
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.get("business_id")).isIn(a.membership().businessId(), null));
        assertThat(events("business_id = ?", a.membership().businessId()))
                .noneMatch(row -> b.userId().equals(row.get("actor_user_id")));
    }

    @Test
    void noSecretOrPersonalDataIsEverStored() throws Exception {
        Account owner = accounts.owner("audit-secrets");
        Session session = TestAccounts.login(mvc, owner.email());
        mvc.perform(loginRequest(owner.email(), "Mauvais-mot-de-passe-1"));
        mvc.perform(authPost("/api/v1/auth/logout").cookie(session.refreshCookie()));
        String hash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id = ?", String.class, owner.userId());

        String all = String.join("\n", jdbc.queryForList("SELECT row_to_json(a)::text FROM audit_log a", String.class));
        assertThat(all).doesNotContain(PASSWORD, "Mauvais-mot-de-passe-1", session.accessToken(), session.refreshToken(),
                hash, owner.email(), owner.fullName(), "127.0.0.1", "fooyre_refresh", "Bearer");
    }

    @Test
    void metadataOutsideTheWhitelistFormatIsRejected() {
        AuditEvent event = AuditEvent.of(AuditAction.LOGIN_FAILED, AuditOutcome.FAILURE);
        assertThatThrownBy(() -> event.with(AuditDetail.REASON, "password=secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("secret");
        assertThatThrownBy(() -> event.with(AuditDetail.REASON, "victime@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> event.with(AuditDetail.SESSION_ID, "pas-un-uuid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(event.details()).isEmpty();
    }

    @Test
    void recordRequiresTheOperationTransactionAndRollsBackWithIt() {
        UUID target = UUID.randomUUID();
        AuditEvent event = AuditEvent.of(AuditAction.LOGOUT, AuditOutcome.SUCCESS).target(AuditTarget.SESSION, target);
        assertThatThrownBy(() -> audit.record(event)).isInstanceOf(IllegalTransactionStateException.class);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            audit.record(event);
            status.setRollbackOnly(); // l'opération échoue : l'événement de succès disparaît avec elle
        });
        assertThat(events("target_id = ?", target)).isEmpty();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            audit.recordIndependently(AuditEvent.of(AuditAction.LOGIN_FAILED, AuditOutcome.FAILURE)
                    .target(AuditTarget.USER, target));
            status.setRollbackOnly(); // un échec journalisé indépendamment subsiste
        });
        assertThat(events("target_id = ?", target)).hasSize(1);
    }

    @Test
    void theJournalIsInsertOnly() {
        Account owner = accounts.owner("audit-immuable");
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_log SET outcome = 'FAILURE' WHERE target_id = ?", owner.userId()))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("insertion seule");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_log WHERE target_id = ?", owner.userId()))
                .isInstanceOf(DataAccessException.class);
        assertThat(events("target_id = ?", owner.userId())).hasSize(1);
    }
}
