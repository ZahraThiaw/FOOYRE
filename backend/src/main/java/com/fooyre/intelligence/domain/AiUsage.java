package com.fooyre.intelligence.domain;

import com.fooyre.shared.tenant.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Un appel (ou une tentative refusée) à l'IA : jetons, coût estimé, latence, statut. Jamais le contenu. */
@Entity
@Table(name = "ai_usage")
public class AiUsage extends TenantScopedEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 60)
    private String model;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    @Column(name = "estimated_cost_usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal estimatedCostUsd;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "stop_reason", length = 30)
    private String stopReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AiUsage() {
        // JPA
    }

    public AiUsage(UUID userId, String model, String status, long inputTokens, long outputTokens,
            BigDecimal estimatedCostUsd, long latencyMs, String stopReason, Instant now) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.model = model;
        this.status = status;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.estimatedCostUsd = estimatedCostUsd;
        this.latencyMs = latencyMs;
        this.stopReason = stopReason == null || stopReason.length() <= 30 ? stopReason : stopReason.substring(0, 30);
        this.createdAt = now;
    }
}
