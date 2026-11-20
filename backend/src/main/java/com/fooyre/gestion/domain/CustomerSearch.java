package com.fooyre.gestion.domain;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Critères de recherche des clients, traduits en requête paramétrée (aucune concaténation SQL).
 * L'entreprise est TOUJOURS un critère obligatoire (en plus de la RLS).
 *
 * <p>Le texte est cherché dans le nom et l'e-mail (sans tenir compte de la casse) et, s'il
 * contient au moins {@value #MIN_PHONE_DIGITS} chiffres, dans les CHIFFRES du téléphone :
 * « 77 123 », « 77123 » ou « +221 77 123 » retrouvent le numéro « +221 77 123 45 67 ».
 *
 * @param text   texte cherché ({@code null} : aucun filtre)
 * @param status statut ({@code null} : tous)
 */
public record CustomerSearch(String text, CatalogStatus status) {

    static final int MIN_PHONE_DIGITS = 3;
    private static final char ESCAPE = '\\';

    public Specification<Customer> forBusiness(UUID businessId) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("businessId"), businessId));
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            String normalized = CatalogText.optional(text);
            if (normalized != null) {
                String pattern = "%" + ProductSearch.escapeLike(normalized.toLowerCase(Locale.ROOT)) + "%";
                List<Predicate> any = new ArrayList<>();
                any.add(builder.like(builder.lower(root.get("name")), pattern, ESCAPE));
                any.add(builder.like(root.get("email"), pattern, ESCAPE));
                // Préfixe international « 00 » équivalent à « + » : « 00221… » retrouve « +221… ».
                String digits = normalized.replaceAll("[^0-9]", "").replaceFirst("^00", "");
                if (digits.length() >= MIN_PHONE_DIGITS) {
                    any.add(builder.like(root.get("phoneDigits"), "%" + digits + "%"));
                }
                predicates.add(builder.or(any.toArray(Predicate[]::new)));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }
}
