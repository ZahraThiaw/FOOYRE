package com.fooyre.shared.web;

import com.fooyre.shared.error.FieldErrorCode;
import com.fooyre.shared.error.FieldValidationException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Construction d'une pagination conforme au contrat (docs/api-specification.md §1.2) :
 * {@code page} ≥ 0, {@code size} de 1 à {@value #MAX_SIZE} (défaut {@value #DEFAULT_SIZE}),
 * tri {@code champ,asc|desc} limité à une LISTE BLANCHE (champ API → propriété interne).
 * Toute valeur hors limites est refusée ({@code 400 VALIDATION_ERROR}), jamais corrigée en silence.
 */
public final class PageRequests {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private PageRequests() {
    }

    /**
     * @param sort        ex. {@code name,asc} ; {@code null} → {@code defaultSort}
     * @param sortable    champs API autorisés → propriétés JPA
     * @param tieBreaker  propriété ajoutée en dernier critère (ordre stable entre les pages)
     * @param textFields  propriétés triées sans tenir compte de la casse
     */
    public static Pageable of(Integer page, Integer size, String sort, Map<String, String> sortable, Sort defaultSort,
            String tieBreaker, Set<String> textFields) {
        int number = page == null ? 0 : page;
        int pageSize = size == null ? DEFAULT_SIZE : size;
        if (number < 0) {
            throw FieldValidationException.of("page", FieldErrorCode.INVALID_VALUE);
        }
        if (pageSize < 1 || pageSize > MAX_SIZE) {
            throw FieldValidationException.of("size", FieldErrorCode.INVALID_VALUE);
        }
        Sort order = sort == null || sort.isBlank() ? defaultSort : parse(sort, sortable, textFields);
        return PageRequest.of(number, pageSize, order.and(Sort.by(Sort.Direction.ASC, tieBreaker)));
    }

    private static Sort parse(String sort, Map<String, String> sortable, Set<String> textFields) {
        String[] parts = sort.split(",", -1);
        if (parts.length > 2 || !sortable.containsKey(parts[0].trim())) {
            throw FieldValidationException.of("sort", FieldErrorCode.INVALID_VALUE);
        }
        Sort.Direction direction = Sort.Direction.ASC;
        if (parts.length == 2) {
            switch (parts[1].trim().toLowerCase(Locale.ROOT)) {
                case "asc" -> direction = Sort.Direction.ASC;
                case "desc" -> direction = Sort.Direction.DESC;
                default -> throw FieldValidationException.of("sort", FieldErrorCode.INVALID_VALUE);
            }
        }
        String property = sortable.get(parts[0].trim());
        Sort.Order order = new Sort.Order(direction, property);
        return Sort.by(textFields.contains(property) ? order.ignoreCase() : order);
    }
}
