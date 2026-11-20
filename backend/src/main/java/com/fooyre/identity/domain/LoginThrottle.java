package com.fooyre.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Compteur d'échecs de connexion pour une clé (empreinte HMAC : jamais l'e-mail ni l'IP en
 * clair). Mis à jour uniquement par les requêtes atomiques de {@link LoginThrottleRepository}.
 */
@Entity
@Table(name = "login_throttle")
public class LoginThrottle {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "key_hash", length = 64)
    private String keyHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "key_type", nullable = false, length = 20)
    private LoginThrottleKeyType keyType;

    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    @Column(name = "window_started_at", nullable = false)
    private Instant windowStartedAt;

    @Column(name = "blocked_until")
    private Instant blockedUntil;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LoginThrottle() {
        // JPA
    }

    public LoginThrottleKeyType getKeyType() {
        return keyType;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public Instant getBlockedUntil() {
        return blockedUntil;
    }
}
