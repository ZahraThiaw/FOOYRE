package com.fooyre.shared.tenant;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.UUID;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Transmet le contexte d'entreprise à PostgreSQL au DÉBUT de chaque transaction, pour les
 * politiques Row Level Security : {@code set_config('app.business_id', <id ou ''>, true)}.
 * <ul>
 *   <li>{@code is_local = true} : la valeur n'existe que dans la transaction ; elle disparaît au
 *       {@code COMMIT}/{@code ROLLBACK}, AVANT que la connexion retourne dans le pool — aucune
 *       fuite possible vers la requête suivante qui réutilise la connexion.</li>
 *   <li>La valeur est TOUJOURS écrite, vide sans entreprise active : une valeur résiduelle de
 *       niveau session ne peut pas être héritée.</li>
 *   <li>L'entreprise vient uniquement de {@link TenantContext} (jeton vérifié en base).</li>
 * </ul>
 * Hors transaction (auto‑commit), la variable n'est pas définie : les politiques RLS, écrites
 * avec {@code NULLIF(current_setting('app.business_id', true), '')}, ne laissent alors voir
 * AUCUNE ligne (échec fermé). Règle : tout accès aux données métier passe par une transaction.
 */
public class TenantAwareJpaTransactionManager extends JpaTransactionManager {

    public static final String SETTING = "app.business_id";

    public TenantAwareJpaTransactionManager(EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        EntityManagerHolder holder = (EntityManagerHolder) TransactionSynchronizationManager.getResource(
                obtainEntityManagerFactory());
        EntityManager entityManager = holder.getEntityManager();
        String businessId = TenantContext.currentUser()
                .filter(user -> user.hasActiveBusiness())
                .map(user -> user.businessId())
                .map(UUID::toString)
                .orElse("");
        entityManager.createNativeQuery("SELECT set_config('" + SETTING + "', :businessId, true)")
                .setParameter("businessId", businessId)
                .getSingleResult();
    }
}
