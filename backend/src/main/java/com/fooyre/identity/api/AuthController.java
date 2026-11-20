package com.fooyre.identity.api;

import com.fooyre.identity.application.AuthenticationService;
import com.fooyre.identity.application.IssuedSession;
import com.fooyre.identity.application.RefreshOutcome;
import com.fooyre.shared.error.ApiProblemFactory;
import com.fooyre.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentification (docs/api-specification.md §2.1, ADR-04) : connexion, renouvellement,
 * déconnexion. Le jeton d'accès est renvoyé dans le corps ; le refresh token uniquement dans le
 * cookie {@code HttpOnly}. Les trois routes exigent {@code Origin} + {@code X-Requested-With}.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    /** Identifiants de connexion. Le mot de passe n'est jamais renvoyé ni journalisé. */
    public record LoginRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 128) String password) {

        @Override
        public String toString() {
            return "LoginRequest[email=<masqué>, password=<masqué>]";
        }
    }

    /** Jeton d'accès et durée de validité en secondes (même forme que switch-business). */
    public record TokenResponse(String accessToken, long expiresIn) {

        @Override
        public String toString() {
            return "TokenResponse[accessToken=<masqué>, expiresIn=" + expiresIn + "]";
        }
    }

    private final AuthenticationService authentication;
    private final CookieRouteGuard guard;
    private final RefreshCookies cookies;
    private final ApiProblemFactory problems;

    AuthController(AuthenticationService authentication, CookieRouteGuard guard, RefreshCookies cookies,
            ApiProblemFactory problems) {
        this.authentication = authentication;
        this.guard = guard;
        this.cookies = cookies;
        this.problems = problems;
    }

    @PostMapping("/login")
    @PreAuthorize("permitAll()")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest login, HttpServletRequest request) {
        guard.check(request);
        // Adresse TCP du client : aucun en‑tête X-Forwarded-For n'est pris en compte tant qu'aucun
        // proxy de confiance n'est configuré (production : lot B2).
        return withSession(authentication.login(login.email(), login.password(), request.getRemoteAddr()));
    }

    @PostMapping("/refresh")
    @PreAuthorize("permitAll()")
    public ResponseEntity<?> refresh(@CookieValue(name = RefreshCookies.NAME, required = false) String refreshToken,
            HttpServletRequest request) {
        guard.check(request);
        return switch (authentication.refresh(refreshToken)) {
            case RefreshOutcome.Renewed renewed -> withSession(renewed.session());
            case RefreshOutcome.Rejected rejected -> {
                ProblemDetail problem = problems.create(ErrorCode.SESSION_REVOKED, request);
                yield ResponseEntity.status(problem.getStatus())
                        .headers(problems.headersFor(problem))
                        .header(HttpHeaders.SET_COOKIE, cookies.clear())
                        .body(problem);
            }
        };
    }

    @PostMapping("/logout")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Void> logout(@CookieValue(name = RefreshCookies.NAME, required = false) String refreshToken,
            HttpServletRequest request) {
        guard.check(request);
        authentication.logout(refreshToken);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear()).build();
    }

    private ResponseEntity<TokenResponse> withSession(IssuedSession session) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.issue(session.refreshToken(), session.refreshTokenTtl()))
                .body(new TokenResponse(session.accessToken().value(), session.accessToken().expiresInSeconds()));
    }
}
