package com.fooyre.shared.config;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * Messages du backend (ADR‑13, D16) : {@code i18n/messages_fr.properties}, UTF‑8.
 * Déclaration explicite : l'autoconfiguration de Spring Boot ne s'active qu'en présence d'un
 * fichier de base {@code messages.properties}, que le projet n'utilise pas (français seul au
 * lancement, une langue = un fichier {@code messages_<code>.properties}).
 */
@Configuration(proxyBeanMethods = false)
public class MessagesConfiguration {

    @Bean
    MessageSource messageSource() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("i18n/messages");
        messageSource.setDefaultEncoding(StandardCharsets.UTF_8.name());
        messageSource.setFallbackToSystemLocale(false);
        // Langue par défaut (D16) : une locale non prise en charge retombe sur le français.
        messageSource.setDefaultLocale(Locale.FRENCH);
        messageSource.setUseCodeAsDefaultMessage(false);
        return messageSource;
    }
}
