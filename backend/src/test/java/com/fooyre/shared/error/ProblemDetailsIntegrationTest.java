package com.fooyre.shared.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fooyre.IntegrationTest;
import com.fooyre.shared.web.TraceIdFilter;
import com.fooyre.support.TestSupportController;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Contrat Problem Details (docs/api-specification.md §1.3), vérifié de bout en bout. */
@IntegrationTest
@WithMockUser
class ProblemDetailsIntegrationTest {

    private static final String BASE = "/api/v1/test-support";

    @Autowired
    private MockMvc mvc;

    @Test
    void validationErrorListsStableFieldCodesWithoutRejectedValues() throws Exception {
        String body = """
                {"name": " ", "code": "TROPLONG", "phone": "77-abc", "amount": null}""";
        MvcResult result = mvc.perform(post(BASE + "/validation").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.type").value("https://fooyre.app/problems/validation-error"))
                .andExpect(jsonPath("$.title").value("Données invalides"))
                .andExpect(jsonPath("$.instance").value(BASE + "/validation"))
                .andExpect(jsonPath("$.errors", hasSize(4)))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("name", "code", "phone", "amount")))
                .andExpect(jsonPath("$.errors[?(@.field == 'name')].code").value("REQUIRED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'code')].code").value("TOO_LONG"))
                .andExpect(jsonPath("$.errors[?(@.field == 'phone')].code").value("INVALID_FORMAT"))
                .andExpect(jsonPath("$.errors[?(@.field == 'amount')].code").value("REQUIRED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'name')].message").value("Ce champ est obligatoire."))
                .andReturn();
        String response = result.getResponse().getContentAsString();
        assertThat(response).doesNotContain("TROPLONG", "77-abc", "jakarta", "NotBlank");
        assertTraceIdMatchesHeader(result);
    }

    @Test
    void tooShortValueIsReported() throws Exception {
        mvc.perform(post(BASE + "/validation").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Awa", "code": "A", "amount": "1"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("code"))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_SHORT"));
    }

    @Test
    void malformedJsonIsAValidationErrorWithoutTechnicalDetail() throws Exception {
        MvcResult result = mvc.perform(post(BASE + "/validation").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("jackson", "Unexpected", "line:");
    }

    @Test
    void wrongJsonTypeIsReportedOnTheField() throws Exception {
        mvc.perform(post(BASE + "/validation").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Awa", "amount": "douze"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("amount"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_FORMAT"));
    }

    @Test
    void pathVariableTypeMismatchIsAValidationError() throws Exception {
        mvc.perform(get(BASE + "/items/pas-un-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("id"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_FORMAT"));
    }

    @Test
    void requestParameterConstraintIsAValidationError() throws Exception {
        mvc.perform(get(BASE + "/search").param("q", "trop long"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("q"))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
    }

    // --- D4 : paramètre / en‑tête obligatoire manquant -------------------------------------------

    @Test
    void missingRequiredParameterIsARequiredFieldError() throws Exception {
        MvcResult result = mvc.perform(get(BASE + "/search"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("q"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"))
                .andExpect(jsonPath("$.errors[0].message").value("Ce champ est obligatoire."))
                .andReturn();
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("\"errors\":[{\"field\":\"q\",\"code\":\"REQUIRED\",\"message\":\"Ce champ est obligatoire.\"}]");
    }

    @Test
    void missingRequiredHeaderIsARequiredFieldError() throws Exception {
        mvc.perform(get(BASE + "/required-header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("X-Required-Header"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        mvc.perform(get(BASE + "/required-header").header("X-Required-Header", "présent"))
                .andExpect(status().isOk());
    }

    @Test
    void badRequestNotLinkedToAMissingFieldHasNoRequiredError() throws Exception {
        mvc.perform(post(BASE + "/validation").contentType(MediaType.APPLICATION_JSON).content("[1, 2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.errors[?(@.code == 'REQUIRED')]", hasSize(0)));
    }

    // --- 406 : comportement documenté (aucun code créé) ------------------------------------------

    /**
     * Client n'acceptant que HTML sur une route qui produit du JSON. Comportement OBSERVÉ et figé
     * (Spring Framework 7.0.10, sans réglage particulier de la négociation de contenu) : 406, corps
     * Problem Details en application/problem+json malgré l'Accept, SANS propriété code (aucun code
     * documenté pour 406), traceId présent ; les données de la route ne sont pas renvoyées.
     */
    @Test
    void notAcceptableIsAProblemDetailsWithoutCodeButWithTraceId() throws Exception {
        MvcResult result = mvc.perform(get(BASE + "/amounts/sample").accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(406))
                .andExpect(jsonPath("$.type").value("https://fooyre.app/problems/http-406"))
                .andExpect(jsonPath("$.title").value("Format de réponse non disponible"))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andReturn();
        assertTraceIdMatchesHeader(result);
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("1234.50", "Exception", "<html");
    }

    @Test
    void unknownRouteIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/route-inexistante"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.title").value("Ressource introuvable"));
    }

    @Test
    void unsupportedMethodKeepsItsStatusWithoutInventedCode() throws Exception {
        mvc.perform(delete(BASE + "/trace"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void unsupportedMediaTypeKeepsItsStatus() throws Exception {
        mvc.perform(post(BASE + "/validation").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    void internalErrorHidesTechnicalDetailsAndExposesTraceId() throws Exception {
        MvcResult result = mvc.perform(get(BASE + "/failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.title").value("Erreur interne"))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist())
                .andReturn();
        String response = result.getResponse().getContentAsString();
        assertThat(response).doesNotContain(TestSupportController.TECHNICAL_SECRET, "IllegalStateException",
                "SQLSTATE", "password", "at com.fooyre");
        assertTraceIdMatchesHeader(result);
    }

    private static void assertTraceIdMatchesHeader(MvcResult result) throws Exception {
        String header = result.getResponse().getHeader(TraceIdFilter.RESPONSE_HEADER);
        assertThat(header).isNotBlank();
        assertThat(result.getResponse().getContentAsString()).contains("\"traceId\":\"" + header + "\"");
    }
}
