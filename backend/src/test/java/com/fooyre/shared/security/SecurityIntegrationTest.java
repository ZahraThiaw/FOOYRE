package com.fooyre.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.hamcrest.Matchers.hasSize;

import com.fooyre.IntegrationTest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Refus par défaut, réponses 401/403 en Problem Details, Actuator limité (B1). */
@IntegrationTest
class SecurityIntegrationTest {

    private static final String[] HIDDEN_ACTUATOR_ENDPOINTS = {
        "/actuator", "/actuator/health", "/actuator/env", "/actuator/beans", "/actuator/configprops",
        "/actuator/metrics", "/actuator/info", "/actuator/heapdump", "/actuator/threaddump",
        "/actuator/loggers", "/actuator/mappings", "/actuator/prometheus", "/actuator/shutdown"};

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ProblemAccessDeniedHandler accessDeniedHandler;

    @Test
    void anonymousRequestIsRejectedWith401ProblemDetails() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/test-support/trace"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("SESSION_REVOKED"))
                .andExpect(jsonPath("$.title").value("Authentification requise"))
                .andExpect(jsonPath("$.instance").value("/api/v1/test-support/trace"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .contains(result.getResponse().getHeader("X-Trace-Id"));
    }

    @Test
    void anonymousUnknownRouteIsAlso401() throws Exception {
        mvc.perform(get("/api/v1/route-inexistante"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
    }

    @Test
    void anonymousPostIsRejectedWith401NotCsrf403() throws Exception {
        mvc.perform(post("/api/v1/test-support/amounts").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
    }

    @Test
    @WithMockUser
    void authenticatedUserWithoutPermissionGets403ProblemDetails() throws Exception {
        mvc.perform(get("/api/v1/test-support/restricted"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.title").value("Accès refusé"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Test
    void filterLevel401AndMvcLevel401ShareTheSameBearerChallenge() throws Exception {
        for (String path : new String[] {"/api/v1/route-inexistante", "/actuator/env"}) {
            mvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("SESSION_REVOKED"))
                    .andExpect(jsonPath("$.traceId").isNotEmpty());
        }
    }

    @Test
    void filterLevel403HandlerWritesProblemDetailsWithoutBearerChallenge() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/x");
        MockHttpServletResponse response = new MockHttpServletResponse();
        accessDeniedHandler.handle(request, response, new AccessDeniedException("détail interne"));
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("WWW-Authenticate")).isNull();
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains("\"code\":\"FORBIDDEN\"").doesNotContain("détail interne");
    }

    @Test
    void writerNeverOverwritesACommittedResponse() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("déjà envoyé");
        response.flushBuffer();
        accessDeniedHandler.handle(new MockHttpServletRequest("GET", "/x"), response,
                new AccessDeniedException("x"));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo("déjà envoyé");
    }

    // --- D3 : rejets du pare-feu (MockMvc ; vérification sur Tomcat réel : RequestRejectionServerTest)

    @Test
    void firewallRejectionIsAProblemDetailsWithoutEchoingThePath() throws Exception {
        MvcResult result = mvc.perform(get(URI.create("/actuator/health/liveness;jsessionid=1")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.instance").doesNotExist())
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("jsessionid", "liveness", "malicious", "Firewall", "RequestRejected");
        assertThat(body).contains("\"traceId\":\"" + result.getResponse().getHeader("X-Trace-Id") + "\"");
    }

    @Test
    @WithMockUser(authorities = "TEST_PERMISSION")
    void authenticatedUserWithPermissionIsAllowed() throws Exception {
        mvc.perform(get("/api/v1/test-support/restricted"))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
    }

    @Test
    @WithMockUser
    void noHttpSessionIsCreated() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/test-support/trace"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    void securityHeadersArePresent() throws Exception {
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().exists("Cache-Control"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health/liveness", "/actuator/health/readiness"})
    void healthProbesArePublicAndWithoutDetails(String probe) throws Exception {
        MvcResult result = mvc.perform(get(probe))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("postgres", "jdbc", "PostgreSQL");
    }

    @Test
    void otherActuatorEndpointsAreRefusedToAnonymousUsers() throws Exception {
        for (String endpoint : HIDDEN_ACTUATOR_ENDPOINTS) {
            mvc.perform(get(endpoint))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
        }
    }

    @Test
    @WithMockUser(authorities = {"PLATFORM_AUDIT_READ", "ROLE_ADMIN"})
    void otherActuatorEndpointsDoNotExistEvenWhenAuthenticated() throws Exception {
        for (String endpoint : HIDDEN_ACTUATOR_ENDPOINTS) {
            if ("/actuator/health".equals(endpoint)) {
                continue;
            }
            mvc.perform(get(endpoint)).andExpect(status().isNotFound());
        }
    }

    @Test
    @WithMockUser
    void healthRootRevealsNoDetailsEvenWhenAuthenticated() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }
}
