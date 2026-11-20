package com.fooyre.identity.application;

/**
 * Issue d'un renouvellement de session. Un refus n'est pas une exception : une révocation
 * décidée pendant le traitement (réutilisation détectée, compte désactivé…) doit être
 * enregistrée même si la réponse est un refus.
 */
public sealed interface RefreshOutcome {

    record Renewed(IssuedSession session) implements RefreshOutcome {
    }

    /** Session absente, expirée, révoquée ou jeton réutilisé : {@code 401}, cookie supprimé. */
    record Rejected() implements RefreshOutcome {
    }
}
