package com.fooyre.shared.security;

/** Jeton d'accès émis et sa durée de validité en secondes. Jamais journalisé. */
public record IssuedAccessToken(String value, long expiresInSeconds) {

    @Override
    public String toString() {
        return "IssuedAccessToken[value=<masqué>, expiresInSeconds=" + expiresInSeconds + "]";
    }
}
