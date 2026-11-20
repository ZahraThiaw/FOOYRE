package com.fooyre.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Génération, propagation et nettoyage du traceId (décision B1). */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void generatesAUuidVisibleInMdcHeaderAndRequestThenCleansTheMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/x");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seenInChain.set(MDC.get(TraceIdFilter.MDC_KEY)));

        String header = response.getHeader(TraceIdFilter.RESPONSE_HEADER);
        assertThat(header).isNotNull();
        assertThat(UUID.fromString(header).version()).isEqualTo(4);
        assertThat(seenInChain.get()).isEqualTo(header);
        assertThat(TraceIdFilter.currentTraceId(request)).isEqualTo(header);
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void cleansTheMdcEvenWhenTheChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/x");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            throw new ServletException("échec simulé");
        })).isInstanceOf(ServletException.class);

        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void ignoresAnyTraceIdSuppliedByTheClient() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/x");
        String forged = "forged\r\nINFO fausse ligne de journal";
        request.addHeader(TraceIdFilter.RESPONSE_HEADER, forged);
        request.addHeader("X-Request-Id", "11111111-1111-4111-8111-111111111111");
        request.addHeader("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String header = response.getHeader(TraceIdFilter.RESPONSE_HEADER);
        assertThat(header).isNotEqualTo(forged).isNotEqualTo("11111111-1111-4111-8111-111111111111");
        assertThat(UUID.fromString(header)).isNotNull();
    }

    @Test
    void eachRequestGetsADistinctTraceId() throws Exception {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET", "/"), response, new MockFilterChain());
            ids.add(response.getHeader(TraceIdFilter.RESPONSE_HEADER));
        }
        assertThat(ids).hasSize(200);
    }
}
