package com.fooyre.identity.domain;

/** Statut d'un compte : seul un compte {@code ACTIVE} peut se connecter et utiliser ses jetons. */
public enum UserAccountStatus {
    ACTIVE,
    LOCKED,
    DISABLED
}
