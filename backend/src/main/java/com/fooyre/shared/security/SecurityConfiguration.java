package com.fooyre.shared.security;

import jakarta.servlet.DispatcherType;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Chaîne de sécurité de l'API (ADR-04).
 * <ul>
 *   <li>Refus par défaut : toute route exige une authentification, sauf les sondes de santé et
 *       les trois routes d'authentification à cookie ou publiques ({@code POST /api/v1/auth/login},
 *       {@code /refresh}, {@code /logout}).</li>
 *   <li>Authentification des routes métier UNIQUEMENT par {@code Authorization: Bearer} (JWT HS256),
 *       jamais par cookie ni paramètre d'URL ; le jeton est revérifié en base à chaque requête
 *       ({@link VerifiedJwtAuthenticationConverter}).</li>
 *   <li>Sans état : aucune session HTTP. Le seul cookie est le refresh token, limité à
 *       {@code Path=/api/v1/auth}.</li>
 *   <li>CSRF de Spring Security désactivé — choix lié au mécanisme retenu : les routes métier ne
 *       lisent aucun cookie (un formulaire tiers ne peut pas fournir le Bearer) ; les routes qui
 *       lisent le cookie sont protégées par {@code SameSite=Strict}, {@code Path} restreint et le
 *       contrôle {@code Origin} + {@code X-Requested-With: fooyre} du module identity
 *       ({@code 403 CSRF_CHECK_FAILED}).</li>
 *   <li>Pas de formulaire de connexion, pas d'authentification Basic, pas de page de déconnexion.</li>
 *   <li>Réponses 401/403 au format Problem Details ; {@code @PreAuthorize} sur chaque endpoint.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfiguration {

    /** Routes d'authentification accessibles sans jeton d'accès (POST uniquement). */
    static final String[] PUBLIC_AUTH_ROUTES = {"/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout"};

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
            ProblemAuthenticationEntryPoint authenticationEntryPoint,
            ProblemAccessDeniedHandler accessDeniedHandler,
            AccessTokenService accessTokens,
            VerifiedJwtAuthenticationConverter authenticationConverter) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize
                        // Redistribution interne d'une erreur déjà produite : conserve son statut.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_AUTH_ROUTES).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(bearerTokenResolver())
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(jwt -> jwt
                                .decoder(accessTokens.decoder())
                                .jwtAuthenticationConverter(authenticationConverter)))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    /**
     * Jeton lu uniquement dans l'en‑tête {@code Authorization} (jamais en paramètre ni dans le
     * corps). Les routes publiques d'authentification l'ignorent : un jeton d'accès expiré envoyé
     * avec {@code /auth/refresh} ne doit pas empêcher le renouvellement.
     */
    static BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver header = new DefaultBearerTokenResolver();
        Set<String> ignored = Set.of(PUBLIC_AUTH_ROUTES);
        return request -> ignored.contains(request.getRequestURI()) ? null : header.resolve(request);
    }

    /** Rejets du pare‑feu (StrictHttpFirewall, inchangé) → Problem Details 400 (ProblemRequestRejectedHandler). */
    @Bean
    WebSecurityCustomizer requestRejectedHandlerCustomizer(ProblemRequestRejectedHandler requestRejectedHandler) {
        return web -> web.requestRejectedHandler(requestRejectedHandler);
    }
}
