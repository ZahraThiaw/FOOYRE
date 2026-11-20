package com.fooyre.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Refresh tokens opaques (ADR-04) : 256 bits aléatoires ({@link SecureRandom}) encodés en
 * Base64 URL sans remplissage ; stockés uniquement sous forme de haché SHA-256 (hexadécimal).
 */
final class RefreshTokenCodec {

    private static final int TOKEN_BYTES = 32;
    /** 32 octets en Base64 URL sans remplissage = 43 caractères. */
    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private RefreshTokenCodec() {
    }

    static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Vrai si la valeur a la forme d'un jeton émis (évite de hacher des valeurs arbitraires). */
    static boolean isWellFormed(String token) {
        return token != null && FORMAT.matcher(token).matches();
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 indisponible", impossible);
        }
    }
}
