package com.fooyre.shared.audit;

import com.fooyre.shared.security.KeyedHasher;
import com.fooyre.shared.web.TraceIdFilter;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/**
 * Journal d'audit (table {@code audit_log}, insertion seule).
 * <ul>
 *   <li>{@link #record} : DANS la transaction de l'opération (obligatoire) — l'événement n'existe
 *       que si l'opération est validée ; à utiliser pour les succès.</li>
 *   <li>{@link #recordIndependently} : dans une transaction séparée — l'événement subsiste même si
 *       l'opération échoue ensuite (échecs de connexion, refus) ; à utiliser pour les échecs.</li>
 * </ul>
 * Données enregistrées : action, résultat, acteur, entreprise, ressource, horodatage UTC,
 * traceId, empreinte HMAC de l'adresse IP et métadonnées de la liste blanche
 * {@link AuditDetail}. Jamais : mot de passe, haché, jeton, cookie, secret, e-mail, nom, contenu
 * de requête.
 */
@Service
public class AuditService {

    static final String IP_PURPOSE = "audit-ip";

    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;
    private final KeyedHasher hasher;
    private final Clock clock;
    private final EntityManager entityManager;

    AuditService(JdbcTemplate jdbc, JsonMapper jsonMapper, KeyedHasher hasher, Clock clock, EntityManager entityManager) {
        this.entityManager = entityManager;
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.hasher = hasher;
        this.clock = clock;
    }

    /** Enregistre l'événement dans la transaction courante (qui doit exister). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEvent event) {
        // Les entités JPA référencées (entreprise, compte) créées dans cette transaction doivent
        // exister en base avant l'insertion JDBC (clés étrangères).
        entityManager.flush();
        insert(event);
    }

    /** Enregistre l'événement dans sa propre transaction, indépendante de l'opération. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependently(AuditEvent event) {
        insert(event);
    }

    private void insert(AuditEvent event) {
        Map<String, String> details = new LinkedHashMap<>();
        event.details().forEach((key, value) -> details.put(key.name(), value));
        jdbc.update("""
                INSERT INTO audit_log (id, occurred_at, business_id, actor_user_id, action, outcome,
                                       target_type, target_id, trace_id, ip_hash, details)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)""",
                UUID.randomUUID(),
                Timestamp.from(Instant.now(clock)),
                event.businessId(),
                event.actorUserId(),
                event.action().name(),
                event.outcome().name(),
                event.targetType(),
                event.targetId(),
                currentTraceId(),
                currentIpHash(),
                jsonMapper.writeValueAsString(details));
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }

    private static String currentTraceId() {
        HttpServletRequest request = currentRequest();
        return request != null ? TraceIdFilter.currentTraceId(request) : MDC.get(TraceIdFilter.MDC_KEY);
    }

    /** Adresse IP de connexion (jamais un en‑tête X-Forwarded-For non vérifié), sous forme d'empreinte. */
    private String currentIpHash() {
        HttpServletRequest request = currentRequest();
        return request == null || request.getRemoteAddr() == null ? null : hasher.hash(IP_PURPOSE, request.getRemoteAddr());
    }
}
