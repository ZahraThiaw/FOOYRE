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

/**
 * Catégorie du catalogue d'une entreprise. {@code business_id} est affecté par
 * {@link TenantScopedEntity} depuis le contexte vérifié. Jamais supprimée : archivée.
 */
@Entity
@Table(name = "category")
public class Category extends TenantScopedEntity {

    public static final int MAX_NAME_LENGTH = 100;
    public static final int MAX_DESCRIPTION_LENGTH = 500;

    @Id
    private UUID id;

    @Column(nullable = false, length = MAX_NAME_LENGTH)
    private String name;

    @Column(length = MAX_DESCRIPTION_LENGTH)
    private String description;

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

    protected Category() {
        // JPA
    }

    public Category(String name, String description, Instant now) {
        this.id = UUID.randomUUID();
        this.status = CatalogStatus.ACTIVE;
        this.createdAt = now;
        rename(name, description, now);
    }

    /** Modifie les informations (catégorie active uniquement : contrôlé par le service). */
    public void rename(String name, String description, Instant now) {
        this.name = CatalogText.normalizeName(name);
        this.description = CatalogText.optional(description);
        this.updatedAt = now;
    }

    /** @return {@code true} si la catégorie vient d'être archivée, {@code false} si elle l'était déjà */
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

    public String getDescription() {
        return description;
    }

    public CatalogStatus getStatus() {
        return status;
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
}
