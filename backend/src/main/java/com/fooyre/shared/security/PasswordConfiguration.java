package com.fooyre.shared.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Hachage des mots de passe : bcrypt, coût 12 (docs/architecture.md §5.5 : « Argon2 ou bcrypt
 * coût ≥ 12 »). bcrypt est fourni par Spring Security sans dépendance supplémentaire (Argon2
 * exigerait Bouncy Castle). Comparaison en temps constant assurée par l'implémentation.
 * Limite de bcrypt : 72 octets (contrôlée par les appelants).
 */
@Configuration(proxyBeanMethods = false)
public class PasswordConfiguration {

    public static final int BCRYPT_STRENGTH = 12;

    /** Longueur maximale (en octets UTF-8) d'un mot de passe accepté par bcrypt. */
    public static final int MAX_PASSWORD_BYTES = 72;

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    }
}
