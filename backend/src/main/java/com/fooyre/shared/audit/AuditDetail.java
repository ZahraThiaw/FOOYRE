package com.fooyre.shared.audit;

import java.util.regex.Pattern;

/**
 * LISTE BLANCHE des métadonnées d'audit : aucune autre clé ne peut être enregistrée, et chaque
 * valeur doit être un code court (lettres majuscules, chiffres, « _ ») ou un UUID. Une valeur
 * libre (e-mail, nom, mot de passe, jeton, contenu de requête…) est donc impossible par
 * construction : elle est refusée avant toute écriture.
 */
public enum AuditDetail {
    /** Motif codé (ex. {@code BAD_PASSWORD}, {@code MEMBERSHIP_REVOKED}). */
    REASON(Format.CODE),
    /** Limite atteinte ({@code ACCOUNT_IP}, {@code ACCOUNT}, {@code IP}). */
    LIMIT(Format.CODE),
    /** Rôle concerné (ex. {@code BUSINESS_OWNER}). */
    ROLE(Format.CODE),
    /** Identifiant de session ({@code sid}) — jamais le jeton. */
    SESSION_ID(Format.UUID);

    enum Format {
        CODE(Pattern.compile("[A-Z][A-Z0-9_]{0,59}")),
        UUID(Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));

        private final Pattern pattern;

        Format(Pattern pattern) {
            this.pattern = pattern;
        }
    }

    private final Format format;

    AuditDetail(Format format) {
        this.format = format;
    }

    /** Vrai si la valeur respecte le format autorisé pour cette clé. */
    public boolean accepts(String value) {
        return value != null && format.pattern.matcher(value).matches();
    }
}
