package com.fooyre.architecture.fixtures.catalog.application;

import com.fooyre.architecture.fixtures.catalog.domain.ItemRepository;
import java.util.UUID;

/** Classe FAUTIVE de test : lecture par identifiant seul, sans filtre d'entreprise. */
public class BadCatalogService {

    Object unscopedRead(ItemRepository items, UUID id) {
        return items.findById(id);
    }
}
