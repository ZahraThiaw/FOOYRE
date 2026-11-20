package com.fooyre.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.stereotype.Component;

/**
 * Requête rejetée par le pare‑feu de Spring Security ({@code StrictHttpFirewall}, qui reste
 * actif et inchangé) : réponse {@code 400 VALIDATION_ERROR} au format Problem Details, avec le
 * traceId généré par le backend.
 * <p>
 * Remplace le comportement par défaut ({@code sendError(400)}), qui déclenchait une
 * redistribution vers {@code /error} et la page d'erreur générique de Spring Boot. La réponse est
 * écrite directement : aucune redistribution, donc aucune boucle possible. Le motif du rejet
 * n'est ni renvoyé au client ni journalisé (seul un message fixe l'est, avec le traceId du MDC).
 */
@Component
class ProblemRequestRejectedHandler implements RequestRejectedHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProblemRequestRejectedHandler.class);

    private final ProblemResponseWriter writer;

    ProblemRequestRejectedHandler(ProblemResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            RequestRejectedException rejection) throws IOException {
        // Message FIXE : le motif du pare‑feu peut contenir la valeur d'un en‑tête (jeton, données
        // personnelles) ou du texte destiné à falsifier les journaux ; il n'est jamais journalisé.
        LOGGER.warn("Requête rejetée par le pare-feu de sécurité");
        writer.writeRejected(request, response);
    }
}
