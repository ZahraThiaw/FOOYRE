package com.fooyre.gestion.infrastructure;

import com.fooyre.gestion.domain.CatalogText;
import com.fooyre.gestion.domain.Product;
import com.fooyre.gestion.domain.ProductStock;
import com.fooyre.gestion.domain.StockOverviewRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

/**
 * Requêtes de la vue d'ensemble du stock (JPQL paramétré). Seuls des fragments FIXES sont assemblés
 * (critères et tri issus de listes fermées) ; toute valeur saisie est un paramètre lié — jamais
 * concaténée. Produits ACTIFS uniquement ; jointure externe sur le stock (même entreprise).
 */
@Repository
class JpaStockOverviewRepository implements StockOverviewRepository {

    /** Tri autorisé : propriété de l'API → expression JPQL fixe. */
    private static final Map<String, String> ORDER_EXPRESSIONS = Map.of(
            "name", "lower(p.name)",
            "quantity", "s.quantity",
            "updatedAt", "s.updatedAt");

    /** Commence par un espace : concaténé après la liste des colonnes. */
    private static final String FROM = " FROM Product p LEFT JOIN ProductStock s"
            + " ON s.productId = p.id AND s.businessId = p.businessId"
            + " WHERE p.businessId = :businessId AND p.status = com.fooyre.gestion.domain.CatalogStatus.ACTIVE";

    private final EntityManager entityManager;

    JpaStockOverviewRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Page<StockLine> search(Criteria criteria, Pageable pageable) {
        StringBuilder where = new StringBuilder();
        String text = CatalogText.optional(criteria.text());
        if (text != null) {
            where.append(" AND (lower(p.name) LIKE :pattern ESCAPE '\\' OR lower(p.sku) LIKE :pattern ESCAPE '\\')");
        }
        if (criteria.categoryId() != null) {
            where.append(" AND p.categoryId = :categoryId");
        }
        if (criteria.status() != null) {
            where.append(switch (criteria.status()) {
                case NOT_INITIALIZED -> " AND s.id IS NULL";
                case TRACKED -> " AND s.id IS NOT NULL";
                case IN_STOCK -> " AND s.quantity > s.minimumQuantity";
                case LOW_STOCK -> " AND s.quantity > 0 AND s.quantity <= s.minimumQuantity";
                case OUT_OF_STOCK -> " AND s.quantity <= 0";
                case ALERT -> " AND s.quantity <= s.minimumQuantity";
            });
        }

        TypedQuery<Object[]> query = entityManager.createQuery(
                "SELECT p, s" + FROM + where + orderBy(pageable.getSort()), Object[].class);
        TypedQuery<Long> count = entityManager.createQuery("SELECT count(p)" + FROM + where, Long.class);
        for (TypedQuery<?> q : List.of(query, count)) {
            q.setParameter("businessId", criteria.businessId());
            if (text != null) {
                q.setParameter("pattern", "%" + escapeLike(text.toLowerCase(Locale.ROOT)) + "%");
            }
            if (criteria.categoryId() != null) {
                q.setParameter("categoryId", criteria.categoryId());
            }
        }
        query.setFirstResult((int) pageable.getOffset());
        query.setMaxResults(pageable.getPageSize());
        List<StockLine> lines = new ArrayList<>();
        for (Object[] row : query.getResultList()) {
            lines.add(new StockLine((Product) row[0], (ProductStock) row[1]));
        }
        return new PageImpl<>(lines, pageable, count.getSingleResult());
    }

    @Override
    public Counts counts(UUID businessId) {
        Object[] row = entityManager.createQuery("""
                SELECT count(p), count(s.id),
                       coalesce(sum(CASE WHEN s.quantity > s.minimumQuantity THEN 1 ELSE 0 END), 0),
                       coalesce(sum(CASE WHEN s.quantity > 0 AND s.quantity <= s.minimumQuantity THEN 1 ELSE 0 END), 0),
                       coalesce(sum(CASE WHEN s.quantity <= 0 THEN 1 ELSE 0 END), 0)""" + FROM, Object[].class)
                .setParameter("businessId", businessId)
                .getSingleResult();
        return new Counts(number(row[0]), number(row[1]), number(row[2]), number(row[3]), number(row[4]));
    }

    @Override
    public List<ValuationLine> positiveStockValuationLines(UUID businessId) {
        return entityManager.createQuery("SELECT s.quantity, p.purchasePrice" + FROM + " AND s.quantity > 0",
                        Object[].class)
                .setParameter("businessId", businessId)
                .getResultList().stream()
                .map(row -> new ValuationLine((BigDecimal) row[0], (BigDecimal) row[1]))
                .toList();
    }

    /** Tri à partir de la liste fermée ; les produits non initialisés en dernier pour les tris de stock. */
    private static String orderBy(Sort sort) {
        List<String> orders = new ArrayList<>();
        for (Sort.Order order : sort) {
            String expression = ORDER_EXPRESSIONS.get(order.getProperty());
            if (expression != null) {
                String direction = order.isAscending() ? " ASC" : " DESC";
                orders.add(expression + direction + (expression.startsWith("s.") ? " NULLS LAST" : ""));
            }
        }
        orders.add("p.id ASC"); // ordre stable entre les pages
        return " ORDER BY " + String.join(", ", orders);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }
}
