package com.fooyre.gestion.application;

import com.fooyre.gestion.domain.CatalogStatus;
import com.fooyre.gestion.domain.CatalogText;
import com.fooyre.gestion.domain.Category;
import com.fooyre.gestion.domain.CategoryRepository;
import com.fooyre.gestion.domain.MeasurementUnit;
import com.fooyre.gestion.domain.Product;
import com.fooyre.gestion.domain.ProductDetails;
import com.fooyre.gestion.domain.ProductRepository;
import com.fooyre.gestion.domain.ProductSearch;
import com.fooyre.gestion.domain.ProductStockRepository;
import com.fooyre.shared.audit.AuditAction;
import com.fooyre.shared.audit.AuditEvent;
import com.fooyre.shared.audit.AuditOutcome;
import com.fooyre.shared.audit.AuditService;
import com.fooyre.shared.audit.AuditTarget;
import com.fooyre.shared.error.ApiException;
import com.fooyre.shared.error.ErrorCode;
import com.fooyre.shared.error.FieldErrorCode;
import com.fooyre.shared.error.FieldValidationException;
import com.fooyre.shared.money.CurrencyAmounts;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Produits du catalogue. Règles :
 * <ul>
 *   <li>nom obligatoire ; unité de mesure obligatoire (liste fermée) ;</li>
 *   <li>prix de vente obligatoire, ≥ 0 (0 admis : article offert, échantillon), sans plus de
 *       décimales que la devise de l'entreprise (XOF : aucune) — refusé, jamais arrondi ;</li>
 *   <li>prix d'achat de RÉFÉRENCE facultatif, ≥ 0, MÊME précision que le prix de vente : au plus
 *       les décimales de la devise (XOF : aucune) — refusé, jamais arrondi (décision de la porteuse
 *       du projet, 2026-11-19) ; ce n'est pas le coût moyen pondéré ; visible et modifiable
 *       uniquement avec {@code COST_READ} ;</li>
 *   <li>SKU (sans tenir compte de la casse) et code-barres facultatifs, uniques parmi les produits
 *       ACTIFS de l'entreprise ;</li>
 *   <li>catégorie facultative, de la MÊME entreprise ({@code 404} sinon) et active — une catégorie
 *       archivée n'est conservée que si le produit y était déjà rattaché ;</li>
 *   <li>un produit archivé n'est plus modifiable ; aucune suppression physique ;</li>
 *   <li>unité verrouillée dès que le stock du produit est initialisé (MVP-3).</li>
 * </ul>
 */
@Service
public class ProductService {

    static final String SKU_UNIQUE_INDEX = "ux_product_business_active_sku";
    static final String BARCODE_UNIQUE_INDEX = "ux_product_business_active_barcode";

    private static final Pattern SKU_FORMAT = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N} ._/-]*");
    private static final Pattern BARCODE_FORMAT = Pattern.compile("[A-Za-z0-9-]+");

    /**
     * Données saisies. {@code purchasePrice} n'est pris en compte que si l'utilisateur a
     * {@code COST_READ} ; sinon la valeur enregistrée est conservée.
     */
    public record ProductCommand(
            String name,
            String description,
            String sku,
            String barcode,
            UUID categoryId,
            MeasurementUnit unit,
            BigDecimal purchasePrice,
            BigDecimal salePrice) {
    }

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final ProductStockRepository stocks;
    private final CatalogContext context;
    private final AuditService audit;
    private final Clock clock;

    ProductService(ProductRepository products, CategoryRepository categories, ProductStockRepository stocks,
            CatalogContext context, AuditService audit, Clock clock) {
        this.products = products;
        this.categories = categories;
        this.stocks = stocks;
        this.context = context;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<ProductView> search(ProductSearch criteria, Pageable pageable) {
        CatalogContext.Current current = context.current();
        Page<Product> page = products.findAll(criteria.forBusiness(current.businessId()), pageable);
        Map<UUID, Category> categoriesById = categoriesOf(current.businessId(), page.getContent());
        return page.map(product -> toView(product,
                product.getCategoryId() == null ? null : categoriesById.get(product.getCategoryId()), current));
    }

    @Transactional(readOnly = true)
    public ProductView get(UUID id) {
        CatalogContext.Current current = context.current();
        Product product = find(id, current.businessId());
        return toView(product, categoryOf(product, current.businessId()), current);
    }

    @Transactional
    public ProductView create(ProductCommand command) {
        CatalogContext.Current current = context.current();
        ProductDetails details = validate(command, null, current, null);
        Product product = saveChecked(new Product(details, Instant.now(clock)));
        audit.record(event(AuditAction.PRODUCT_CREATED, current, product.getId()));
        return toView(product, categoryOf(product, current.businessId()), current);
    }

    @Transactional
    public ProductView update(UUID id, int expectedVersion, ProductCommand command) {
        CatalogContext.Current current = context.current();
        Product product = find(id, current.businessId());
        CategoryService.ensureVersion(product.getVersion(), expectedVersion);
        if (!product.isActive()) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION);
        }
        product.apply(validate(command, id, current, product), Instant.now(clock));
        saveChecked(product);
        audit.record(event(AuditAction.PRODUCT_UPDATED, current, id));
        return toView(product, categoryOf(product, current.businessId()), current);
    }

    /** Archive le produit (idempotent : aucun événement s'il l'était déjà). L'historique est conservé. */
    @Transactional
    public void archive(UUID id) {
        CatalogContext.Current current = context.current();
        Product product = find(id, current.businessId());
        if (product.archive(Instant.now(clock))) {
            products.saveAndFlush(product);
            audit.record(event(AuditAction.PRODUCT_ARCHIVED, current, id));
        }
    }

    // --- Validation ---------------------------------------------------------------------------------

    private ProductDetails validate(ProductCommand command, UUID productId, CatalogContext.Current current,
            Product existing) {
        Map<String, FieldErrorCode> errors = new LinkedHashMap<>();

        String name = CatalogText.normalizeName(command.name());
        if (name == null || name.isEmpty()) {
            errors.put("name", FieldErrorCode.REQUIRED);
        } else if (name.length() > Product.MAX_NAME_LENGTH) {
            errors.put("name", FieldErrorCode.TOO_LONG);
        }
        String description = CatalogText.optional(command.description());
        if (description != null && description.length() > Product.MAX_DESCRIPTION_LENGTH) {
            errors.put("description", FieldErrorCode.TOO_LONG);
        }
        String sku = CatalogText.optional(command.sku());
        if (sku != null && (sku.length() > Product.MAX_CODE_LENGTH || !SKU_FORMAT.matcher(sku).matches())) {
            errors.put("sku", sku.length() > Product.MAX_CODE_LENGTH ? FieldErrorCode.TOO_LONG : FieldErrorCode.INVALID_FORMAT);
        }
        String barcode = CatalogText.optional(command.barcode());
        if (barcode != null && (barcode.length() > Product.MAX_CODE_LENGTH || !BARCODE_FORMAT.matcher(barcode).matches())) {
            errors.put("barcode", barcode.length() > Product.MAX_CODE_LENGTH ? FieldErrorCode.TOO_LONG : FieldErrorCode.INVALID_FORMAT);
        }
        if (command.unit() == null) {
            errors.put("unit", FieldErrorCode.REQUIRED);
        } else if (existing != null && command.unit() != existing.getUnit()
                && stocks.existsByBusinessIdAndProductId(current.businessId(), existing.getId())) {
            // Stock initialisé : changer d'unité fausserait les quantités et l'historique (MVP-3).
            errors.put("unit", FieldErrorCode.LOCKED);
        }
        BigDecimal salePrice = command.salePrice();
        if (salePrice == null) {
            errors.put("salePrice", FieldErrorCode.REQUIRED);
        } else if (salePrice.signum() < 0 || !CurrencyAmounts.fitsCurrency(salePrice, current.currency())) {
            errors.put("salePrice", FieldErrorCode.INVALID_VALUE);
        }
        // Sans COST_READ, le prix d'achat n'est ni lu ni modifiable : la valeur enregistrée est conservée.
        BigDecimal purchasePrice = current.canReadCosts()
                ? command.purchasePrice()
                : existing == null ? null : existing.getPurchasePrice();
        if (current.canReadCosts() && purchasePrice != null
                && (purchasePrice.signum() < 0 || !CurrencyAmounts.fitsCurrency(purchasePrice, current.currency()))) {
            errors.put("purchasePrice", FieldErrorCode.INVALID_VALUE);
        }
        if (sku != null && !errors.containsKey("sku") && products.activeSkuExists(current.businessId(), sku, productId)) {
            errors.put("sku", FieldErrorCode.ALREADY_EXISTS);
        }
        if (barcode != null && !errors.containsKey("barcode")
                && products.activeBarcodeExists(current.businessId(), barcode, productId)) {
            errors.put("barcode", FieldErrorCode.ALREADY_EXISTS);
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
        UUID categoryId = command.categoryId();
        if (categoryId != null) {
            // Catégorie d'une autre entreprise ou inexistante : introuvable (404), sans rien révéler.
            Category category = categories.findByIdAndBusinessId(categoryId, current.businessId())
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            boolean unchanged = existing != null && categoryId.equals(existing.getCategoryId());
            if (!category.isActive() && !unchanged) {
                throw FieldValidationException.of("categoryId", FieldErrorCode.ARCHIVED);
            }
        }
        return new ProductDetails(name, description, sku, barcode, categoryId, command.unit(), purchasePrice,
                CurrencyAmounts.toCurrencyScale(salePrice, current.currency()));
    }

    // --- Accès --------------------------------------------------------------------------------------

    private Product find(UUID id, UUID businessId) {
        return products.findByIdAndBusinessId(id, businessId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private Category categoryOf(Product product, UUID businessId) {
        return product.getCategoryId() == null ? null
                : categories.findByIdAndBusinessId(product.getCategoryId(), businessId).orElse(null);
    }

    private Map<UUID, Category> categoriesOf(UUID businessId, java.util.List<Product> page) {
        java.util.Set<UUID> ids = page.stream().map(Product::getCategoryId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of()
                : categories.findByBusinessIdAndIdIn(businessId, ids).stream()
                        .collect(Collectors.toMap(Category::getId, Function.identity()));
    }

    /** Écriture immédiate : un doublon concurrent est traduit en erreur de champ. */
    private Product saveChecked(Product product) {
        try {
            return products.saveAndFlush(product);
        } catch (DataIntegrityViolationException conflict) {
            String cause = String.valueOf(conflict.getMostSpecificCause().getMessage());
            if (cause.contains(SKU_UNIQUE_INDEX)) {
                throw FieldValidationException.of("sku", FieldErrorCode.ALREADY_EXISTS);
            }
            if (cause.contains(BARCODE_UNIQUE_INDEX)) {
                throw FieldValidationException.of("barcode", FieldErrorCode.ALREADY_EXISTS);
            }
            throw conflict;
        }
    }

    private static AuditEvent event(AuditAction action, CatalogContext.Current current, UUID productId) {
        // Aucune donnée commerciale (prix, coût, nom) : identifiants uniquement.
        return AuditEvent.of(action, AuditOutcome.SUCCESS)
                .actor(current.actorId())
                .business(current.businessId())
                .target(AuditTarget.PRODUCT, productId);
    }

    /**
     * Le prix d'achat n'est JAMAIS renvoyé sans la permission COST_READ (filtrage côté serveur).
     * Affichage : à l'échelle de la devise ; une valeur antérieure à la règle actuelle (ex. 1800.5 en
     * XOF, enregistrée avant le 2026-11-19) est renvoyée telle quelle, sans arrondi ni erreur.
     */
    private static ProductView toView(Product product, Category category, CatalogContext.Current current) {
        BigDecimal purchasePrice = current.canReadCosts() && product.getPurchasePrice() != null
                ? CurrencyAmounts.toUnitCostScale(product.getPurchasePrice(), current.currency())
                : null;
        return new ProductView(product.getId(), product.getName(), product.getDescription(), product.getSku(),
                product.getBarcode(), product.getUnit(), product.getCategoryId(),
                category == null ? null : category.getName(),
                category == null ? null : category.getStatus(),
                purchasePrice, current.canReadCosts(),
                CurrencyAmounts.toCurrencyScale(product.getSalePrice(), current.currency()),
                current.currency(), product.getStatus(), product.getCreatedAt(), product.getUpdatedAt(),
                product.getVersion());
    }

    /** Valeurs de tri autorisées (API → propriété). */
    public static Map<String, String> sortableFields() {
        return Map.of("name", "name", "createdAt", "createdAt", "salePrice", "salePrice");
    }

    /** Statut par défaut de la liste : catalogue actif. */
    public static CatalogStatus defaultStatus() {
        return CatalogStatus.ACTIVE;
    }
}
