package com.fooyre.shared.audit;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Événement d'audit à enregistrer. Les métadonnées passent obligatoirement par
 * {@link #with(AuditDetail, String)} : liste blanche de clés et format contrôlé des valeurs.
 * L'entreprise n'est jamais lue d'une requête client : l'appelant la fournit depuis le contexte
 * vérifié (jeton, appartenance) ou la ressource elle‑même.
 */
public final class AuditEvent {

    private final AuditAction action;
    private final AuditOutcome outcome;
    private UUID actorUserId;
    private UUID businessId;
    private String targetType;
    private UUID targetId;
    private final Map<AuditDetail, String> details = new EnumMap<>(AuditDetail.class);

    private AuditEvent(AuditAction action, AuditOutcome outcome) {
        this.action = Objects.requireNonNull(action, "action");
        this.outcome = Objects.requireNonNull(outcome, "outcome");
    }

    public static AuditEvent of(AuditAction action, AuditOutcome outcome) {
        return new AuditEvent(action, outcome);
    }

    public AuditEvent actor(UUID userId) {
        this.actorUserId = userId;
        return this;
    }

    public AuditEvent business(UUID id) {
        this.businessId = id;
        return this;
    }

    /** Ressource concernée : type codé (ex. {@code USER}, {@code SESSION}) et identifiant. */
    public AuditEvent target(AuditTarget type, UUID id) {
        this.targetType = Objects.requireNonNull(type, "type").name();
        this.targetId = Objects.requireNonNull(id, "id");
        return this;
    }

    /**
     * Ajoute une métadonnée autorisée.
     *
     * @throws IllegalArgumentException si la valeur ne respecte pas le format de la clé (le
     *                                  message ne reprend jamais la valeur refusée)
     */
    public AuditEvent with(AuditDetail key, String value) {
        if (!key.accepts(value)) {
            throw new IllegalArgumentException("Valeur refusée pour la métadonnée d'audit " + key);
        }
        details.put(key, value);
        return this;
    }

    public AuditAction action() {
        return action;
    }

    public AuditOutcome outcome() {
        return outcome;
    }

    public UUID actorUserId() {
        return actorUserId;
    }

    public UUID businessId() {
        return businessId;
    }

    public String targetType() {
        return targetType;
    }

    public UUID targetId() {
        return targetId;
    }

    public Map<AuditDetail, String> details() {
        return Collections.unmodifiableMap(details);
    }
}
