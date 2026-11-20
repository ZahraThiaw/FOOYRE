package com.fooyre.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Identifiant de corrélation de chaque requête (support, journaux).
 * <ul>
 *   <li>Toujours GÉNÉRÉ par le backend ({@link UUID#randomUUID()}, fondé sur un générateur
 *       cryptographiquement sûr) ; aucun identifiant fourni par le client n'est accepté
 *       (décision B1 ; l'intégration avec le proxy est étudiée en B2).</li>
 *   <li>Placé dans le MDC ({@value #MDC_KEY}), dans un attribut de requête, dans l'en‑tête de
 *       réponse {@value #RESPONSE_HEADER} et dans les Problem Details.</li>
 *   <li>Retiré du MDC dans un bloc {@code finally}, même en cas d'exception.</li>
 * </ul>
 * Placé avant la chaîne Spring Security pour que les réponses 401/403 portent aussi le traceId.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "traceId";
    public static final String RESPONSE_HEADER = "X-Trace-Id";
    static final String REQUEST_ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String traceId = UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ATTRIBUTE, traceId);
        response.setHeader(RESPONSE_HEADER, traceId);
        MDC.put(MDC_KEY, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** TraceId de la requête en cours (attribut de requête, sinon MDC), ou {@code null}. */
    public static String currentTraceId(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ATTRIBUTE);
        return value instanceof String traceId ? traceId : MDC.get(MDC_KEY);
    }
}
