package com.fooyre.business;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fooyre.IntegrationTest;
import com.fooyre.business.application.MembershipService;
import com.fooyre.shared.security.AccessTokenClaims;
import com.fooyre.shared.security.AccessTokenService;
import com.fooyre.shared.security.BusinessRole;
import com.fooyre.support.TestAccounts;
import com.fooyre.support.TestAccounts.Account;
import com.fooyre.support.TestAccounts.Session;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Contexte entreprise (CLAUDE.md §3) : l'entreprise vient uniquement du jeton vérifié ; un
 * identifiant d'entreprise fourni par le client ne donne jamais accès à une autre entreprise.
 */
@IntegrationTest
class BusinessAccessIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private MembershipService memberships;

    @Autowired
    private AccessTokenService accessTokens;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void anOwnerReadsTheBusinessOfItsMembership() throws Exception {
        Account owner = accounts.owner("autorisee");
        Session session = TestAccounts.login(mvc, owner.email());
        mvc.perform(get("/api/v1/business").header("Authorization", session.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(owner.membership().businessId().toString()))
                .andExpect(jsonPath("$.name").value("Entreprise autorisee"))
                .andExpect(jsonPath("$.currency").value("XOF"))
                .andExpect(jsonPath("$.timezone").value("Africa/Dakar"));
    }

    @Test
    void businessIdSuppliedByTheClientIsIgnored() throws Exception {
        Account a = accounts.owner("tenant-a");
        Account b = accounts.owner("tenant-b");
        Session session = TestAccounts.login(mvc, a.email());
        String other = b.membership().businessId().toString();

        mvc.perform(get("/api/v1/business").param("businessId", other)
                        .header("X-Business-Id", other)
                        .header("X-Expected-Business-Id", other)
                        .header("Authorization", session.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(a.membership().businessId().toString()));
        mvc.perform(get("/api/v1/business/" + other).header("Authorization", session.bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aValidlySignedTokenClaimingAnotherBusinessIsRefused() throws Exception {
        Account a = accounts.owner("jeton-a");
        Account b = accounts.owner("jeton-b");
        Session session = TestAccounts.login(mvc, a.email());
        UUID sessionId = jdbc.queryForObject(
                "SELECT family_id FROM refresh_token WHERE token_hash = encode(sha256(?::bytea), 'hex')",
                UUID.class, session.refreshToken());

        // Entreprise de B avec l'appartenance de A.
        String wrongBusiness = accessTokens.issue(new AccessTokenClaims(a.userId(), sessionId,
                b.membership().businessId(), a.membership().membershipId(), BusinessRole.BUSINESS_OWNER, 0)).value();
        // Appartenance de B présentée par A.
        String foreignMembership = accessTokens.issue(new AccessTokenClaims(a.userId(), sessionId,
                b.membership().businessId(), b.membership().membershipId(), BusinessRole.BUSINESS_OWNER, 0)).value();

        for (String token : new String[] {wrongBusiness, foreignMembership}) {
            mvc.perform(get("/api/v1/business").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
            mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void aRoleDifferentFromTheMembershipIsRefused() throws Exception {
        Account owner = accounts.owner("role-modifie");
        Session session = TestAccounts.login(mvc, owner.email());
        jdbc.update("UPDATE business_membership SET role = 'EMPLOYEE' WHERE id = ?", owner.membership().membershipId());
        mvc.perform(get("/api/v1/business").header("Authorization", session.bearer()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aUserWithoutBusinessHasNoActiveContextAndNoBusinessAccess() throws Exception {
        Account lonely = accounts.withoutBusiness("sans-entreprise");
        Session session = TestAccounts.login(mvc, lonely.email());

        mvc.perform(get("/api/v1/me").header("Authorization", session.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeBusiness").isEmpty())
                .andExpect(jsonPath("$.activeMembership").isEmpty())
                .andExpect(jsonPath("$.permissions.length()").value(0))
                .andExpect(jsonPath("$.businesses.length()").value(0));
        mvc.perform(get("/api/v1/business").header("Authorization", session.bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void withSeveralMembershipsNoBusinessIsChosenForTheUser() throws Exception {
        Account owner = accounts.owner("plusieurs");
        memberships.createBusinessWithOwner("Seconde entreprise plusieurs", owner.userId());
        Session session = TestAccounts.login(mvc, owner.email());

        mvc.perform(get("/api/v1/me").header("Authorization", session.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeBusiness").isEmpty())
                .andExpect(jsonPath("$.activeMembership").isEmpty())
                .andExpect(jsonPath("$.businesses.length()").value(2));
    }

    @Test
    void businessRouteRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/business")).andExpect(status().isUnauthorized());
    }
}
