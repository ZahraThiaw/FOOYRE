package com.fooyre.identity;

import static com.fooyre.support.TestAccounts.loginRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fooyre.IntegrationTest;
import com.fooyre.identity.application.DemoAccountService;
import com.fooyre.identity.application.DemoAccountService.Result;
import com.fooyre.support.TestAccounts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Compte de démonstration : idempotence réelle sur PostgreSQL, absence hors profil dev. */
@IntegrationTest
class DemoAccountIntegrationTest {

    private static final String FIRST_PASSWORD = "Premier-mot-de-passe-1";

    @Autowired
    private DemoAccountService demoAccounts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ApplicationContext context;

    @Test
    void initialisationIsIdempotentAndNeverOverwritesThePassword() throws Exception {
        String email = TestAccounts.uniqueEmail("demo");

        assertThat(demoAccounts.ensureDemoAccount(email, "Administrateur Démo", FIRST_PASSWORD, "Commerce Démo Test"))
                .isEqualTo(Result.CREATED);
        String hashAfterFirstRun = passwordHash(email);

        assertThat(demoAccounts.ensureDemoAccount(email, "Autre nom", "Second-mot-de-passe-2", "Autre entreprise"))
                .isEqualTo(Result.ALREADY_PRESENT);
        assertThat(demoAccounts.ensureDemoAccount(email.toUpperCase(), "Autre nom", "Second-mot-de-passe-2", "Autre"))
                .isEqualTo(Result.ALREADY_PRESENT);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE lower(email) = ?", Integer.class, email))
                .isEqualTo(1);
        assertThat(passwordHash(email)).isEqualTo(hashAfterFirstRun);
        assertThat(jdbc.queryForObject("SELECT full_name FROM app_user WHERE email = ?", String.class, email))
                .isEqualTo("Administrateur Démo");
        assertThat(jdbc.queryForList("""
                SELECT b.name || ':' || m.role || ':' || m.status FROM business_membership m
                JOIN business b ON b.id = m.business_id JOIN app_user u ON u.id = m.user_id
                WHERE u.email = ?""", String.class, email))
                .containsExactly("Commerce Démo Test:BUSINESS_OWNER:ACTIVE");

        mvc.perform(loginRequest(email, FIRST_PASSWORD)).andExpect(status().isOk());
        mvc.perform(loginRequest(email, "Second-mot-de-passe-2")).andExpect(status().isUnauthorized());
    }

    @Test
    void anInvalidPasswordCreatesNothing() {
        String email = TestAccounts.uniqueEmail("demo-court");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> demoAccounts.ensureDemoAccount(email, "Administrateur Démo", "court", "Entreprise"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("court\"");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE email = ?", Integer.class, email)).isZero();
    }

    @Test
    void noDemoAccountAndNoInitializerOutsideTheDevProfile() {
        assertThat(context.getBeanNamesForType(
                org.springframework.boot.ApplicationRunner.class)).noneMatch(name -> name.toLowerCase().contains("demo"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE email = 'demo@fooyre.local'", Integer.class))
                .isZero();
    }

    private String passwordHash(String email) {
        return jdbc.queryForObject("SELECT password_hash FROM app_user WHERE email = ?", String.class, email);
    }
}
