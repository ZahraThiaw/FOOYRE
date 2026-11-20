package com.fooyre.intelligence.application;

import java.math.BigDecimal;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Configuration de l'IA, lue depuis l'environnement. Une variable VIDE (cas de {@code .env}) vaut
 * « non définie » : la valeur par défaut s'applique, sans faire échouer le démarrage.
 */
@Component
public class AiSettings {

    private final boolean enabled;
    private final boolean productionApproved;
    private final boolean prodProfile;
    private final String termsVersion;
    private final long dailyRequestsPerBusiness;
    private final BigDecimal monthlyBudgetPerBusinessUsd;
    private final BigDecimal priceInputPerMtok;
    private final BigDecimal priceOutputPerMtok;

    AiSettings(Environment environment,
            @Value("${fooyre.ai.enabled:}") String enabled,
            @Value("${fooyre.ai.production-approved:}") String productionApproved,
            @Value("${fooyre.ai.terms-version:}") String termsVersion,
            @Value("${fooyre.ai.daily-requests-per-business:}") String dailyRequests,
            @Value("${fooyre.ai.monthly-budget-per-business-usd:}") String monthlyBudget,
            @Value("${fooyre.ai.price-input-per-mtok-usd:}") String priceInput,
            @Value("${fooyre.ai.price-output-per-mtok-usd:}") String priceOutput) {
        this.enabled = "true".equalsIgnoreCase(trim(enabled));
        this.productionApproved = "true".equalsIgnoreCase(trim(productionApproved));
        this.prodProfile = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        this.termsVersion = orDefault(termsVersion, "2026-11-19");
        this.dailyRequestsPerBusiness = Long.parseLong(orDefault(dailyRequests, "50"));
        this.monthlyBudgetPerBusinessUsd = new BigDecimal(orDefault(monthlyBudget, "5"));
        this.priceInputPerMtok = new BigDecimal(orDefault(priceInput, "4.00"));
        this.priceOutputPerMtok = new BigDecimal(orDefault(priceOutput, "20.00"));
    }

    static String trim(String value) {
        return value == null ? "" : value.strip();
    }

    static String orDefault(String value, String fallback) {
        String trimmed = trim(value);
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    /** IA autorisée globalement : {@code AI_ENABLED}, et en prod seulement après {@code AI_PRODUCTION_APPROVED}. */
    public boolean globallyEnabled() {
        return enabled && (!prodProfile || productionApproved);
    }

    public String termsVersion() {
        return termsVersion;
    }

    public long dailyRequestsPerBusiness() {
        return dailyRequestsPerBusiness;
    }

    public BigDecimal monthlyBudgetPerBusinessUsd() {
        return monthlyBudgetPerBusinessUsd;
    }

    /** Coût ESTIMÉ en USD d'un appel (grille configurable, à revérifier). */
    public BigDecimal estimateCostUsd(long inputTokens, long outputTokens) {
        BigDecimal million = BigDecimal.valueOf(1_000_000);
        return priceInputPerMtok.multiply(BigDecimal.valueOf(inputTokens))
                .add(priceOutputPerMtok.multiply(BigDecimal.valueOf(outputTokens)))
                .divide(million, 6, java.math.RoundingMode.HALF_UP);
    }
}
