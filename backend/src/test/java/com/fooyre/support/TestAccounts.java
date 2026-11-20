package com.fooyre.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fooyre.business.application.MembershipService;
import com.fooyre.business.application.MembershipView;
import com.fooyre.identity.application.UserAccountService;
import com.fooyre.shared.security.BusinessRole;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.boot.test.context.TestComponent;

/**
 * Comptes et entreprises de TEST (valeurs fictives, adresses uniques par test : le conteneur
 * PostgreSQL est partagé par toutes les classes). Passe par les services applicatifs réels.
 */
@TestComponent
public class TestAccounts {

    /** Mot de passe fictif des comptes de test (jamais utilisé hors des tests). */
    public static final String PASSWORD = "Mot-de-passe-de-test-1";
    public static final String ORIGIN = "http://localhost:5173";
    public static final String REFRESH_COOKIE = "fooyre_refresh";

    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"accessToken\":\"([^\"]+)\"");

    private final UserAccountService accounts;
    private final MembershipService memberships;
    private final JdbcTemplate jdbc;

    public TestAccounts(UserAccountService accounts, MembershipService memberships, JdbcTemplate jdbc) {
        this.accounts = accounts;
        this.memberships = memberships;
        this.jdbc = jdbc;
    }

    public record Account(UUID userId, String email, String fullName, MembershipView membership) {
    }

    /** Compte propriétaire d'une nouvelle entreprise. */
    public Account owner(String label) {
        String email = uniqueEmail(label);
        String fullName = "Propriétaire " + label;
        UUID userId = accounts.createAccount(email, fullName, PASSWORD);
        MembershipView membership = memberships.createBusinessWithOwner("Entreprise " + label, userId);
        return new Account(userId, email, fullName, membership);
    }

    /**
     * Membre d'une entreprise existante avec un autre rôle (ex. EMPLOYEE). La gestion des membres
     * n'existant pas encore, l'appartenance est insérée directement (base de test uniquement).
     */
    public Account member(Account owner, BusinessRole role, String label) {
        String email = uniqueEmail(label);
        String fullName = "Membre " + label;
        UUID userId = accounts.createAccount(email, fullName, PASSWORD);
        UUID membershipId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO business_membership (id, business_id, user_id, role, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', now(), now())""", membershipId, owner.membership().businessId(), userId,
                role.name());
        MembershipView membership = new MembershipView(membershipId, owner.membership().businessId(),
                owner.membership().businessName(), owner.membership().currency(), role);
        return new Account(userId, email, fullName, membership);
    }

    /** Compte sans aucune entreprise. */
    public Account withoutBusiness(String label) {
        String email = uniqueEmail(label);
        UUID userId = accounts.createAccount(email, "Sans entreprise " + label, PASSWORD);
        return new Account(userId, email, "Sans entreprise " + label, null);
    }

    public static String uniqueEmail(String label) {
        return label.toLowerCase().replaceAll("[^a-z0-9]", "") + "-" + UUID.randomUUID() + "@test.fooyre.invalid";
    }

    // --- Requêtes HTTP --------------------------------------------------------------------------

    /** Requête vers une route d'authentification avec les en‑têtes attendus (Origin, X-Requested-With). */
    public static MockHttpServletRequestBuilder authPost(String path) {
        return post(path).header("Origin", ORIGIN).header("X-Requested-With", "fooyre");
    }

    public static MockHttpServletRequestBuilder loginRequest(String email, String password) {
        return authPost("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    public record Session(String accessToken, String refreshToken) {

        public String bearer() {
            return "Bearer " + accessToken;
        }

        public Cookie refreshCookie() {
            return new Cookie(REFRESH_COOKIE, refreshToken);
        }
    }

    public static Session login(MockMvc mvc, String email) throws Exception {
        return sessionOf(mvc.perform(loginRequest(email, PASSWORD)).andReturn());
    }

    /** Jeton d'accès du corps et refresh token du cookie posé par la réponse. */
    public static Session sessionOf(MvcResult result) throws Exception {
        if (result.getResponse().getStatus() != 200) {
            throw new IllegalStateException("Statut inattendu : " + result.getResponse().getStatus());
        }
        Matcher matcher = ACCESS_TOKEN.matcher(result.getResponse().getContentAsString());
        if (!matcher.find()) {
            throw new IllegalStateException("accessToken absent");
        }
        return new Session(matcher.group(1), refreshCookieValue(result));
    }

    public static String refreshCookieValue(MvcResult result) {
        for (String header : result.getResponse().getHeaders("Set-Cookie")) {
            if (header.startsWith(REFRESH_COOKIE + "=")) {
                return header.substring(REFRESH_COOKIE.length() + 1, header.indexOf(';'));
            }
        }
        return null;
    }

    public static String refreshSetCookieHeader(MvcResult result) {
        for (String header : result.getResponse().getHeaders("Set-Cookie")) {
            if (header.startsWith(REFRESH_COOKIE + "=")) {
                return header;
            }
        }
        return null;
    }
}
