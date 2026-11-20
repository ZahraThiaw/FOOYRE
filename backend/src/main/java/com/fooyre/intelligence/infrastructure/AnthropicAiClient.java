package com.fooyre.intelligence.infrastructure;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.fooyre.intelligence.application.AiClient;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Appel de Claude via le SDK Java OFFICIEL d'Anthropic. La clé n'existe que côté serveur
 * ({@code ANTHROPIC_API_KEY}) : jamais journalisée, jamais incluse dans un message d'erreur.
 * Fournisseur réel uniquement si {@code AI_PROVIDER} vaut {@code anthropic} (ou est vide) : toute
 * autre valeur (ex. {@code fake}) désactive les appels réels. Client créé à la première utilisation
 * (aucune connexion au démarrage). Sortie STRUCTURÉE ({@code outputConfig(Class)}), validée ensuite
 * par {@code AssistantService}. Les erreurs du fournisseur sont classées (authentification, débit,
 * délai, service…) et journalisées avec leur seule catégorie et leur code HTTP.
 */
@Component
class AnthropicAiClient implements AiClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnthropicAiClient.class);

    private final String provider;
    private final String apiKey;
    private final String model;
    private final long maxTokens;
    private final Duration timeout;
    private final int maxRetries;
    private final String baseUrl;
    private volatile AnthropicClient client;

    AnthropicAiClient(
            @Value("${fooyre.ai.provider:}") String provider,
            @Value("${fooyre.ai.anthropic.api-key:}") String apiKey,
            @Value("${fooyre.ai.anthropic.model:}") String model,
            @Value("${fooyre.ai.anthropic.max-tokens:}") String maxTokens,
            @Value("${fooyre.ai.anthropic.timeout-seconds:}") String timeoutSeconds,
            @Value("${fooyre.ai.anthropic.max-retries:}") String maxRetries,
            @Value("${fooyre.ai.anthropic.base-url:}") String baseUrl) {
        // Une valeur VIDE (.env) vaut « non définie » : valeur par défaut, sans échec au démarrage.
        this.provider = orDefault(provider, "anthropic").toLowerCase(Locale.ROOT);
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = orDefault(model, "claude-opus-5-5");
        this.maxTokens = Long.parseLong(orDefault(maxTokens, "16000"));
        this.timeout = Duration.ofSeconds(Long.parseLong(orDefault(timeoutSeconds, "120")));
        this.maxRetries = Integer.parseInt(orDefault(maxRetries, "2"));
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip();
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    @Override
    public boolean configured() {
        return "anthropic".equals(provider) && !apiKey.isEmpty();
    }

    @Override
    public Result complete(String systemPrompt, List<Turn> history, String question) {
        if (!configured()) {
            throw new AiUnavailableException(Reason.NOT_CONFIGURED, "fournisseur IA non configuré", null);
        }
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .system(systemPrompt);
        for (Turn turn : history) {
            if (turn.fromUser()) {
                builder.addUserMessage(turn.text());
            } else {
                builder.addAssistantMessage(turn.text());
            }
        }
        StructuredMessageCreateParams<AssistantPayload> params = builder
                .addUserMessage(question)
                .outputConfig(AssistantPayload.class)
                .build();
        try {
            StructuredMessage<AssistantPayload> message = client().messages().create(params);
            String stopReason = message.stopReason().map(StopReason::toString).orElse("unknown");
            Usage usage = new Usage(message.usage().inputTokens(), message.usage().outputTokens(), stopReason);
            if (message.stopReason().filter(reason -> reason.equals(StopReason.REFUSAL)).isPresent()) {
                throw new AiRefusalException();
            }
            AssistantPayload payload = message.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(text -> text.text())
                    .findFirst()
                    .orElse(null);
            if (payload == null) {
                LOGGER.warn("Fournisseur IA : réponse structurée absente (motif d'arrêt {})", stopReason);
                throw new AiUnavailableException(Reason.INVALID_RESPONSE, "réponse structurée absente", null);
            }
            return new Result(payload, usage, model);
        } catch (AnthropicException failure) {
            Reason reason = classify(failure);
            // Catégorie et code HTTP uniquement : jamais le message du fournisseur, la clé ni le contenu.
            LOGGER.warn("Fournisseur IA en échec : catégorie={} type={} statut={}", reason,
                    failure.getClass().getSimpleName(), statusOf(failure));
            throw new AiUnavailableException(reason, "appel du fournisseur IA en échec (" + reason + ")", failure);
        }
    }

    static Reason classify(AnthropicException failure) {
        if (failure instanceof UnauthorizedException || failure instanceof PermissionDeniedException) {
            return Reason.AUTHENTICATION;
        }
        if (failure instanceof RateLimitException) {
            return Reason.RATE_LIMITED;
        }
        if (failure instanceof AnthropicIoException) {
            return Reason.NETWORK_OR_TIMEOUT;
        }
        if (failure instanceof AnthropicServiceException service) {
            return service.statusCode() >= 500 ? Reason.PROVIDER_ERROR : Reason.REQUEST_REJECTED;
        }
        return Reason.INVALID_RESPONSE;
    }

    private static String statusOf(AnthropicException failure) {
        return failure instanceof AnthropicServiceException service ? String.valueOf(service.statusCode()) : "-";
    }

    private AnthropicClient client() {
        AnthropicClient current = client;
        if (current == null) {
            synchronized (this) {
                if (client == null) {
                    AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                            .apiKey(apiKey)
                            .timeout(timeout)
                            .maxRetries(maxRetries);
                    if (!baseUrl.isEmpty()) {
                        builder.baseUrl(baseUrl); // tests hors ligne (simulateur local) uniquement
                    }
                    client = builder.build();
                }
                current = client;
            }
        }
        return current;
    }
}
