package com.fooyre.shared.error;

/**
 * Erreur métier ou de sécurité associée à un code stable du contrat API. Traduite en Problem
 * Details par {@link GlobalExceptionHandler} ; aucun message technique n'est jamais renvoyé.
 * {@code retryAfterSeconds} (facultatif) devient l'en‑tête {@code Retry-After} (429).
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Long retryAfterSeconds;

    public ApiException(ErrorCode code) {
        this(code, null);
    }

    public ApiException(ErrorCode code, Long retryAfterSeconds) {
        super(code.name());
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public ErrorCode code() {
        return code;
    }

    /** Délai avant nouvelle tentative, en secondes, ou {@code null}. */
    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
