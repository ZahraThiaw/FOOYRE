package com.fooyre.identity.api;

import com.fooyre.shared.config.FooyreProperties;
import com.fooyre.shared.error.ApiException;
import com.fooyre.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * Protection CSRF des routes d'authentification qui lisent ou posent le cookie de
 * rafraîchissement (ADR-04) : en‑tête {@code Origin} égal à l'origine publique de l'application
 * ({@code APP_PUBLIC_BASE_URL}) ET en‑tête {@code X-Requested-With: fooyre}. À défaut :
 * {@code 403 CSRF_CHECK_FAILED}. Complète {@code SameSite=Strict} et le {@code Path} restreint
 * du cookie ; appliquée aussi à la connexion (protection contre la connexion forcée).
 */
@Component
class CookieRouteGuard {

    static final String REQUESTED_WITH_HEADER = "X-Requested-With";
    static final String REQUESTED_WITH_VALUE = "fooyre";

    private final String expectedOrigin;

    CookieRouteGuard(FooyreProperties properties) {
        this.expectedOrigin = originOf(properties.publicBaseUrl());
    }

    void check(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        String requestedWith = request.getHeader(REQUESTED_WITH_HEADER);
        if (origin == null || !expectedOrigin.equals(origin.trim().toLowerCase(Locale.ROOT))
                || !REQUESTED_WITH_VALUE.equals(requestedWith)) {
            throw new ApiException(ErrorCode.CSRF_CHECK_FAILED);
        }
    }

    /** Origine (schéma://hôte[:port]) en minuscules, port par défaut omis, comme l'envoie un navigateur. */
    static String originOf(URI url) {
        String scheme = url.getScheme().toLowerCase(Locale.ROOT);
        String host = url.getHost().toLowerCase(Locale.ROOT);
        int port = url.getPort();
        boolean defaultPort = port == -1 || ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + host + (defaultPort ? "" : ":" + port);
    }
}
