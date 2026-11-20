package com.fooyre.identity.api;

import com.fooyre.shared.config.AuthProperties;
import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Cookie du refresh token (ADR-04) : {@code fooyre_refresh}, {@code HttpOnly},
 * {@code SameSite=Strict}, {@code Path=/api/v1/auth}, {@code Secure} selon
 * {@code AUTH_COOKIE_SECURE} (obligatoire en production). Jamais lisible par JavaScript.
 */
@Component
class RefreshCookies {

    static final String NAME = "fooyre_refresh";
    static final String PATH = "/api/v1/auth";

    private final boolean secure;

    RefreshCookies(AuthProperties properties) {
        this.secure = properties.cookieSecure();
    }

    String issue(String token, Duration ttl) {
        return base(token).maxAge(ttl).build().toString();
    }

    String clear() {
        return base("").maxAge(Duration.ZERO).build().toString();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(PATH);
    }
}
