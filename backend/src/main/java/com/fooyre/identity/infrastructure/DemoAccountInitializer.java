package com.fooyre.identity.infrastructure;

import com.fooyre.identity.application.DemoAccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Initialisation des comptes propriétaires et entreprises de démonstration — profils {@code dev}
 * et {@code demo} UNIQUEMENT (jamais en test, ni en production sans activation EXPLICITE du profil
 * {@code demo} pour une base de démonstration dédiée : {@code prod,demo}). Deux comptes INDÉPENDANTS : le principal
 * ({@code fooyre.demo.*}) et le second ({@code fooyre.demo2.*}) ; chacun est traité séparément
 * (l'absence ou l'échec de l'un n'empêche pas l'autre), par le même service idempotent
 * {@link DemoAccountService} (mot de passe jamais écrasé). Sans mot de passe configuré, rien n'est
 * créé pour le compte concerné. Les journaux ne contiennent ni mot de passe ni adresse e-mail.
 */
@Component
@Profile({"dev", "demo"})
class DemoAccountInitializer implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(DemoAccountInitializer.class);

    private final DemoAccountService demoAccounts;
    private final DemoAccountProperties properties;
    private final DemoSecondAccountProperties second;

    DemoAccountInitializer(DemoAccountService demoAccounts, DemoAccountProperties properties,
            DemoSecondAccountProperties second) {
        this.demoAccounts = demoAccounts;
        this.properties = properties;
        this.second = second;
    }

    @Override
    public void run(ApplicationArguments args) {
        provision("Compte de démonstration", "FOOYRE_DEMO_OWNER_PASSWORD", properties.ownerEmail(),
                properties.ownerFullName(), properties.ownerPassword(), properties.businessName());
        if (second != null) {
            provision("Second compte de démonstration", "FOOYRE_DEMO2_OWNER_PASSWORD", second.ownerEmail(),
                    second.ownerFullName(), second.ownerPassword(), second.businessName());
        }
    }

    private void provision(String label, String passwordVariable, String email, String fullName, String password,
            String businessName) {
        if (isBlank(email) || isBlank(fullName) || isBlank(businessName)) {
            LOGGER.warn("{} non initialisé : configuration incomplète", label);
            return;
        }
        if (isBlank(password)) {
            LOGGER.warn("{} non initialisé : {} n'est pas défini", label, passwordVariable);
            return;
        }
        try {
            DemoAccountService.Result result = demoAccounts.ensureDemoAccount(email, fullName, password, businessName);
            LOGGER.info("{} : {}", label, switch (result) {
                case CREATED -> "compte et entreprise créés";
                case BUSINESS_ADDED -> "entreprise créée pour le compte existant";
                case ALREADY_PRESENT -> "déjà présent, inchangé";
            });
        } catch (IllegalArgumentException invalid) {
            // Message sans valeur sensible (voir UserAccountService).
            LOGGER.warn("{} non initialisé : {}", label, invalid.getMessage());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
