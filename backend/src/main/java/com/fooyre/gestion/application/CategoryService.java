package com.fooyre.gestion.application;

import com.fooyre.gestion.domain.CatalogStatus;
import com.fooyre.gestion.domain.CatalogText;
import com.fooyre.gestion.domain.Category;
import com.fooyre.gestion.domain.CategoryRepository;
import com.fooyre.gestion.domain.ProductRepository;
import com.fooyre.shared.audit.AuditAction;
import com.fooyre.shared.audit.AuditEvent;
import com.fooyre.shared.audit.AuditOutcome;
import com.fooyre.shared.audit.AuditService;
import com.fooyre.shared.audit.AuditTarget;
import com.fooyre.shared.error.ApiException;
import com.fooyre.shared.error.ErrorCode;
import com.fooyre.shared.error.FieldErrorCode;
import com.fooyre.shared.error.FieldValidationException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Catégories du catalogue. Règles :
 * <ul>
 *   <li>nom obligatoire, espaces superflus supprimés, unique (sans tenir compte de la casse) parmi
 *       les catégories ACTIVES de l'entreprise — deux entreprises peuvent utiliser le même nom ;</li>
 *   <li>archivage autorisé même si des produits y sont rattachés : l'association est CONSERVÉE
 *       (historique) ; la catégorie archivée ne peut plus être choisie pour un produit et n'est
 *       plus modifiable ; aucune suppression physique ;</li>
 *   <li>une catégorie d'une autre entreprise est introuvable ({@code 404}).</li>
 * </ul>
 */
@Service
public class CategoryService {

    /** Contrainte d'unicité du nom (migration V3). */
    static final String NAME_UNIQUE_INDEX = "ux_category_business_active_name";

    public record CategoryCommand(String name, String description) {
    }

    private final CategoryRepository categories;
    private final ProductRepository products;
    private final CatalogContext context;
    private final AuditService audit;
    private final Clock clock;

    CategoryService(CategoryRepository categories, ProductRepository products, CatalogContext context,
            AuditService audit, Clock clock) {
        this.categories = categories;
        this.products = products;
        this.context = context;
        this.audit = audit;
        this.clock = clock;
    }

    /** @param status {@code null} : toutes les catégories */
    @Transactional(readOnly = true)
    public Page<CategoryView> list(CatalogStatus status, Pageable pageable) {
        UUID businessId = context.current().businessId();
        Page<Category> page = status == null
                ? categories.findByBusinessId(businessId, pageable)
                : categories.findByBusinessIdAndStatus(businessId, status, pageable);
        Map<UUID, Long> counts = activeProductCounts(businessId, page.getContent().stream().map(Category::getId).toList());
        return page.map(category -> toView(category, counts.getOrDefault(category.getId(), 0L)));
    }

    @Transactional(readOnly = true)
    public CategoryView get(UUID id) {
        UUID businessId = context.current().businessId();
        return toView(find(id, businessId), activeProductCounts(businessId, List.of(id)).getOrDefault(id, 0L));
    }

    @Transactional
    public CategoryView create(CategoryCommand command) {
        CatalogContext.Current current = context.current();
        String name = validName(command.name());
        ensureNameAvailable(current.businessId(), name, null);
        Category category = saveChecked(new Category(name, command.description(), Instant.now(clock)));
        audit.record(event(AuditAction.CATEGORY_CREATED, current, category.getId()));
        return toView(category, 0);
    }

    @Transactional
    public CategoryView update(UUID id, int expectedVersion, CategoryCommand command) {
        CatalogContext.Current current = context.current();
        Category category = find(id, current.businessId());
        ensureVersion(category.getVersion(), expectedVersion);
        if (!category.isActive()) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION);
        }
        String name = validName(command.name());
        ensureNameAvailable(current.businessId(), name, id);
        category.rename(name, command.description(), Instant.now(clock));
        saveChecked(category);
        audit.record(event(AuditAction.CATEGORY_UPDATED, current, id));
        return toView(category, activeProductCounts(current.businessId(), List.of(id)).getOrDefault(id, 0L));
    }

    /** Archive la catégorie (idempotent : aucun événement si elle l'était déjà). */
    @Transactional
    public void archive(UUID id) {
        CatalogContext.Current current = context.current();
        Category category = find(id, current.businessId());
        if (category.archive(Instant.now(clock))) {
            categories.saveAndFlush(category);
            audit.record(event(AuditAction.CATEGORY_ARCHIVED, current, id));
        }
    }

    private Category find(UUID id, UUID businessId) {
        return categories.findByIdAndBusinessId(id, businessId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private static String validName(String raw) {
        String name = CatalogText.normalizeName(raw);
        if (name == null || name.isEmpty()) {
            throw FieldValidationException.of("name", FieldErrorCode.REQUIRED);
        }
        if (name.length() > Category.MAX_NAME_LENGTH) {
            throw FieldValidationException.of("name", FieldErrorCode.TOO_LONG);
        }
        return name;
    }

    private void ensureNameAvailable(UUID businessId, String name, UUID excludedId) {
        if (categories.activeNameExists(businessId, name, excludedId)) {
            throw FieldValidationException.of("name", FieldErrorCode.ALREADY_EXISTS);
        }
    }

    static void ensureVersion(int actual, int expected) {
        if (actual != expected) {
            throw new ApiException(ErrorCode.OPTIMISTIC_LOCK);
        }
    }

    /** Écriture immédiate : une création concurrente du même nom est traduite en erreur de champ. */
    private Category saveChecked(Category category) {
        try {
            return categories.saveAndFlush(category);
        } catch (DataIntegrityViolationException conflict) {
            if (String.valueOf(conflict.getMostSpecificCause().getMessage()).contains(NAME_UNIQUE_INDEX)) {
                throw FieldValidationException.of("name", FieldErrorCode.ALREADY_EXISTS);
            }
            throw conflict;
        }
    }

    private Map<UUID, Long> activeProductCounts(UUID businessId, List<UUID> categoryIds) {
        Map<UUID, Long> counts = new HashMap<>();
        if (!categoryIds.isEmpty()) {
            for (Object[] row : products.countActiveByCategory(businessId, categoryIds)) {
                counts.put((UUID) row[0], (Long) row[1]);
            }
        }
        return counts;
    }

    private static AuditEvent event(AuditAction action, CatalogContext.Current current, UUID categoryId) {
        return AuditEvent.of(action, AuditOutcome.SUCCESS)
                .actor(current.actorId())
                .business(current.businessId())
                .target(AuditTarget.CATEGORY, categoryId);
    }

    private static CategoryView toView(Category category, long activeProductCount) {
        return new CategoryView(category.getId(), category.getName(), category.getDescription(), category.getStatus(),
                activeProductCount, category.getCreatedAt(), category.getUpdatedAt(), category.getVersion());
    }
}
