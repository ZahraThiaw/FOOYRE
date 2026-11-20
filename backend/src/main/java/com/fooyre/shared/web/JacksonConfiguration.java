package com.fooyre.shared.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.DeserializationFeature;

/**
 * Montants, coûts, quantités et taux en JSON (ADR‑06, CLAUDE.md §4) :
 * <ul>
 *   <li>tout {@link BigDecimal} est écrit en CHAÎNE, en notation décimale simple
 *       ({@code "1000"}, jamais {@code "1E+3"}), avec son échelle d'origine : aucun arrondi
 *       n'est appliqué ici (les règles d'arrondi sont explicites, au point de calcul) ;</li>
 *   <li>en lecture, un nombre JSON décimal non typé est lu en {@link BigDecimal}, jamais en
 *       {@code double}.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfiguration {

    @Bean
    JsonMapperBuilderCustomizer decimalAsStringCustomizer() {
        return builder -> builder
                .withConfigOverride(BigDecimal.class,
                        override -> override.setFormat(JsonFormat.Value.forShape(JsonFormat.Shape.STRING)))
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true);
    }
}
