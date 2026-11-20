package com.fooyre.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fooyre.TestcontainersConfiguration;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rejets de requêtes vérifiés sur un VRAI Tomcat (port aléatoire, démarré par le test) avec des
 * requêtes HTTP brutes : MockMvc ne reproduit ni Tomcat ni la redistribution vers /error.
 * <ul>
 *   <li>Rejets du pare‑feu Spring Security : Problem Details 400 (correction D3).</li>
 *   <li>Rejets de Tomcat lui‑même, avant tout filtre : LIMITE documentée (page Tomcat minimale).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class RequestRejectionServerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    record RawResponse(int status, Map<String, String> headers, String body) {
        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    // --- D3 : pare-feu Spring Security --------------------------------------------------------------

    /** Chemins rejetés par StrictHttpFirewall ; sans pare‑feu, la sonde publique répondrait 200. */
    @ParameterizedTest
    @ValueSource(strings = {
        "/actuator/health/liveness;jsessionid=1",
        "/actuator/health/%2e%2e/liveness",
        "/actuator//health/liveness",
        "/api/v1/%2e%2e/actuator/env"})
    void firewallRejectionsAreProblemDetailsWithABackendTraceId(String path, CapturedOutput output) throws Exception {
        RawResponse response = get(path, "");

        assertThat(response.status()).as("requête rejetée").isEqualTo(400);
        assertThat(response.header("content-type")).isEqualTo("application/problem+json");
        assertThat(response.body()).doesNotContainIgnoringCase("<html");

        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("status").asInt()).isEqualTo(400);
        assertThat(body.get("code").asString()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.get("type").asString()).isEqualTo("https://fooyre.app/problems/validation-error");
        assertThat(body.get("title").asString()).isEqualTo("Données invalides");
        assertThat(body.get("errors").isArray()).isTrue();
        assertThat(body.get("errors").size()).isZero();
        assertThat(body.has("instance")).as("chemin suspect non recopié").isFalse();
        assertThat(body.has("timestamp")).as("pas de page d'erreur générique Boot").isFalse();

        String traceId = body.get("traceId").asString();
        assertThat(UUID.fromString(traceId).version()).isEqualTo(4);
        assertThat(response.header("x-trace-id")).isEqualTo(traceId);

        assertThat(response.body()).doesNotContain("jsessionid", "%2e", "..", "malicious", "Firewall",
                "RequestRejected", "Exception", "at org.");
        assertThat(response.header("www-authenticate")).isNull();
        // Aucune redistribution vers /error, aucune erreur secondaire, motif du rejet non journalisé.
        assertThat(output).contains("Requête rejetée par le pare-feu de sécurité")
                .doesNotContain("Erreur interne non gérée")
                .doesNotContain("potentially malicious")
                .doesNotContain(" ERROR ");
    }

    @Test
    void firewallRejectionOfAnUnauthenticatedApiRouteIsStill400Not200() throws Exception {
        RawResponse rejected = get("/api/v1/test-support/trace;x=1", "");
        RawResponse normal = get("/api/v1/test-support/trace", "");
        assertThat(rejected.status()).isEqualTo(400);
        assertThat(normal.status()).isEqualTo(401);
    }

    // --- D2 sur Tomcat réel + encodage ------------------------------------------------------------

    @Test
    void real401CarriesBearerChallengeAndUtf8ProblemDetails() throws Exception {
        RawResponse response = get("/api/v1/test-support/trace", "");
        assertThat(response.status()).isEqualTo(401);
        assertThat(response.header("www-authenticate")).isEqualTo("Bearer");
        assertThat(response.header("content-type")).isEqualTo("application/problem+json");
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("code").asString()).isEqualTo("SESSION_REVOKED");
        assertThat(body.get("detail").asString()).isEqualTo("La session est absente, expirée ou révoquée.");
        assertThat(body.get("traceId").asString()).isEqualTo(response.header("x-trace-id"));
    }

    // --- LIMITE : rejets de Tomcat avant tout filtre (aucun code applicatif n'est exécuté) ----------

    /**
     * Tomcat rejette ces requêtes lors de l'analyse HTTP, avant les filtres Spring : la réponse est
     * la page minimale de Tomcat (HTML), sans traceId. Le test FIGE la limite et vérifie qu'aucune
     * information interne n'est révélée (ni version, ni trace de pile).
     */
    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/%5ctest", "/api/v1/a%2Fb", "/api/v1/a%00b", "/api/v1/a|b"})
    void tomcatLevelRejectionsAreMinimalAndRevealNothing(String path) throws Exception {
        RawResponse response = get(path, "");
        assertTomcatMinimal400(response);
    }

    @Test
    void oversizedHeaderIsRejectedByTomcatRevealingNothing() throws Exception {
        assertTomcatMinimal400(get("/api/v1/x", "X-Big: " + "a".repeat(20_000) + "\r\n"));
    }

    private static void assertTomcatMinimal400(RawResponse response) {
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.header("x-trace-id")).as("aucun filtre exécuté").isNull();
        assertThat(response.body()).doesNotContain("Apache Tomcat", "Tomcat/", "Exception", "at org.",
                "Spring", "fooyre", "Root Cause", "Note ");
        assertThat(response.header("server")).isNull();
    }

    // --- Client HTTP brut (aucune normalisation du chemin) -----------------------------------------

    private RawResponse get(String path, String extraHeaders) throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nAccept: application/json\r\n"
                    + extraHeaders + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(buffer);
            return parse(buffer.toByteArray());
        }
    }

    private static RawResponse parse(byte[] raw) {
        String head = new String(raw, StandardCharsets.ISO_8859_1);
        int split = head.indexOf("\r\n\r\n");
        String[] lines = head.substring(0, split).split("\r\n");
        int status = Integer.parseInt(lines[0].split(" ")[1]);
        Map<String, String> headers = new TreeMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
        }
        byte[] body = java.util.Arrays.copyOfRange(raw, split + 4, raw.length);
        if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
            body = dechunk(body);
        }
        return new RawResponse(status, headers, new String(body, StandardCharsets.UTF_8));
    }

    private static byte[] dechunk(byte[] chunked) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int index = 0;
        while (index < chunked.length) {
            int lineEnd = indexOfCrlf(chunked, index);
            int size = Integer.parseInt(new String(chunked, index, lineEnd - index, StandardCharsets.ISO_8859_1).trim(), 16);
            if (size == 0) {
                break;
            }
            out.write(chunked, lineEnd + 2, size);
            index = lineEnd + 2 + size + 2;
        }
        return out.toByteArray();
    }

    private static int indexOfCrlf(byte[] data, int from) {
        for (int i = from; i < data.length - 1; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n') {
                return i;
            }
        }
        return data.length;
    }
}
