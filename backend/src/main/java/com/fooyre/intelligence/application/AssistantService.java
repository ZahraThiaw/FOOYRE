package com.fooyre.intelligence.application;

import com.fooyre.business.application.BusinessSettingsService;
import com.fooyre.gestion.application.SalesCatalogService.SaleProduct;
import com.fooyre.intelligence.application.AiClient.Action;
import com.fooyre.intelligence.application.AiClient.ActionLine;
import com.fooyre.intelligence.application.AiClient.Result;
import com.fooyre.intelligence.application.AiClient.Turn;
import com.fooyre.intelligence.application.AssistantSnapshotBuilder.Snapshot;
import com.fooyre.intelligence.domain.AiUsage;
import com.fooyre.intelligence.domain.AiUsageRepository;
import com.fooyre.shared.audit.AuditAction;
import com.fooyre.shared.audit.AuditDetail;
import com.fooyre.shared.audit.AuditEvent;
import com.fooyre.shared.audit.AuditOutcome;
import com.fooyre.shared.audit.AuditService;
import com.fooyre.shared.error.ApiException;
import com.fooyre.shared.error.ErrorCode;
import com.fooyre.shared.security.Permission;
import com.fooyre.shared.tenant.TenantContext;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assistante IA (CLAUDE.md §9, D15, D18). Pour CHAQUE question, dans cet ordre :
 * <ol>
 *   <li>IA autorisée globalement ({@code AI_ENABLED}, et {@code AI_PRODUCTION_APPROVED} en prod) ;</li>
 *   <li>connexion configurée (clé côté serveur) ;</li>
 *   <li>consentement EN VIGUEUR du propriétaire, relu en base ;</li>
 *   <li>quotas de l'entreprise (requêtes du jour, budget du mois) vérifiés AVANT l'appel ;</li>
 *   <li>instantané construit selon les permissions de l'utilisatrice (aucun coût sans COST_READ…) ;</li>
 *   <li>appel du modèle, sortie structurée VALIDÉE : chaque nombre cité doit exister dans
 *       l'instantané ou la question, chaque référence doit exister — sinon réponse rejetée ;</li>
 *   <li>journalisation de l'utilisation (jetons, coût estimé, latence, statut) et de l'événement
 *       d'audit — jamais le contenu de la question ni de la réponse.</li>
 * </ol>
 * Les actions proposées ne sont JAMAIS exécutées ici : elles sont renvoyées pour confirmation
 * explicite, puis exécutées par les routes métier normales (mêmes contrôles).
 */
@Service
public class AssistantService {

    static final int MAX_HISTORY_TURNS = 6;
    static final int MAX_QUESTION_LENGTH = 1000;
    static final int MAX_LINES = 20;
    private static final Pattern NUMBER = Pattern.compile("\\d{1,3}(?:[ \\u00a0\\u202f]\\d{3})+(?:[.,]\\d+)?|\\d+(?:[.,]\\d+)?");
    private static final Pattern REF = Pattern.compile("\\{\\{(C\\d+)}}");

    public record Status(boolean globallyEnabled, boolean configured, boolean businessConsent, boolean canManageConsent,
            String termsVersion) {
    }

    public record ProposedLine(UUID productId, String productName, String unit, String quantity) {
    }

    public record Proposal(String type, UUID customerId, String customerName, List<ProposedLine> lines, String query,
            String newCustomerName, String newCustomerPhone) {
    }

    public record Answer(String answer, boolean insufficientData, Proposal proposal) {
    }

    private final AiClient client;
    private final AiSettings settings;
    private final BusinessSettingsService business;
    private final AssistantSnapshotBuilder snapshots;
    private final AiUsageRecorder usage;
    private final Clock clock;
    private final String systemPromptTemplate;

    AssistantService(AiClient client, AiSettings settings, BusinessSettingsService business,
            AssistantSnapshotBuilder snapshots, AiUsageRecorder usage, Clock clock) {
        this.client = client;
        this.settings = settings;
        this.business = business;
        this.snapshots = snapshots;
        this.usage = usage;
        this.clock = clock;
        this.systemPromptTemplate = loadPrompt();
    }

    public Status status() {
        Set<Permission> permissions = TenantContext.requireUser().permissions();
        return new Status(settings.globallyEnabled(), client.configured(), business.aiEnabledForActiveBusiness(),
                permissions.contains(Permission.AI_CONSENT_MANAGE), settings.termsVersion());
    }

    public Answer ask(String question, List<Turn> history) {
        if (!settings.globallyEnabled()) {
            throw new ApiException(ErrorCode.AI_DISABLED);
        }
        if (!client.configured()) {
            throw new ApiException(ErrorCode.AI_NOT_CONFIGURED);
        }
        if (!business.aiEnabledForActiveBusiness()) {
            throw new ApiException(ErrorCode.AI_NOT_ENABLED);
        }
        usage.ensureWithinQuota(settings);
        Set<Permission> permissions = TenantContext.requireUser().permissions();
        Snapshot snapshot = snapshots.build(permissions);
        List<Turn> turns = history == null ? List.of()
                : history.subList(Math.max(0, history.size() - MAX_HISTORY_TURNS), history.size());
        String system = systemPromptTemplate + "\n\n" + snapshot.text();

        long start = System.nanoTime();
        Result result;
        try {
            result = client.complete(system, turns, question);
        } catch (AiClient.AiRefusalException refusal) {
            usage.record("REFUSED", "refusal", 0, 0, elapsed(start), settings);
            throw new ApiException(ErrorCode.AI_INVALID_OUTPUT);
        } catch (AiClient.AiUnavailableException unavailable) {
            usage.record("UNAVAILABLE", unavailable.reason().name(), 0, 0, elapsed(start), settings);
            throw new ApiException(switch (unavailable.reason()) {
                case AUTHENTICATION -> ErrorCode.AI_CREDENTIALS_REJECTED;
                case NOT_CONFIGURED -> ErrorCode.AI_NOT_CONFIGURED;
                default -> ErrorCode.AI_UNAVAILABLE;
            });
        }
        long latency = elapsed(start);
        Answer answer;
        try {
            answer = validate(result.payload(), snapshot, question, turns, permissions);
        } catch (InvalidOutputException invalid) {
            usage.record("INVALID_OUTPUT", result.usage().stopReason(), result.usage().inputTokens(),
                    result.usage().outputTokens(), latency, settings);
            throw new ApiException(ErrorCode.AI_INVALID_OUTPUT);
        }
        usage.record("SUCCEEDED", result.usage().stopReason(), result.usage().inputTokens(),
                result.usage().outputTokens(), latency, settings);
        return answer;
    }

    // ─── Validation de la sortie ────────────────────────────────────────────────────────────────

    static final class InvalidOutputException extends RuntimeException {
        InvalidOutputException(String reason) {
            super(reason);
        }
    }

    static Answer validate(AiClient.AssistantPayload payload, Snapshot snapshot, String question, List<Turn> history,
            Set<Permission> permissions) {
        if (payload == null || payload.answer() == null || payload.answer().isBlank()) {
            throw new InvalidOutputException("réponse vide");
        }
        // Chaque nombre cité doit exister dans l'instantané, la question ou l'échange précédent.
        Set<String> allowed = new HashSet<>(numbers(snapshot.text()));
        allowed.addAll(numbers(question));
        history.forEach(turn -> allowed.addAll(numbers(turn.text())));
        for (int small = 0; small <= 12; small++) {
            allowed.add(String.valueOf(small)); // petits dénombrements (« 2 produits », « 1 vente »…)
        }
        for (String number : numbers(payload.answer())) {
            if (!allowed.contains(number)) {
                throw new InvalidOutputException("nombre non vérifiable");
            }
        }
        // Références de clients : remplacées par le nom (jamais transmis au modèle) si autorisé.
        StringBuilder text = new StringBuilder();
        Matcher matcher = REF.matcher(payload.answer());
        while (matcher.find()) {
            String ref = matcher.group(1);
            if (!snapshot.customerNames().containsKey(ref)) {
                throw new InvalidOutputException("référence client inconnue");
            }
            String name = permissions.contains(Permission.CUSTOMER_READ) ? snapshot.customerNames().get(ref) : ref;
            matcher.appendReplacement(text, Matcher.quoteReplacement(name == null ? ref : name));
        }
        matcher.appendTail(text);
        return new Answer(text.toString().strip(), payload.insufficientData(), proposal(payload.action(), snapshot));
    }

    private static Proposal proposal(Action action, Snapshot snapshot) {
        if (action == null || action.type() == null) {
            return null;
        }
        switch (action.type()) {
            case "PREPARE_SALE" -> {
                if (action.lines() == null || action.lines().isEmpty() || action.lines().size() > MAX_LINES) {
                    throw new InvalidOutputException("brouillon sans ligne");
                }
                List<ProposedLine> lines = new ArrayList<>();
                for (ActionLine line : action.lines()) {
                    SaleProduct product = snapshot.products().get(line.productRef());
                    if (product == null) {
                        throw new InvalidOutputException("produit inconnu");
                    }
                    BigDecimal quantity;
                    try {
                        quantity = new BigDecimal(line.quantity().strip().replace(',', '.'));
                    } catch (RuntimeException invalid) {
                        throw new InvalidOutputException("quantité illisible");
                    }
                    if (quantity.signum() <= 0
                            || Math.max(quantity.stripTrailingZeros().scale(), 0) > product.quantityDecimals()) {
                        throw new InvalidOutputException("quantité invalide");
                    }
                    lines.add(new ProposedLine(product.id(), product.name(), product.unit(),
                            AssistantSnapshotBuilder.plain(quantity)));
                }
                String customerRef = action.customerRef() == null ? "" : action.customerRef().strip();
                UUID customerId = customerRef.isEmpty() ? null : snapshot.customerIds().get(customerRef);
                if (!customerRef.isEmpty() && customerId == null) {
                    throw new InvalidOutputException("client inconnu");
                }
                return new Proposal("PREPARE_SALE", customerId,
                        customerId == null ? null : snapshot.customerNames().get(customerRef), lines, null, null, null);
            }
            case "SEARCH_PRODUCT" -> {
                String query = action.query() == null ? "" : action.query().strip();
                return query.isEmpty() ? null : new Proposal("SEARCH_PRODUCT", null, null, List.of(),
                        query.length() > 100 ? query.substring(0, 100) : query, null, null);
            }
            case "PREPARE_CUSTOMER" -> {
                String name = action.customerName() == null ? "" : action.customerName().strip();
                String phone = action.customerPhone() == null ? "" : action.customerPhone().strip();
                return name.isEmpty() && phone.isEmpty() ? null : new Proposal("PREPARE_CUSTOMER", null, null,
                        List.of(), null, name.length() > 150 ? name.substring(0, 150) : name,
                        phone.length() > 30 ? phone.substring(0, 30) : phone);
            }
            default -> {
                return null;
            }
        }
    }

    /** Nombres normalisés (« 12 000 » = « 12000 », « 2,5 » = « 2.5 », zéros finaux ignorés). */
    static Set<String> numbers(String text) {
        Set<String> found = new HashSet<>();
        if (text == null) {
            return found;
        }
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            String raw = matcher.group().replaceAll("[ \\u00a0\\u202f]", "").replace(',', '.');
            try {
                BigDecimal value = new BigDecimal(raw).stripTrailingZeros();
                found.add((value.scale() < 0 ? value.setScale(0) : value).toPlainString());
            } catch (NumberFormatException ignored) {
                // séquence non numérique : ignorée
            }
        }
        return found;
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static String loadPrompt() {
        try (InputStream in = new ClassPathResource("prompts/assistant-v1.md").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException missing) {
            throw new IllegalStateException("invite système introuvable", missing);
        }
    }

    /** Persistance de l'utilisation et quotas (transactions courtes, hors de l'appel réseau). */
    @Service
    public static class AiUsageRecorder {

        private final AiUsageRepository repository;
        private final AuditService audit;
        private final Clock clock;

        AiUsageRecorder(AiUsageRepository repository, AuditService audit, Clock clock) {
            this.repository = repository;
            this.audit = audit;
            this.clock = clock;
        }

        @Transactional(readOnly = true)
        public void ensureWithinQuota(AiSettings settings) {
            UUID businessId = TenantContext.requireBusinessId();
            Instant now = clock.instant();
            Instant dayStart = LocalDate.ofInstant(now, ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
            Instant monthStart = LocalDate.ofInstant(now, ZoneOffset.UTC).withDayOfMonth(1).atStartOfDay()
                    .toInstant(ZoneOffset.UTC);
            if (repository.countByBusinessIdAndCreatedAtGreaterThanEqual(businessId, dayStart)
                    >= settings.dailyRequestsPerBusiness()
                    || repository.costSince(businessId, monthStart).compareTo(settings.monthlyBudgetPerBusinessUsd()) >= 0) {
                throw new ApiException(ErrorCode.AI_QUOTA_EXCEEDED);
            }
        }

        @Transactional
        public void record(String status, String stopReason, long inputTokens, long outputTokens, long latencyMs,
                AiSettings settings) {
            UUID userId = TenantContext.requireUser().userId();
            repository.saveAndFlush(new AiUsage(userId, "assistant", status, inputTokens, outputTokens,
                    settings.estimateCostUsd(inputTokens, outputTokens), latencyMs, stopReason, clock.instant()));
            audit.record(AuditEvent.of(AuditAction.AI_QUERY,
                            "SUCCEEDED".equals(status) ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE)
                    .actor(userId)
                    .business(TenantContext.requireBusinessId())
                    .with(AuditDetail.REASON, status));
        }
    }

}
