package com.fooyre.identity.domain;

/**
 * Dimensions de la limitation des connexions.
 * <ul>
 *   <li>{@code ACCOUNT_IP} : e-mail normalisé + IP — seuil bas, cible le cas courant ;</li>
 *   <li>{@code ACCOUNT} : e-mail normalisé, toutes IP — attaque distribuée sur un compte ;</li>
 *   <li>{@code IP} : toutes adresses e-mail depuis une IP — essais sur de nombreux comptes.</li>
 * </ul>
 */
public enum LoginThrottleKeyType {
    ACCOUNT_IP,
    ACCOUNT,
    IP
}
