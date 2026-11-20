package com.fooyre.shared.security;

import com.fooyre.shared.config.AuthProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Empreintes non réversibles (HMAC-SHA256, hexadécimal) des valeurs personnelles qu'il faut
 * pouvoir comparer sans les stocker : e-mail normalisé, adresse IP. La clé est DÉRIVÉE de la clé
 * applicative ({@code JWT_SECRET}) et séparée par usage ({@code purpose}) : une base divulguée ne
 * permet pas de retrouver les valeurs par dictionnaire. Changer la clé applicative remet ces
 * empreintes à zéro (compteurs de connexion), ce qui est acceptable.
 */
@Component
public class KeyedHasher {

    private final byte[] key;

    public KeyedHasher(AuthProperties properties) {
        try {
            this.key = MessageDigest.getInstance("SHA-256").digest(concat(
                    "fooyre-keyed-hasher-v1".getBytes(StandardCharsets.UTF_8), properties.jwtSecretBytes()));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("SHA-256 indisponible", impossible);
        }
    }

    /** Empreinte de {@code value} pour l'usage {@code purpose} (64 caractères hexadécimaux). */
    public String hash(String purpose, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(purpose.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HMAC-SHA256 indisponible", impossible);
        }
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
