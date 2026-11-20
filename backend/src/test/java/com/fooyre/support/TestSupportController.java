package com.fooyre.support;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contrôleur RÉSERVÉ AUX TESTS (src/test) : il permet de vérifier le socle (erreurs, sécurité,
 * JSON, traceId). Il n'existe pas dans l'application livrée.
 */
@RestController
@RequestMapping("/api/v1/test-support")
public class TestSupportController {

    public static final String TECHNICAL_SECRET = "SQLSTATE 42P01 password=ne-doit-jamais-sortir";

    public record SampleRequest(
            @NotBlank String name,
            @Size(min = 2, max = 5) String code,
            @Pattern(regexp = "[0-9]+") String phone,
            @NotNull BigDecimal amount) {
    }

    public record AmountPayload(BigDecimal amount, BigDecimal quantity) {
    }

    @PostMapping("/validation")
    public SampleRequest validate(@Valid @RequestBody SampleRequest request) {
        return request;
    }

    @PostMapping("/amounts")
    public AmountPayload echoAmounts(@RequestBody AmountPayload payload) {
        return payload;
    }

    @GetMapping("/amounts/sample")
    public Map<String, BigDecimal> sampleAmounts() {
        return Map.of(
                "price", new BigDecimal("1234.50"),
                "scientific", new BigDecimal("1E+3"),
                "precise", new BigDecimal("12345678901234567890.1234"));
    }

    @GetMapping("/items/{id}")
    public String item(@PathVariable UUID id) {
        return id.toString();
    }

    @GetMapping("/search")
    public String search(@RequestParam @Size(max = 3) String q) {
        return q;
    }

    @GetMapping("/required-header")
    public String requiredHeader(@RequestHeader("X-Required-Header") String value) {
        return value;
    }

    @GetMapping("/failure")
    public String failure() {
        throw new IllegalStateException(TECHNICAL_SECRET);
    }

    @GetMapping("/restricted")
    @PreAuthorize("hasAuthority('TEST_PERMISSION')")
    public String restricted() {
        return "ok";
    }

    @GetMapping("/trace")
    public String trace() {
        return MDC.get("traceId");
    }
}
