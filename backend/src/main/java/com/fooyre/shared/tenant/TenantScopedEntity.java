package com.fooyre.shared.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import java.util.UUID;

/**
 * Base OBLIGATOIRE de toute entité métier appartenant à une entreprise (CLAUDE.md §3, à partir du
 * MVP-2). {@code business_id} est renseigné automatiquement à l'insertion depuis
 * {@link TenantContext} (jamais depuis une donnée du client) et n'est jamais modifiable.
 * Conventions associées : docs/architecture.md §5.2 (« Isolation par entreprise — règles »).
 */
@MappedSuperclass
public abstract class TenantScopedEntity {

    @Column(name = "business_id", nullable = false, updatable = false)
    private UUID businessId;

    public UUID getBusinessId() {
        return businessId;
    }

    /**
     * Renseigne l'entreprise active. Refuse l'insertion sans entreprise active, ou si l'entité
     * porte déjà une autre entreprise que l'entreprise active.
     */
    @PrePersist
    protected void assignBusiness() {
        UUID active = TenantContext.requireBusinessId();
        if (businessId == null) {
            businessId = active;
        } else if (!businessId.equals(active)) {
            throw new IllegalStateException("Entité rattachée à une autre entreprise que l'entreprise active");
        }
    }
}
