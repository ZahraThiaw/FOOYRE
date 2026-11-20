package com.fooyre.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fooyre.IntegrationTest;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Montants en JSON : chaînes décimales, sans perte de précision ni arrondi (ADR‑06). */
@IntegrationTest
@WithMockUser
class BigDecimalJsonIntegrationTest {

    record Amount(BigDecimal value) {
    }

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private MockMvc mvc;

    @ParameterizedTest
    @CsvSource({
        "1234.50, \"1234.50\"",
        "0.10, \"0.10\"",
        "0, \"0\"",
        "-15000, \"-15000\"",
        "1E+3, \"1000\"",
        "1.000, \"1.000\"",
        "0.0001, \"0.0001\"",
        "12345678901234567890.1234, \"12345678901234567890.1234\""})
    void bigDecimalIsWrittenAsPlainStringKeepingItsScale(String value, String expectedJson) {
        String json = jsonMapper.writeValueAsString(new Amount(new BigDecimal(value)));
        assertThat(json).isEqualTo("{\"value\":" + expectedJson + "}");
    }

    @Test
    void noRoundingIsAppliedWhenWriting() {
        String json = jsonMapper.writeValueAsString(new Amount(new BigDecimal("1499.995")));
        assertThat(json).isEqualTo("{\"value\":\"1499.995\"}");
    }

    @ParameterizedTest
    @CsvSource({"\"0.1\", 0.1", "0.1, 0.1", "\"12345678901234567890.1234\", 12345678901234567890.1234",
        "0.30000000000000004441, 0.30000000000000004441"})
    void bigDecimalIsReadExactlyFromStringOrNumber(String jsonValue, String expected) {
        Amount amount = jsonMapper.readValue("{\"value\":" + jsonValue + "}", Amount.class);
        assertThat(amount.value()).isEqualTo(new BigDecimal(expected));
    }

    @Test
    void untypedDecimalNumbersAreReadAsBigDecimalNotDouble() {
        Map<String, Object> map = jsonMapper.readValue("{\"value\":0.1}", new TypeReference<>() {
        });
        assertThat(map.get("value")).isInstanceOf(BigDecimal.class).isEqualTo(new BigDecimal("0.1"));
    }

    @Test
    void amountsRoundTripThroughTheApiAsStrings() throws Exception {
        String response = mvc.perform(post("/api/v1/test-support/amounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"14500.00\",\"quantity\":2.500}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).isEqualTo("{\"amount\":\"14500.00\",\"quantity\":\"2.500\"}");
    }

    @Test
    void controllerResponsesUseStringsForAmounts() throws Exception {
        String response = mvc.perform(get("/api/v1/test-support/amounts/sample"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(response)
                .contains("\"price\":\"1234.50\"")
                .contains("\"scientific\":\"1000\"")
                .contains("\"precise\":\"12345678901234567890.1234\"")
                .doesNotContain("E+");
    }
}
