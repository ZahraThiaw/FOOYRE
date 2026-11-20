package com.fooyre.identity;

import static com.fooyre.support.TestAccounts.PASSWORD;
import static com.fooyre.support.TestAccounts.authPost;
import static com.fooyre.support.TestAccounts.loginRequest;
import static com.fooyre.support.TestAccounts.refreshSetCookieHeader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fooyre.IntegrationTest;
import com.fooyre.shared.config.AuthProperties;
import com.fooyre.shared.security.AccessTokenClaims;
import com.fooyre.shared.security.AccessTokenService;
import com.fooyre.shared.security.BusinessRole;
import com.fooyre.support.TestAccounts;
import com.fooyre.support.TestAccounts.Account;
import com.fooyre.support.TestAccounts.Session;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Authentification réelle (MVP-1, ADR-04) sur PostgreSQL 18.7 : connexion, /me, renouvellement
 * avec rotation et détection de réutilisation, déconnexion et révocation, protection CSRF des
 * routes à cookie, revérification en base de chaque jeton d'accès.
 */
@IntegrationTest
class AuthenticationIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AccessTokenService accessTokens;

    @Autowired
    private AuthProperties authProperties;

    // --- Connexion --------------------------------------------------------------------------------

    @Test
    void loginWithValidCredentialsReturnsAnAccessTokenAndAnHttpOnlyRefreshCookie() throws Exception {
        Account owner = accounts.owner("connexion");

        MvcResult result = mvc.perform(loginRequest(owner.email(), PASSWORD))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andReturn();

        String cookie = refreshSetCookieHeader(result);
        assertThat(cookie).isNotNull()
                .contains("HttpOnly", "SameSite=Strict", "Path=/api/v1/auth", "Max-Age=" + Duration.ofDays(30).toSeconds());
        assertThat(cookie).doesNotContain("Domain=");
        assertThat(result.getResponse().getContentAsString()).doesNotContain(TestAccounts.refreshCookieValue(result));
        String lastLogin = jdbc.queryForObject("SELECT last_login_at::text FROM app_user WHERE id = ?", String.class,
                owner.userId());
        assertThat(lastLogin).isNotNull();
    }

    @Test
    void loginIsCaseInsensitiveOnTheEmail() throws Exception {
        Account owner = accounts.owner("casse");
        mvc.perform(loginRequest(owner.email().toUpperCase(), PASSWORD)).andExpect(status().isOk());
    }

    @Test
    void wrongPasswordAndUnknownAccountGetTheSameNeutralAnswer() throws Exception {
        Account owner = accounts.owner("mauvais-mdp");

        MvcResult wrongPassword = mvc.perform(loginRequest(owner.email(), "Mauvais-mot-de-passe-9"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn();
        MvcResult unknown = mvc.perform(loginRequest(TestAccounts.uniqueEmail("inconnu"), PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn();

        String first = wrongPassword.getResponse().getContentAsString().replaceAll("\"traceId\":\"[^\"]+\"", "");
        String second = unknown.getResponse().getContentAsString().replaceAll("\"traceId\":\"[^\"]+\"", "");
        assertThat(first).isEqualTo(second);
        assertThat(first).doesNotContain(owner.email());
    }

    @Test
    void disabledAccountCannotLogInAndGetsTheSameAnswer() throws Exception {
        Account owner = accounts.owner("desactive");
        jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", owner.userId());
        mvc.perform(loginRequest(owner.email(), PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void passwordIsStoredAsABcryptHashOnly() {
        Account owner = accounts.owner("hachage");
        String hash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id = ?", String.class, owner.userId());
        assertThat(hash).startsWith("$2a$12$").doesNotContain(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, hash)).isTrue();
        Integer plainTextCopies = jdbc.queryForObject(
                "SELECT count(*) FROM app_user WHERE password_hash = ? OR email = ? OR full_name = ?",
                Integer.class, PASSWORD, PASSWORD, PASSWORD);
        assertThat(plainTextCopies).isZero();
    }

    @Test
    void invalidLoginBodyIsAValidationError() throws Exception {
        mvc.perform(authPost("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"pas-un-email\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("email", "password")));
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
        "NULL, fooyre",
        "http://evil.example, fooyre",
        "http://localhost:5173, NULL",
        "http://localhost:5173, XMLHttpRequest"})
    void cookieRoutesRequireTheExpectedOriginAndHeader(String origin, String requestedWith) throws Exception {
        Account owner = accounts.owner("csrf");
        for (String path : new String[] {"/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout"}) {
            var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + owner.email() + "\",\"password\":\"" + PASSWORD + "\"}");
            if (origin != null) {
                request.header("Origin", origin);
            }
            if (requestedWith != null) {
                request.header("X-Requested-With", requestedWith);
            }
            mvc.perform(request)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("CSRF_CHECK_FAILED"))
                    .andExpect(header().doesNotExist("Set-Cookie"));
        }
    }

    // --- /me ----------------------------------------------------------------------------------------

    @Test
    void meWithoutAuthenticationIs401() throws Exception {
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
    }

    @Test
    void meIsNotAuthenticatedByTheRefreshCookie() throws Exception {
        Session session = TestAccounts.login(mvc, accounts.owner("cookie-me").email());
        mvc.perform(get("/api/v1/me").cookie(session.refreshCookie()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meReturnsTheRealProfileBusinessRoleAndPermissions() throws Exception {
        Account owner = accounts.owner("profil");
        Session session = TestAccounts.login(mvc, owner.email());

        MvcResult result = mvc.perform(get("/api/v1/me").header("Authorization", session.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(owner.userId().toString()))
                .andExpect(jsonPath("$.email").value(owner.email()))
                .andExpect(jsonPath("$.fullName").value(owner.fullName()))
                .andExpect(jsonPath("$.locale").value("fr"))
                .andExpect(jsonPath("$.platformAdmin").value(false))
                .andExpect(jsonPath("$.activeBusiness.id").value(owner.membership().businessId().toString()))
                .andExpect(jsonPath("$.activeBusiness.name").value("Entreprise profil"))
                .andExpect(jsonPath("$.activeBusiness.currency").value("XOF"))
                .andExpect(jsonPath("$.activeMembership.id").value(owner.membership().membershipId().toString()))
                .andExpect(jsonPath("$.activeMembership.role").value("BUSINESS_OWNER"))
                .andExpect(jsonPath("$.activeMembership.employeeMaxDiscountRate").isEmpty())
                .andExpect(jsonPath("$.permissions", hasItems("BUSINESS_READ", "MEMBER_MANAGE", "BUSINESS_SETTINGS_UPDATE")))
                .andExpect(jsonPath("$.permissions", not(hasItem("PLATFORM_AUDIT_READ"))))
                .andExpect(jsonPath("$.businesses.length()").value(1))
                .andExpect(jsonPath("$.businesses[0].name").value("Entreprise profil"))
                .andExpect(jsonPath("$.businesses[0].role").value("BUSINESS_OWNER"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("password", "$2a$");
    }

    // --- Renouvellement -----------------------------------------------------------------------------

    @Test
    void refreshRotatesTheRefreshTokenAndIssuesAWorkingAccessToken() throws Exception {
        Session first = TestAccounts.login(mvc, accounts.owner("rotation").email());

        MvcResult renewed = mvc.perform(authPost("/api/v1/auth/refresh")
                        .cookie(first.refreshCookie())
                        // Jeton d'accès invalide ou expiré envoyé par le client : ignoré sur cette route.
                        .header("Authorization", "Bearer jeton.invalide.ou.expire"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();
        Session second = TestAccounts.sessionOf(renewed);

        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(second.accessToken()).isNotEqualTo(first.accessToken());
        mvc.perform(get("/api/v1/me").header("Authorization", second.bearer())).andExpect(status().isOk());
    }

    @Test
    void reuseWithinTheGraceWindowIsRefusedWithoutRevokingTheSession() throws Exception {
        Session first = TestAccounts.login(mvc, accounts.owner("tolerance").email());
        Session second = TestAccounts.sessionOf(mvc.perform(authPost("/api/v1/auth/refresh").cookie(first.refreshCookie())).andReturn());

        mvc.perform(authPost("/api/v1/auth/refresh").cookie(first.refreshCookie()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REVOKED"));

        mvc.perform(get("/api/v1/me").header("Authorization", second.bearer())).andExpect(status().isOk());
        mvc.perform(authPost("/api/v1/auth/refresh").cookie(second.refreshCookie())).andExpect(status().isOk());
    }

    @Test
    void reuseAfterTheGraceWindowRevokesTheWholeSession() throws Exception {
        Session first = TestAccounts.login(mvc, accounts.owner("reutilisation").email());
        Session second = TestAccounts.sessionOf(mvc.perform(authPost("/api/v1/auth/refresh").cookie(first.refreshCookie())).andReturn());
        // Le remplacement a eu lieu « il y a » plus longtemps que la fenêtre de tolérance.
        jdbc.update("UPDATE refresh_token SET replaced_at = replaced_at - interval '1 minute' "
                + "WHERE token_hash = encode(sha256(?::bytea), 'hex')", first.refreshToken());

        mvc.perform(authPost("/api/v1/auth/refresh").cookie(first.refreshCookie()))
                .andExpect(status().isUnauthorized());

        // Toute la famille est révoquée : le jeton le plus récent et le jeton d'accès aussi.
        mvc.perform(authPost("/api/v1/auth/refresh").cookie(second.refreshCookie())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").header("Authorization", second.bearer())).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForList("SELECT DISTINCT revocation_reason FROM refresh_token WHERE family_id = "
                + "(SELECT family_id FROM refresh_token WHERE token_hash = encode(sha256(?::bytea), 'hex'))",
                String.class, second.refreshToken())).containsExactly("REUSE_DETECTED");
    }

    @Test
    void refreshWithoutOrWithAnUnknownCookieIs401AndClearsTheCookie() throws Exception {
        MvcResult missing = mvc.perform(authPost("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REVOKED"))
                .andReturn();
        assertThat(refreshSetCookieHeader(missing)).contains("Max-Age=0");

        mvc.perform(authPost("/api/v1/auth/refresh").cookie(new jakarta.servlet.http.Cookie("fooyre_refresh", "inconnu")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredRefreshTokenIsRefused() throws Exception {
        Session session = TestAccounts.login(mvc, accounts.owner("refresh-expire").email());
        jdbc.update("UPDATE refresh_token SET created_at = created_at - interval '40 days', expires_at = now() - interval '1 second' "
                + "WHERE token_hash = encode(sha256(?::bytea), 'hex')", session.refreshToken());
        mvc.perform(authPost("/api/v1/auth/refresh").cookie(session.refreshCookie())).andExpect(status().isUnauthorized());
    }

    // --- Déconnexion --------------------------------------------------------------------------------

    @Test
    void logoutRevokesTheSessionImmediately() throws Exception {
        Session session = TestAccounts.login(mvc, accounts.owner("deconnexion").email());
        mvc.perform(get("/api/v1/me").header("Authorization", session.bearer())).andExpect(status().isOk());

        MvcResult logout = mvc.perform(authPost("/api/v1/auth/logout").cookie(session.refreshCookie()))
                .andExpect(status().isNoContent())
                .andReturn();
        assertThat(refreshSetCookieHeader(logout)).contains("Max-Age=0", "Path=/api/v1/auth", "HttpOnly");

        // Le jeton d'accès encore non expiré est refusé dès la requête suivante.
        mvc.perform(get("/api/v1/me").header("Authorization", session.bearer()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
        mvc.perform(authPost("/api/v1/auth/refresh").cookie(session.refreshCookie())).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT revocation_reason FROM refresh_token WHERE token_hash = encode(sha256(?::bytea), 'hex')",
                String.class, session.refreshToken())).isEqualTo("LOGOUT");
    }

    @Test
    void logoutWithoutCookieIsIdempotent() throws Exception {
        mvc.perform(authPost("/api/v1/auth/logout")).andExpect(status().isNoContent());
    }

    @Test
    void logoutDoesNotAffectOtherSessionsOfTheSameUser() throws Exception {
        Account owner = accounts.owner("deux-sessions");
        Session phone = TestAccounts.login(mvc, owner.email());
        Session laptop = TestAccounts.login(mvc, owner.email());
        mvc.perform(authPost("/api/v1/auth/logout").cookie(phone.refreshCookie())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me").header("Authorization", laptop.bearer())).andExpect(status().isOk());
    }

    // --- Jetons d'accès refusés -----------------------------------------------------------------------

    @Test
    void expiredAccessTokenIs401() throws Exception {
        Account owner = accounts.owner("acces-expire");
        Session session = TestAccounts.login(mvc, owner.email());
        UUID sessionId = jdbc.queryForObject("SELECT family_id FROM refresh_token WHERE token_hash = encode(sha256(?::bytea), 'hex')",
                UUID.class, session.refreshToken());
        AccessTokenService pastIssuer = new AccessTokenService(authProperties,
                Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC));
        String expired = pastIssuer.issue(new AccessTokenClaims(owner.userId(), sessionId,
                owner.membership().businessId(), owner.membership().membershipId(), BusinessRole.BUSINESS_OWNER, 0)).value();

        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
    }

    @Test
    void tokenSignedWithAnotherKeyIs401() throws Exception {
        Account owner = accounts.owner("autre-cle");
        AuthProperties otherKey = new AuthProperties("YXV0cmUtY2xlLWRlLXRlc3Qtbm9uLXNlY3JldGUtMzItb2N0ZXRz",
                Duration.ofMinutes(15), Duration.ofDays(30), Duration.ofSeconds(10), false);
        String forged = new AccessTokenService(otherKey, Clock.systemUTC()).issue(new AccessTokenClaims(owner.userId(),
                UUID.randomUUID(), owner.membership().businessId(), owner.membership().membershipId(),
                BusinessRole.BUSINESS_OWNER, 0)).value();
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + forged)).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenForASessionThatDoesNotExistIs401() throws Exception {
        Account owner = accounts.owner("session-inconnue");
        String token = accessTokens.issue(new AccessTokenClaims(owner.userId(), UUID.randomUUID(),
                owner.membership().businessId(), owner.membership().membershipId(), BusinessRole.BUSINESS_OWNER, 0)).value();
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test
    void changingTheSecurityVersionInvalidatesAccessTokens() throws Exception {
        Account owner = accounts.owner("version-securite");
        Session session = TestAccounts.login(mvc, owner.email());
        jdbc.update("UPDATE app_user SET security_version = security_version + 1 WHERE id = ?", owner.userId());
        mvc.perform(get("/api/v1/me").header("Authorization", session.bearer())).andExpect(status().isUnauthorized());
    }

    @Test
    void revokedMembershipIsRefusedAtTheNextRequestAndAtRefresh() throws Exception {
        Account owner = accounts.owner("appartenance-revoquee");
        Session session = TestAccounts.login(mvc, owner.email());
        jdbc.update("UPDATE business_membership SET status = 'REVOKED', revoked_at = now() WHERE id = ?",
                owner.membership().membershipId());

        mvc.perform(get("/api/v1/me").header("Authorization", session.bearer())).andExpect(status().isUnauthorized());
        mvc.perform(authPost("/api/v1/auth/refresh").cookie(session.refreshCookie())).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT revocation_reason FROM refresh_token WHERE token_hash = encode(sha256(?::bytea), 'hex')",
                String.class, session.refreshToken())).isEqualTo("MEMBERSHIP_REVOKED");
    }

    @Test
    void suspendedBusinessIsRefused() throws Exception {
        Account owner = accounts.owner("entreprise-suspendue");
        Session session = TestAccounts.login(mvc, owner.email());
        jdbc.update("UPDATE business SET status = 'SUSPENDED' WHERE id = ?", owner.membership().businessId());
        mvc.perform(get("/api/v1/me").header("Authorization", session.bearer())).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshTokensAreStoredHashedOnly() throws Exception {
        Session session = TestAccounts.login(mvc, accounts.owner("hache-refresh").email());
        Integer plain = jdbc.queryForObject("SELECT count(*) FROM refresh_token WHERE token_hash = ?", Integer.class,
                session.refreshToken());
        Integer hashed = jdbc.queryForObject("SELECT count(*) FROM refresh_token WHERE token_hash = encode(sha256(?::bytea), 'hex')",
                Integer.class, session.refreshToken());
        assertThat(plain).isZero();
        assertThat(hashed).isEqualTo(1);
    }
}
