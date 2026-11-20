package com.fooyre.shared.security;

import com.fooyre.shared.error.ApiProblemFactory;
import com.fooyre.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Écrit une réponse Problem Details depuis la chaîne de filtres (hors Spring MVC). Les en‑têtes
 * (ex. {@code WWW-Authenticate} pour 401) viennent de {@link ApiProblemFactory#headersFor}.
 * N'écrit jamais dans une réponse déjà engagée.
 */
@Component
class ProblemResponseWriter {

    private final ApiProblemFactory problems;
    private final JsonMapper jsonMapper;

    ProblemResponseWriter(ApiProblemFactory problems, JsonMapper jsonMapper) {
        this.problems = problems;
        this.jsonMapper = jsonMapper;
    }

    void write(ErrorCode code, HttpServletRequest request, HttpServletResponse response) throws IOException {
        write(problems.create(code, request), response);
    }

    /**
     * Requête rejetée par le pare‑feu : le chemin suspect n'est PAS recopié dans la réponse
     * ({@code instance} absent).
     */
    void writeRejected(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ProblemDetail problem = problems.create(ErrorCode.VALIDATION_ERROR, request);
        problem.setInstance(null);
        write(problem, response);
    }

    private void write(ProblemDetail problem, HttpServletResponse response) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(problem.getStatus());
        problems.headersFor(problem).forEach((name, values) -> values.forEach(value -> response.setHeader(name, value)));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), problem);
    }
}
