package com.fooyre.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fooyre.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Le traceId est présent dans le MDC pendant le traitement, puis retiré. */
@IntegrationTest
@WithMockUser
class TraceIdIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void traceIdIsInTheMdcDuringTheRequestAndClearedAfterwards() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/test-support/trace").header("X-Trace-Id", "fourni-par-le-client"))
                .andExpect(status().isOk())
                .andReturn();
        String header = result.getResponse().getHeader(TraceIdFilter.RESPONSE_HEADER);
        assertThat(result.getResponse().getContentAsString()).isEqualTo(header);
        assertThat(header).isNotEqualTo("fourni-par-le-client");
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }
}
