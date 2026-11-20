package com.fooyre.architecture.fixtures.catalog.domain;

import java.util.UUID;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.NoRepositoryBean;

/** Repository FICTIF de test (jamais instancié par Spring : {@code @NoRepositoryBean}). */
@NoRepositoryBean
public interface ItemRepository extends CrudRepository<Object, UUID> {
}
