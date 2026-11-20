package com.fooyre.shared.error;

import com.fooyre.shared.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/**
 * Construit les réponses Problem Details (RFC 9457) du contrat FOOYRE :
 * {@code type}, {@code title}, {@code status}, {@code detail}, {@code instance}, {@code code},
 * {@code traceId} et, pour la validation, {@code errors[{field, code, message}]}.
 * Les textes viennent de {@code i18n/messages_fr.properties} ; aucun message technique
 * (exception, SQL, trace de pile) n'est jamais repris.
 */
@Component
public class ApiProblemFactory {

    /** Français seul au lancement (D16) ; la langue de l'utilisateur sera prise en compte plus tard. */
    static final Locale MESSAGES_LOCALE = Locale.FRENCH;

    private static final String TYPE_BASE = "https://fooyre.app/problems/";

    private final MessageSource messageSource;

    public ApiProblemFactory(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    public ProblemDetail create(ErrorCode code, HttpServletRequest request) {
        return create(code.status(), code, request);
    }

    /**
     * Statut sans code stable documenté (ex. 405, 415) : {@code code} est {@code null}, le corps
     * reste un Problem Details sans propriété {@code code} — le frontend se rabat alors sur le
     * message associé au statut.
     */
    public ProblemDetail create(HttpStatusCode status, ErrorCode code, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        String key = code != null ? code.name() : "HTTP_" + status.value();
        problem.setType(URI.create(TYPE_BASE + key.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setTitle(message("error." + key + ".title"));
        problem.setDetail(message("error." + key + ".detail"));
        problem.setInstance(instanceOf(request));
        if (code != null) {
            problem.setProperty("code", code.name());
        }
        problem.setProperty("traceId", TraceIdFilter.currentTraceId(request));
        if (code == ErrorCode.VALIDATION_ERROR) {
            // Toujours présent avec VALIDATION_ERROR ; vide si aucun champ n'est identifiable.
            problem.setProperty("errors", List.of());
        }
        return problem;
    }

    public ProblemDetail validation(List<ApiFieldError> errors, HttpServletRequest request) {
        ProblemDetail problem = create(ErrorCode.VALIDATION_ERROR, request);
        problem.setProperty("errors", errors);
        return problem;
    }

    /**
     * En‑têtes HTTP associés à un Problem Details — point UNIQUE pour toutes les réponses
     * d'erreur (filtres de sécurité et Spring MVC). 401 : {@code WWW-Authenticate: Bearer}
     * (RFC 9110 §15.5.2, schéma de l'ADR‑04) ; aucun autre statut ne reçoit cet en‑tête.
     */
    public HttpHeaders headersFor(ProblemDetail problem) {
        HttpHeaders headers = new HttpHeaders();
        if (problem.getStatus() == HttpStatus.UNAUTHORIZED.value()) {
            headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return headers;
    }

    /** Chemin de la requête, ou {@code null} s'il n'est pas un URI valide (jamais d'exception ici). */
    private static URI instanceOf(HttpServletRequest request) {
        try {
            return URI.create(request.getRequestURI());
        } catch (IllegalArgumentException invalidUri) {
            return null;
        }
    }

    public ApiFieldError fieldError(String field, FieldErrorCode code) {
        return new ApiFieldError(field, code, message("field-error." + code.name()));
    }

    private String message(String key) {
        return messageSource.getMessage(key, null, MESSAGES_LOCALE);
    }
}
