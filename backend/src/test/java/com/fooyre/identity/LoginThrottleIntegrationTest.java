package com.fooyre.identity;

import static com.fooyre.support.TestAccounts.PASSWORD;
import static com.fooyre.support.TestAccounts.authPost;
import static com.fooyre.support.TestAccounts.loginRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fooyre.IntegrationTest;
import com.fooyre.identity.application.LoginThrottleService;
import com.fooyre.support.TestAccounts;
import com.fooyre.support.TestAccounts.Account;
import com.fooyre.support.TestAccounts.Session;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Limitation des tentatives de connexion (MVP-1.1) : trois dimensions (compte + IP, compte, IP),
 * compteurs PostgreSQL, blocage temporaire, réponse générique 429. Chaque test utilise ses propres
 * adresses IP fictives (plage de documentation) pour rester indépendant des autres.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class LoginThrottleIntegrationTest {

    private static final String WRONG = "Mauvais-mot-de-passe-1";
    private static final AtomicInteger NEXT_IP = new AtomicInteger(1);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LoginThrottleService throttle;

    /** Nouvelle adresse IP fictive (RFC 5737 / RFC 3849), propre à l'appelant. */
    private static String newIp() {
        int n = NEXT_IP.getAndIncrement();
        return "198.51." + (n / 250) + "." + (n % 250 + 1);
    }

    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private MvcResult attempt(String email, String password, String ip) throws Exception {
        MockHttpServletRequestBuilder request = loginRequest(email, password).with(from(ip));
        return mvc.perform(request).andReturn();
    }

    private void fail(String email, String ip, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            assertThat(attempt(email, WRONG, ip).getResponse().getStatus()).isEqualTo(401);
        }
    }

    @Test
    void normalLoginIsAllowedAndASuccessResetsTheAccountCounters() throws Exception {
        Account owner = accounts.owner("limite-normale");
        String ip = newIp();
        fail(owner.email(), ip, 4);
        assertThat(attempt(owner.email(), PASSWORD, ip).getResponse().getStatus()).isEqualTo(200);
        // Compteurs du compte remis à zéro : 4 nouveaux échecs ne bloquent toujours pas.
        fail(owner.email(), ip, 4);
        assertThat(attempt(owner.email(), PASSWORD, ip).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void repeatedFailuresForAnAccountFromAnIpBlockEvenTheRightPassword() throws Exception {
        Account owner = accounts.owner("limite-couple");
        String ip = newIp();
        fail(owner.email(), ip, 5);

        mvc.perform(loginRequest(owner.email(), PASSWORD).with(from(ip)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        // Une autre IP reste autorisée (le seuil par compte seul n'est pas atteint).
        assertThat(attempt(owner.email(), PASSWORD, newIp()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void limitByIpAcrossManyAccounts() throws Exception {
        String ip = newIp();
        for (int i = 0; i < 50; i++) {
            assertThat(attempt(TestAccounts.uniqueEmail("ip" + i), WRONG, ip).getResponse().getStatus()).isEqualTo(401);
        }
        Account owner = accounts.owner("limite-ip");
        assertThat(attempt(owner.email(), PASSWORD, ip).getResponse().getStatus()).isEqualTo(429);
        assertThat(attempt(owner.email(), PASSWORD, newIp()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void limitByNormalizedAccountAcrossManyIps() throws Exception {
        Account victim = accounts.owner("limite-compte");
        String upper = victim.email().toUpperCase();
        for (int i = 0; i < 20; i++) {
            // Variantes de casse : même compte normalisé, IP différente à chaque fois.
            String variant = i % 2 == 0 ? upper : victim.email();
            assertThat(attempt(variant, WRONG, newIp()).getResponse().getStatus()).isEqualTo(401);
        }
        assertThat(attempt(victim.email(), PASSWORD, newIp()).getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void theBlockExpires() throws Exception {
        Account owner = accounts.owner("limite-expiration");
        String ip = newIp();
        fail(owner.email(), ip, 5);
        assertThat(attempt(owner.email(), PASSWORD, ip).getResponse().getStatus()).isEqualTo(429);

        // La durée de blocage et la fenêtre sont écoulées.
        LoginThrottleService.Keys keys = throttle.keysFor(owner.email(), ip);
        jdbc.update("""
                UPDATE login_throttle SET blocked_until = now() - interval '1 second',
                       window_started_at = now() - interval '1 hour' WHERE key_hash IN (?, ?, ?)""",
                keys.accountAndIp(), keys.account(), keys.ip());

        assertThat(attempt(owner.email(), PASSWORD, ip).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void unknownAccountIsLimitedExactlyLikeAnExistingOne() throws Exception {
        Account owner = accounts.owner("limite-existant");
        String unknown = TestAccounts.uniqueEmail("limite-inconnu");
        String ipKnown = newIp();
        String ipUnknown = newIp();
        fail(owner.email(), ipKnown, 5);
        fail(unknown, ipUnknown, 5);

        MvcResult known = attempt(owner.email(), WRONG, ipKnown);
        MvcResult notKnown = attempt(unknown, WRONG, ipUnknown);
        assertThat(known.getResponse().getStatus()).isEqualTo(429);
        assertThat(notKnown.getResponse().getStatus()).isEqualTo(429);
        String a = known.getResponse().getContentAsString().replaceAll("\"traceId\":\"[^\"]+\"", "");
        String b = notKnown.getResponse().getContentAsString().replaceAll("\"traceId\":\"[^\"]+\"", "");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void nothingSensitiveInResponsesStorageOrLogs(CapturedOutput output) throws Exception {
        int start = output.getAll().length(); // uniquement les journaux produits pendant ce test
        Account owner = accounts.owner("limite-fuite");
        String ip = newIp();
        fail(owner.email(), ip, 5);
        MvcResult blocked = attempt(owner.email(), WRONG, ip);

        assertThat(blocked.getResponse().getContentAsString()).doesNotContain(owner.email(), WRONG, ip);
        List<String> rows = jdbc.queryForList("SELECT row_to_json(t)::text FROM login_throttle t", String.class);
        assertThat(String.join("\n", rows)).doesNotContain(owner.email(), ip, WRONG, PASSWORD);
        assertThat(output.getAll().substring(start)).doesNotContain(owner.email(), WRONG, PASSWORD);
    }

    @Test
    void refreshAndLogoutStillWorkWhileLoginIsBlocked() throws Exception {
        Account owner = accounts.owner("limite-session");
        String ip = newIp();
        Session session = TestAccounts.sessionOf(mvc.perform(loginRequest(owner.email(), PASSWORD).with(from(ip))).andReturn());
        fail(owner.email(), ip, 5);
        assertThat(attempt(owner.email(), PASSWORD, ip).getResponse().getStatus()).isEqualTo(429);

        Session renewed = TestAccounts.sessionOf(mvc.perform(authPost("/api/v1/auth/refresh")
                .cookie(session.refreshCookie()).with(from(ip))).andReturn());
        mvc.perform(get("/api/v1/me").header("Authorization", renewed.bearer())).andExpect(status().isOk());
        mvc.perform(authPost("/api/v1/auth/logout").cookie(renewed.refreshCookie()).with(from(ip)))
                .andExpect(status().isNoContent());
    }

    @Test
    void expiredCountersArePurged() {
        String stale = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO login_throttle (key_hash, key_type, failure_count, window_started_at, blocked_until, updated_at)
                VALUES (?, 'IP', 3, now() - interval '3 days', now() - interval '2 days', now() - interval '2 days')""",
                stale);
        assertThat(throttle.purgeExpired()).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_throttle WHERE key_hash = ?", Integer.class, stale))
                .isZero();
    }
}
