package com.fooyre.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.databind.DatabindException;

/**
 * Traduction centralisée des exceptions en Problem Details (docs/api-specification.md §1.3).
 * Les messages des exceptions ne sont jamais renvoyés ; les erreurs 5xx sont journalisées
 * avec leur trace de pile (côté serveur uniquement) et le traceId (MDC).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ApiProblemFactory problems;

    public GlobalExceptionHandler(ApiProblemFactory problems) {
        this.problems = problems;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleInvalidBody(MethodArgumentNotValidException ex,
            HttpServletRequest request) {
        List<ApiFieldError> errors = new ArrayList<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.add(problems.fieldError(fieldError.getField(), FieldErrorCodes.of(fieldError)));
        }
        return respond(problems.validation(errors, request));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ProblemDetail> handleInvalidParameters(HandlerMethodValidationException ex,
            HttpServletRequest request) {
        List<ApiFieldError> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String name = result.getMethodParameter().getParameterName();
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                FieldErrorCode code = error instanceof FieldError fieldError
                        ? FieldErrorCodes.of(fieldError)
                        : FieldErrorCodes.of(constraintName(error), result.getArgument(), error.getArguments());
                errors.add(problems.fieldError(name, code));
            }
        });
        return respond(problems.validation(errors, request));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
            HttpServletRequest request) {
        return respond(problems.validation(
                List.of(problems.fieldError(ex.getName(), FieldErrorCode.INVALID_FORMAT)), request));
    }

    /** Corps illisible : JSON mal formé ou valeur incompatible avec le type attendu. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadableBody(HttpMessageNotReadableException ex,
            HttpServletRequest request) {
        List<ApiFieldError> errors = new ArrayList<>();
        if (ex.getCause() instanceof DatabindException databind) {
            String path = jsonPath(databind);
            if (!path.isEmpty()) {
                errors.add(problems.fieldError(path, FieldErrorCode.INVALID_FORMAT));
            }
        }
        return respond(problems.validation(errors, request));
    }

    /** Paramètre de requête obligatoire absent : erreur de champ REQUIRED sur ce paramètre. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ProblemDetail> handleMissingParameter(MissingServletRequestParameterException ex,
            HttpServletRequest request) {
        return respond(problems.validation(
                List.of(problems.fieldError(ex.getParameterName(), FieldErrorCode.REQUIRED)), request));
    }

    /** En‑tête obligatoire absent : erreur de champ REQUIRED sur cet en‑tête. */
    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ProblemDetail> handleMissingHeader(MissingRequestHeaderException ex,
            HttpServletRequest request) {
        return respond(problems.validation(
                List.of(problems.fieldError(ex.getHeaderName(), FieldErrorCode.REQUIRED)), request));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApiException(ApiException ex, HttpServletRequest request) {
        ProblemDetail problem = problems.create(ex.code(), request);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(problem.getStatus()).headers(problems.headersFor(problem));
        if (ex.retryAfterSeconds() != null) {
            response.header(org.springframework.http.HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()));
        }
        return response.body(problem);
    }

    @ExceptionHandler(FieldValidationException.class)
    ResponseEntity<ProblemDetail> handleFieldValidation(FieldValidationException ex, HttpServletRequest request) {
        List<ApiFieldError> errors = new ArrayList<>();
        ex.errors().forEach((field, code) -> errors.add(problems.fieldError(field, code)));
        return respond(problems.validation(errors, request));
    }

    /** Écriture concurrente détectée par la version JPA : {@code 409 OPTIMISTIC_LOCK}. */
    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleOptimisticLock(Exception ex, HttpServletRequest request) {
        return respond(problems.create(ErrorCode.OPTIMISTIC_LOCK, request));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return respond(problems.create(ErrorCode.FORBIDDEN, request));
    }

    /**
     * Toute autre exception. Les exceptions Spring MVC qui portent leur statut 4xx
     * ({@link ErrorResponse} : 404 route inconnue, 405, 415…) gardent ce statut ; tout le reste
     * devient {@code 500 INTERNAL_ERROR}, journalisé côté serveur uniquement.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleOther(Exception ex, HttpServletRequest request) {
        if (ex instanceof ErrorResponse errorResponse && errorResponse.getStatusCode().is4xxClientError()) {
            HttpStatusCode status = errorResponse.getStatusCode();
            return respond(problems.create(status, codeFor(status), request));
        }
        LOGGER.error("Erreur interne non gérée", ex);
        return respond(problems.create(ErrorCode.INTERNAL_ERROR, request));
    }

    private static ErrorCode codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> ErrorCode.VALIDATION_ERROR;
            case 401 -> ErrorCode.SESSION_REVOKED;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.NOT_FOUND;
            default -> null;
        };
    }

    private static String jsonPath(DatabindException ex) {
        StringBuilder path = new StringBuilder();
        for (var reference : ex.getPath()) {
            if (reference.getPropertyName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(reference.getPropertyName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.toString();
    }

    /** Nom court de la contrainte (dernier code de résolution, ex. « NotBlank »). */
    private static String constraintName(MessageSourceResolvable error) {
        String[] codes = error.getCodes();
        return codes == null || codes.length == 0 ? null : codes[codes.length - 1];
    }

    private ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).headers(problems.headersFor(problem)).body(problem);
    }
}
