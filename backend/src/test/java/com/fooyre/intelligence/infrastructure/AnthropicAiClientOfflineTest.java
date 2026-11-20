package com.fooyre.intelligence.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fooyre.intelligence.application.AiClient;
import com.fooyre.intelligence.application.AiClient.AiUnavailableException;
import com.fooyre.intelligence.application.AiClient.Reason;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Adaptateur Anthropic contre un SIMULATEUR HTTP LOCAL (aucun appel à Anthropic, aucun coût) :
 * construction réelle de la requête par le SDK officiel, lecture de la sortie structurée, et
 * traitement des erreurs (401, 429, 500, délai) sans jamais exposer la clé.
 */
@ExtendWith(OutputCaptureExtension.class)
class AnthropicAiClientOfflineTest {

    /** Fausse clé de test (format quelconque) : ne doit apparaître dans aucun message ni journal. */
    private static final String KEY = "sk-ant-api03-cle-de-test-ne-doit-jamais-apparaitre-0123456789";

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastKeyHeader = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();

    private String start(int status, String body, long delayMs) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            calls.incrementAndGet();
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            // Le SDK officiel 3.3.0 transmet la clé dans Authorization: Bearer (constaté) ; x-api-key accepté aussi.
            String apiKeyHeader = exchange.getRequestHeaders().getFirst("x-api-key");
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            lastKeyHeader.set(apiKeyHeader != null ? apiKeyHeader
                    : authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null);
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            } catch (IOException closed) {
                // client parti (délai dépassé) : sans effet
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static AnthropicAiClient client(String provider, String baseUrl, String timeoutSeconds) {
        return new AnthropicAiClient(provider, KEY, "claude-opus-5-5", "1000", timeoutSeconds, "0", baseUrl);
    }

    private static String error(String type) {
        return "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"détail du fournisseur\"}}";
    }

    @Test
    void structuredOutputIsBuiltByTheOfficialSdkAndParsed(CapturedOutput output) throws IOException {
        String payload = "{\\\"answer\\\":\\\"Il vous reste 20 savons.\\\",\\\"insufficientData\\\":false,"
                + "\\\"action\\\":{\\\"type\\\":\\\"PREPARE_SALE\\\",\\\"customerRef\\\":\\\"\\\",\\\"lines\\\":"
                + "[{\\\"productRef\\\":\\\"P1\\\",\\\"quantity\\\":\\\"2\\\"}],\\\"query\\\":\\\"\\\","
                + "\\\"customerName\\\":\\\"\\\",\\\"customerPhone\\\":\\\"\\\"}}";
        String response = "{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5-5\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"" + payload + "\"}],\"stop_reason\":\"end_turn\","
                + "\"stop_sequence\":null,\"usage\":{\"input_tokens\":120,\"output_tokens\":35}}";
        AnthropicAiClient client = client("anthropic", start(200, response, 0), "5");

        AiClient.Result result = client.complete("Invite système de test", List.of(new AiClient.Turn(true, "Bonjour")),
                "Quel est mon stock ?");

        assertThat(result.payload().answer()).isEqualTo("Il vous reste 20 savons.");
        assertThat(result.payload().action().type()).isEqualTo("PREPARE_SALE");
        assertThat(result.payload().action().lines()).extracting(AiClient.ActionLine::productRef).containsExactly("P1");
        assertThat(result.usage().inputTokens()).isEqualTo(120);
        assertThat(result.usage().outputTokens()).isEqualTo(35);
        // Requête : clé uniquement dans un en-tête d'authentification, modèle configuré, sortie structurée demandée.
        assertThat(lastKeyHeader.get()).isEqualTo(KEY);
        assertThat(lastBody.get()).contains("\"model\":\"claude-opus-5-5\"", "output_config", "Quel est mon stock ?")
                .doesNotContain(KEY);
        assertThat(output).doesNotContain(KEY);
    }

    @Test
    void rejectedKeyRateLimitServerErrorAndTimeoutAreClassifiedWithoutLeakingTheKey(CapturedOutput output)
            throws IOException {
        assertThatThrownBy(() -> client("anthropic", start(401, error("authentication_error"), 0), "5")
                .complete("s", List.of(), "q"))
                .isInstanceOfSatisfying(AiUnavailableException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.AUTHENTICATION);
                    assertThat(e.getMessage()).doesNotContain(KEY, "détail du fournisseur");
                });
        stop();
        assertThatThrownBy(() -> client("anthropic", start(429, error("rate_limit_error"), 0), "5")
                .complete("s", List.of(), "q"))
                .isInstanceOfSatisfying(AiUnavailableException.class, e -> assertThat(e.reason()).isEqualTo(Reason.RATE_LIMITED));
        stop();
        assertThatThrownBy(() -> client("anthropic", start(500, error("api_error"), 0), "5")
                .complete("s", List.of(), "q"))
                .isInstanceOfSatisfying(AiUnavailableException.class, e -> assertThat(e.reason()).isEqualTo(Reason.PROVIDER_ERROR));
        stop();
        assertThatThrownBy(() -> client("anthropic", start(200, "{}", 3000), "1")
                .complete("s", List.of(), "q"))
                .isInstanceOfSatisfying(AiUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.NETWORK_OR_TIMEOUT));

        // Journaux : catégorie et statut seulement ; ni clé, ni message du fournisseur.
        assertThat(output).contains("catégorie=AUTHENTICATION", "statut=401", "catégorie=RATE_LIMITED")
                .doesNotContain(KEY, "détail du fournisseur");
    }

    @Test
    void fakeProviderNeverCallsTheNetwork() throws IOException {
        AnthropicAiClient client = client("fake", start(200, "{}", 0), "5");
        assertThat(client.configured()).isFalse();
        assertThatThrownBy(() -> client.complete("s", List.of(), "q"))
                .isInstanceOfSatisfying(AiUnavailableException.class, e -> assertThat(e.reason()).isEqualTo(Reason.NOT_CONFIGURED));
        assertThat(calls.get()).isZero();
        assertThat(new AnthropicAiClient("", "", "", "", "", "", "").configured()).isFalse();
        assertThat(new AnthropicAiClient("", KEY, "", "", "", "", "").configured()).isTrue();
        // Aucun préfixe imposé : une clé personnelle récente « sk-ant-usr-… » est acceptée telle quelle.
        assertThat(new AnthropicAiClient("anthropic", "sk-ant-usr-fausse-cle-de-test", "", "", "", "", "").configured()).isTrue();
    }
}
