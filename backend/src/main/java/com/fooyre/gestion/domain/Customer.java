package com.fooyre.gestion.domain;

import com.fooyre.shared.tenant.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Client d'une entreprise (MVP-4A). {@code business_id} est affecté par
 * {@link TenantScopedEntity} depuis le contexte vérifié. Jamais supprimé : archivé.
 * Données PERSONNELLES : jamais dans les journaux techniques ni dans l'audit.
 */
@Entity
@Table(name = "customer")
public class Customer extends TenantScopedEntity {

    public static final int MAX_NAME_LENGTH = 150;
    public static final int MAX_PHONE_LENGTH = 30;
    public static final int MAX_EMAIL_LENGTH = 254;
    public static final int MAX_ADDRESS_LENGTH = 500;
    public static final int MAX_NOTES_LENGTH = 2000;

    /** Informations saisies, déjà normalisées et validées par le service. */
    public record Details(String name, String phone, String email, String address, String notes) {
    }

    @Id
    private UUID id;

    @Column(length = MAX_NAME_LENGTH)
    private String name;

    @Column(length = MAX_PHONE_LENGTH)
    private String phone;

    /** Calculé par la base (colonne générée) : lecture seule. */
    @Column(name = "phone_digits", insertable = false, updatable = false)
    private String phoneDigits;

    @Column(length = MAX_EMAIL_LENGTH)
    private String email;

    @Column(length = MAX_ADDRESS_LENGTH)
    private String address;

    @Column(length = MAX_NOTES_LENGTH)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CatalogStatus status;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** {@code null} tant que l'entité n'est pas enregistrée : Spring Data la reconnaît comme nouvelle. */
    @Version
    private Integer version;

    @Column(name = "idempotency_key", updatable = false)
    private UUID idempotencyKey;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_hash", length = 64, updatable = false)
    private String requestHash;

    protected Customer() {
        // JPA
    }

    public Customer(Details details, UUID idempotencyKey, String requestHash, Instant now) {
        this.id = UUID.randomUUID();
        this.status = CatalogStatus.ACTIVE;
        this.createdAt = now;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        update(details, now);
    }

    /** Modifie les informations (client actif uniquement : contrôlé par le service). */
    public final void update(Details details, Instant now) {
        this.name = details.name();
        this.phone = details.phone();
        this.email = details.email();
        this.address = details.address();
        this.notes = details.notes();
        this.updatedAt = now;
    }

    /** @return {@code true} si le client vient d'être archivé, {@code false} s'il l'était déjà */
    public boolean archive(Instant now) {
        if (status == CatalogStatus.ARCHIVED) {
            return false;
        }
        this.status = CatalogStatus.ARCHIVED;
        this.archivedAt = now;
        this.updatedAt = now;
        return true;
    }

    public boolean isActive() {
        return status == CatalogStatus.ACTIVE;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getPhone() {
        return phone;
    }

    public String getEmail() {
        return email;
    }

    public String getAddress() {
        return address;
    }

    public String getNotes() {
        return notes;
    }

    public CatalogStatus getStatus() {
        return status;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public int getVersion() {
        return version == null ? 0 : version;
    }

    public String getRequestHash() {
        return requestHash;
    }
}
