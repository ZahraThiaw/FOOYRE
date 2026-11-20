package com.fooyre.identity.infrastructure;

import com.fooyre.identity.application.LoginThrottleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Nettoyage périodique des compteurs de connexion expirés (planificateur intégré de Spring,
 * aucune infrastructure externe). Fréquence : {@code fooyre.auth.login-throttle.cleanup-interval}.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class LoginThrottleCleanup {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoginThrottleCleanup.class);

    private final LoginThrottleService throttle;

    LoginThrottleCleanup(LoginThrottleService throttle) {
        this.throttle = throttle;
    }

    @Scheduled(initialDelayString = "${fooyre.auth.login-throttle.cleanup-interval:PT1H}",
            fixedDelayString = "${fooyre.auth.login-throttle.cleanup-interval:PT1H}")
    void purge() {
        int deleted = throttle.purgeExpired();
        if (deleted > 0) {
            LOGGER.info("Compteurs de connexion expirés supprimés : {}", deleted);
        }
    }
}
