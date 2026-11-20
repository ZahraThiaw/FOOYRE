package com.fooyre.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Refresh token émis (ADR-04). Seul le haché SHA-256 est stocké ; le jeton en clair n'existe que
 * dans le cookie {@code HttpOnly} du navigateur. Les jetons d'une même connexion forment une
 * famille ({@code familyId} = identifiant de session {@code sid}) : rotation à chaque usage,
 * révocation de toute la famille à la déconnexion ou en cas de réutilisation.
 */
@Entity
@Table(name = "refresh_token")
public class RefreshToken {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "business_id", updatable = false)
    private UUID businessId;

    @Column(name = "membership_id", updatable = false)
    private UUID membershipId;

    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "replaced_by")
    private UUID replacedBy;

    @Column(name = "replaced_at")
    private Instant replacedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revocation_reason", length = 30)
    private RevocationReason revocationReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RefreshToken() {
        // JPA
    }

    public RefreshToken(UUID userId, UUID businessId, UUID membershipId, UUID familyId, String tokenHash,
            Instant now, Duration ttl) {
        this.id = UUID.randomUUID();
        this.userId = Objects.requireNonNull(userId, "userId");
        this.businessId = businessId;
        this.membershipId = membershipId;
        this.familyId = Objects.requireNonNull(familyId, "familyId");
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        this.createdAt = now;
        this.expiresAt = now.plus(ttl);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isReplaced() {
        return replacedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public void markReplacedBy(RefreshToken successor, Instant now) {
        this.replacedBy = successor.getId();
        this.replacedAt = now;
    }

    public void revoke(RevocationReason reason, Instant now) {
        if (revokedAt == null) {
            this.revokedAt = now;
            this.revocationReason = Objects.requireNonNull(reason, "reason");
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getBusinessId() {
        return businessId;
    }

    public UUID getMembershipId() {
        return membershipId;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public Instant getReplacedAt() {
        return replacedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public RevocationReason getRevocationReason() {
        return revocationReason;
    }
}
