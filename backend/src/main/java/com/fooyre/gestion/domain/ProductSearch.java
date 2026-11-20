package com.fooyre.gestion.domain;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Critères de recherche du catalogue, traduits en requête paramétrée (aucune concaténation SQL).
 * L'entreprise est TOUJOURS un critère obligatoire (en plus de la RLS).
 *
 * @param text       texte cherché dans le nom, le SKU ou le code-barres (sans tenir compte de la casse)
 * @param categoryId catégorie ({@code null} : toutes)
 * @param status     statut ({@code null} : tous)
 */
public record ProductSearch(String text, UUID categoryId, CatalogStatus status) {

    private static final char ESCAPE = '\\';

    public Specification<Product> forBusiness(UUID businessId) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("businessId"), businessId));
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            if (categoryId != null) {
                predicates.add(builder.equal(root.get("categoryId"), categoryId));
            }
            String normalized = CatalogText.optional(text);
            if (normalized != null) {
                String pattern = "%" + escapeLike(normalized.toLowerCase(Locale.ROOT)) + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("name")), pattern, ESCAPE),
                        builder.like(builder.lower(root.get("sku")), pattern, ESCAPE),
                        builder.like(builder.lower(root.get("barcode")), pattern, ESCAPE)));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    /** Les caractères génériques saisis ({@code %}, {@code _}) sont cherchés tels quels. */
    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
