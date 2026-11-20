package com.fooyre.business.application;

import java.util.UUID;

/** Profil de l'entreprise active (informations générales, aucune donnée financière). */
public record BusinessProfileView(
        UUID id,
        String name,
        String legalName,
        String currency,
        String timezone,
        String defaultLocale) {
}
