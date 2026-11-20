package com.fooyre.business.domain;

import com.fooyre.shared.security.BusinessRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Appartenance d'un utilisateur à une entreprise, avec son rôle dans CETTE entreprise (ADR-05).
 * L'utilisateur est référencé par son identifiant : le module business ne connaît pas les comptes.
 */
@Entity
@Table(name = "business_membership")
public class BusinessMembership {

    @Id
    private UUID id;

    @Column(name = "business_id", nullable = false, updatable = false)
    private UUID businessId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private BusinessRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MembershipStatus status;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private int version;

    protected BusinessMembership() {
        // JPA
    }

    public BusinessMembership(UUID businessId, UUID userId, BusinessRole role, Instant now) {
        this.id = UUID.randomUUID();
        this.businessId = Objects.requireNonNull(businessId, "businessId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.role = Objects.requireNonNull(role, "role");
        this.status = MembershipStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getBusinessId() {
        return businessId;
    }

    public UUID getUserId() {
        return userId;
    }

    public BusinessRole getRole() {
        return role;
    }

    public MembershipStatus getStatus() {
        return status;
    }

    public boolean isActive() {
        return status == MembershipStatus.ACTIVE;
    }
}
