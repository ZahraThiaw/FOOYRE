package com.fooyre.gestion.application;

import com.fooyre.gestion.domain.CatalogStatus;
import java.time.Instant;
import java.util.UUID;

/** Client renvoyé par le service (aucune entité JPA ne sort de l'application). */
public record CustomerView(
        UUID id,
        String name,
        String phone,
        String email,
        String address,
        String notes,
        CatalogStatus status,
        Instant archivedAt,
        Instant createdAt,
        Instant updatedAt,
        int version) {
}
