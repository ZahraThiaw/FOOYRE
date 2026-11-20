package com.fooyre.shared.tenant;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Remplace le gestionnaire de transactions JPA de Spring Boot (même nom de bean,
 * {@code transactionManager}) par {@link TenantAwareJpaTransactionManager}. JdbcTemplate et les
 * repositories Spring Data utilisent ce même gestionnaire, donc la même connexion et le même
 * contexte d'entreprise.
 */
@Configuration(proxyBeanMethods = false)
public class TenantTransactionConfiguration {

    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory,
            ObjectProvider<TransactionManagerCustomizers> customizers) {
        TenantAwareJpaTransactionManager transactionManager = new TenantAwareJpaTransactionManager(entityManagerFactory);
        customizers.ifAvailable(available -> available.customize(transactionManager));
        return transactionManager;
    }
}
