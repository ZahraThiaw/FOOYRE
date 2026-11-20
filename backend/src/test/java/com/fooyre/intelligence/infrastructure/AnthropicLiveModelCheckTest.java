package com.fooyre.intelligence.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.models.ModelInfo;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * VÉRIFICATION MANUELLE, DÉSACTIVÉE PAR DÉFAUT (jamais exécutée par {@code mvnw verify}) :
 * {@code GET /v1/models/{modèle}} via le SDK officiel — aucune génération de texte. Confirme que la
 * clé est acceptée et que le modèle configuré est disponible pour le compte.
 *
 * <p>Lancement explicite uniquement :
 * {@code ./mvnw test -Dtest=AnthropicLiveModelCheckTest -Dfooyre.ai.liveCheck=true}
 * (+ {@code -Dfooyre.ai.liveCheck.rawFallback=true} pour un SECOND appel de diagnostic avec
 * l'en-tête {@code x-api-key} si le SDK reçoit 401).
 *
 * <p>La clé est lue dans {@code ../.env} (ou la variable d'environnement) et n'est JAMAIS affichée :
 * seuls le statut HTTP, le type d'erreur, l'identifiant de requête et les informations publiques du
 * modèle sont imprimés. Aucune donnée FOOYRE n'est transmise.
 */
@EnabledIfSystemProperty(named = "fooyre.ai.liveCheck", matches = "true")
class AnthropicLiveModelCheckTest {

    private static final Pattern ERROR_TYPE = Pattern.compile("\"type\"\\s*:\\s*\"([a-z_]+)\"");

    @Test
    void modelIsAvailableForTheConfiguredKey() throws Exception {
        String key = setting("ANTHROPIC_API_KEY");
        String model = setting("ANTHROPIC_MODEL");
        model = model.isEmpty() ? "claude-opus-5-5" : model;
        assertThat(key).as("ANTHROPIC_API_KEY absente").isNotEmpty();
        System.out.println("[vérification] clé présente (" + key.length() + " caractères, non affichée) ; modèle : " + model);
        System.out.println("[vérification] authentification par le SDK officiel 3.3.0 : en-tête Authorization: Bearer");

        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(key)
                .maxRetries(0)
                .timeout(Duration.ofSeconds(20))
                .build();
        try {
            ModelInfo info = client.models().retrieve(model);
            System.out.println("[vérification] SUCCÈS HTTP 200 — modèle disponible : id=" + info.id()
                    + " nom=« " + info.displayName() + " » contexte=" + info.maxInputTokens().map(String::valueOf).orElse("?")
                    + " sortie_max=" + info.maxTokens().map(String::valueOf).orElse("?"));
            assertThat(info.id()).isEqualTo(model);
        } catch (AnthropicServiceException failure) {
            System.out.println("[vérification] ÉCHEC via le SDK — statut HTTP=" + failure.statusCode()
                    + " type=" + failure.errorType().map(String::valueOf).orElse("?")
                    + " request-id=" + String.join(",", failure.headers().values("request-id")));
            if (failure.statusCode() == 401 && Boolean.getBoolean("fooyre.ai.liveCheck.rawFallback")) {
                rawCheckWithXApiKey(key, model);
            }
            throw new AssertionError("vérification du modèle en échec (statut " + failure.statusCode() + ")");
        } catch (AnthropicIoException network) {
            System.out.println("[vérification] ÉCHEC réseau ou délai : " + network.getClass().getSimpleName());
            throw new AssertionError("réseau indisponible");
        }
    }

    /** Diagnostic SANS le SDK : même requête avec l'en-tête x-api-key ; seuls statut et type imprimés. */
    private static void rawCheckWithXApiKey(String key, String model) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/models/" + model))
                .timeout(Duration.ofSeconds(20))
                .header("x-api-key", key)
                .header("anthropic-version", "2023-06-01")
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        Matcher type = ERROR_TYPE.matcher(response.body());
        List<String> types = type.results().map(match -> match.group(1)).toList();
        System.out.println("[diagnostic x-api-key] statut HTTP=" + response.statusCode() + " types=" + types
                + " request-id=" + response.headers().firstValue("request-id").orElse("?"));
    }

    /** Variable d'environnement, sinon ../.env ou .env (fichier local jamais versionné) ; jamais affichée. */
    private static String setting(String name) throws IOException {
        String fromEnv = System.getenv(name);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.strip();
        }
        for (Path candidate : List.of(Path.of("../.env"), Path.of(".env"))) {
            if (Files.exists(candidate)) {
                for (String line : Files.readAllLines(candidate)) {
                    if (line.startsWith(name + "=")) {
                        return line.substring(name.length() + 1).strip();
                    }
                }
            }
        }
        return "";
    }
}
