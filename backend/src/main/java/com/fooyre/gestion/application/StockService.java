package com.fooyre.gestion.application;

import com.fooyre.business.application.BusinessSettingsService;
import com.fooyre.gestion.application.StockViews.EstimatedValue;
import com.fooyre.gestion.application.StockViews.MovementView;
import com.fooyre.gestion.application.StockViews.OperationResult;
import com.fooyre.gestion.application.StockViews.SaleExit;
import com.fooyre.gestion.application.StockViews.SaleExitLine;
import com.fooyre.gestion.application.StockViews.StockItemView;
import com.fooyre.gestion.application.StockViews.SummaryView;
import com.fooyre.gestion.domain.CatalogText;
import com.fooyre.gestion.domain.Category;
import com.fooyre.gestion.domain.CategoryRepository;
import com.fooyre.gestion.domain.MeasurementUnit;
import com.fooyre.gestion.domain.Product;
import com.fooyre.gestion.domain.ProductRepository;
import com.fooyre.gestion.domain.ProductStock;
import com.fooyre.gestion.domain.ProductStockRepository;
import com.fooyre.gestion.domain.StockMovement;
import com.fooyre.gestion.domain.StockMovementRepository;
import com.fooyre.gestion.domain.StockMovementType;
import com.fooyre.gestion.domain.StockOverviewRepository;
import com.fooyre.gestion.domain.StockStatus;
import com.fooyre.identity.application.UserAccountService;
import com.fooyre.shared.audit.AuditAction;
import com.fooyre.shared.audit.AuditDetail;
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
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock des produits (MVP-3). Chaque opération est ATOMIQUE (une transaction) :
 * <ol>
 *   <li>entreprise et auteur issus du contexte authentifié ({@link CatalogContext}) ;</li>
 *   <li>rejeu d'une même clé d'idempotence : résultat d'origine renvoyé, aucun nouveau mouvement ;
 *       clé réutilisée pour une autre demande : {@code 409 IDEMPOTENCY_KEY_REUSED} ;</li>
 *   <li>produit de l'entreprise ({@code 404} sinon), actif ({@code 422 PRODUCT_ARCHIVED}) ;</li>
 *   <li>quantité strictement positive, précision de l'unité (entier ou 3 décimales), motif ;</li>
 *   <li>stock VERROUILLÉ ({@code SELECT … FOR UPDATE}) : opérations concurrentes sérialisées ;</li>
 *   <li>paramètre « stock négatif » RELU en base ; quantité négative refusée s'il est désactivé
 *       ({@code 409 INSUFFICIENT_STOCK}) ;</li>
 *   <li>stock mis à jour, mouvement immuable inséré, événement d'audit — tout ou rien.</li>
 * </ol>
 * La quantité n'est JAMAIS modifiée sans mouvement.
 */
@Service
public class StockService {

    /** Chiffres entiers maximum d'une quantité (NUMERIC(19,3)). */
    static final int MAX_QUANTITY_INTEGER_DIGITS = 15;
    static final String IDEMPOTENCY_INDEX = "uq_stock_movement_business_idempotency";
    static final String STOCK_UNIQUE_INDEX = "uq_product_stock_business_product";

    /** Opération demandée (hors initialisation). */
    public record MovementCommand(StockMovementType type, BigDecimal quantity, String reason, String reference,
            BigDecimal unitCost) {

        public MovementCommand(StockMovementType type, BigDecimal quantity, String reason, String reference) {
            this(type, quantity, reason, reference, null);
        }
    }

    /** Chiffres entiers maximum d'un coût unitaire (NUMERIC(19,4)). */
    static final int MAX_UNIT_COST_INTEGER_DIGITS = 15;

    public record InitializeCommand(BigDecimal quantity, BigDecimal minimumQuantity, String note) {
    }

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final ProductStockRepository stocks;
    private final StockMovementRepository movements;
    private final StockOverviewRepository overview;
    private final BusinessSettingsService settings;
    private final UserAccountService accounts;
    private final CatalogContext context;
    private final AuditService audit;
    private final Clock clock;

    StockService(ProductRepository products, CategoryRepository categories, ProductStockRepository stocks,
            StockMovementRepository movements, StockOverviewRepository overview, BusinessSettingsService settings,
            UserAccountService accounts, CatalogContext context, AuditService audit, Clock clock) {
        this.products = products;
        this.categories = categories;
        this.stocks = stocks;
        this.movements = movements;
        this.overview = overview;
        this.settings = settings;
        this.accounts = accounts;
        this.context = context;
        this.audit = audit;
        this.clock = clock;
    }

    // ─── Lecture ───────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<StockItemView> search(String text, UUID categoryId, StockOverviewRepository.StatusFilter status,
            Pageable pageable) {
        UUID businessId = context.current().businessId();
        Page<StockOverviewRepository.StockLine> page = overview.search(
                new StockOverviewRepository.Criteria(businessId, text, categoryId, status), pageable);
        Map<UUID, Category> categoriesById = categoriesOf(businessId,
                page.getContent().stream().map(line -> line.product().getCategoryId()).toList());
        return page.map(line -> toView(line.product(), line.stock(),
                line.product().getCategoryId() == null ? null : categoriesById.get(line.product().getCategoryId())));
    }

    @Transactional(readOnly = true)
    public StockItemView get(UUID productId) {
        UUID businessId = context.current().businessId();
        Product product = findProduct(productId, businessId);
        return toView(product, stocks.findByBusinessIdAndProductId(businessId, productId).orElse(null),
                categoryOf(product, businessId));
    }

    @Transactional(readOnly = true)
    public SummaryView summary() {
        CatalogContext.Current current = context.current();
        StockOverviewRepository.Counts counts = overview.counts(current.businessId());
        EstimatedValue value = current.canReadCosts() ? estimatedValue(current) : null;
        return new SummaryView(counts.tracked(), counts.activeProducts() - counts.tracked(), counts.inStock(),
                counts.lowStock(), counts.outOfStock(), value);
    }

    /** @param productId {@code null} : historique de toute l'entreprise */
    @Transactional(readOnly = true)
    public Page<MovementView> movements(UUID productId, Pageable pageable) {
        UUID businessId = context.current().businessId();
        Page<StockMovement> page;
        if (productId == null) {
            page = movements.findByBusinessId(businessId, pageable);
        } else {
            findProduct(productId, businessId); // 404 si le produit n'est pas de l'entreprise
            page = movements.findByBusinessIdAndProductId(businessId, productId, pageable);
        }
        Map<UUID, Product> productsById = page.getContent().stream().map(StockMovement::getProductId).distinct()
                .map(id -> products.findByIdAndBusinessId(id, businessId).orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Product::getId, product -> product));
        Map<UUID, String> authors = accounts.fullNamesOf(
                page.getContent().stream().map(StockMovement::getCreatedBy).collect(Collectors.toSet()));
        boolean canReadCosts = context.current().canReadCosts();
        return page.map(movement -> toView(movement, productsById.get(movement.getProductId()), authors,
                canReadCosts));
    }

    // ─── Opérations ────────────────────────────────────────────────────────────────────────────

    /** Initialise le stock (mouvement INITIAL). Une seule fois : ensuite, ajustements uniquement. */
    @Transactional
    public OperationResult initialize(UUID productId, UUID idempotencyKey, InitializeCommand command) {
        CatalogContext.Current current = context.current();
        String note = CatalogText.optional(command.note());
        String hash = requestHash(productId, "INITIAL", command.quantity(), command.minimumQuantity(), note, null);
        Optional<OperationResult> replay = replay(current.businessId(), idempotencyKey, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        // Produit verrouillé : deux initialisations simultanées sont sérialisées.
        Product product = products.lockByIdAndBusinessId(productId, current.businessId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        ensureActive(product);
        Map<String, FieldErrorCode> errors = new LinkedHashMap<>();
        validateQuantity("quantity", command.quantity(), product.getUnit(), errors);
        BigDecimal minimum = command.minimumQuantity() == null ? BigDecimal.ZERO : command.minimumQuantity();
        validateMinimum("minimumQuantity", minimum, product.getUnit(), errors);
        if (note != null && note.length() > StockMovement.MAX_REASON_LENGTH) {
            errors.put("note", FieldErrorCode.TOO_LONG);
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
        Optional<OperationResult> concurrentReplay = replay(current.businessId(), idempotencyKey, hash);
        if (concurrentReplay.isPresent()) {
            return concurrentReplay.get();
        }
        if (stocks.existsByBusinessIdAndProductId(current.businessId(), productId)) {
            throw new ApiException(ErrorCode.STOCK_ALREADY_INITIALIZED);
        }
        Instant now = clock.instant();
        ProductStock stock = new ProductStock(productId, command.quantity(), minimum, now);
        StockMovement movement = new StockMovement(productId, StockMovementType.INITIAL, command.quantity(),
                BigDecimal.ZERO, command.quantity(), note, null, current.actorId(), now, idempotencyKey, hash);
        try {
            stocks.saveAndFlush(stock);
            movements.saveAndFlush(movement);
        } catch (DataIntegrityViolationException conflict) {
            throw translate(conflict);
        }
        auditMovement(AuditAction.STOCK_INITIALIZED, current, movement);
        return result(movement, product, stock, current, false);
    }

    /**
     * Entrée, sortie ou ajustement (motif obligatoire). Une sortie refusée pour stock insuffisant
     * valide la transaction avec le SEUL événement d'audit ({@link InsufficientStockException}).
     */
    @Transactional(noRollbackFor = InsufficientStockException.class)
    public OperationResult record(UUID productId, UUID idempotencyKey, MovementCommand command) {
        if (command.type() == null || command.type() == StockMovementType.INITIAL
                || command.type() == StockMovementType.SALE_OUT) {
            throw FieldValidationException.of("type", FieldErrorCode.INVALID_VALUE);
        }
        CatalogContext.Current current = context.current();
        String reason = CatalogText.optional(command.reason());
        String reference = CatalogText.optional(command.reference());
        if (command.unitCost() != null) {
            // Coût d'acquisition : ENTRÉES uniquement, et seulement par qui peut voir les coûts.
            if (command.type() != StockMovementType.ENTRY) {
                throw FieldValidationException.of("unitCost", FieldErrorCode.INVALID_VALUE);
            }
            if (!current.canReadCosts()) {
                throw new ApiException(ErrorCode.FORBIDDEN);
            }
        }
        String hash = requestHash(productId, command.type().name(), command.quantity(), null, reason, reference,
                command.unitCost());
        Optional<OperationResult> replay = replay(current.businessId(), idempotencyKey, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Product product = findProduct(productId, current.businessId());
        ensureActive(product);
        Map<String, FieldErrorCode> errors = new LinkedHashMap<>();
        validateQuantity("quantity", command.quantity(), product.getUnit(), errors);
        if (reason == null) {
            errors.put("reason", FieldErrorCode.REQUIRED);
        } else if (reason.length() > StockMovement.MAX_REASON_LENGTH) {
            errors.put("reason", FieldErrorCode.TOO_LONG);
        }
        if (reference != null && reference.length() > StockMovement.MAX_REFERENCE_LENGTH) {
            errors.put("reference", FieldErrorCode.TOO_LONG);
        }
        if (command.unitCost() != null && !validUnitCost(command.unitCost(), current.currency())) {
            errors.put("unitCost", FieldErrorCode.INVALID_VALUE);
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }

        // Verrou sur le stock du produit, puis seconde vérification de la clé (requêtes simultanées).
        ProductStock stock = stocks.lockByBusinessIdAndProductId(current.businessId(), productId)
                .orElseThrow(() -> new ApiException(ErrorCode.STOCK_NOT_INITIALIZED));
        Optional<OperationResult> concurrentReplay = replay(current.businessId(), idempotencyKey, hash);
        if (concurrentReplay.isPresent()) {
            return concurrentReplay.get();
        }

        BigDecimal before = stock.getQuantity();
        BigDecimal after = command.type().increases()
                ? before.add(command.quantity())
                : before.subtract(command.quantity());
        // Seules les diminutions sont contrôlées (une entrée n'est jamais refusée, même si le stock est
        // déjà négatif). Paramètre relu en base au moment de l'opération (D5).
        if (!command.type().increases() && after.signum() < 0 && !settings.allowsNegativeStock()) {
            if (command.type() == StockMovementType.EXIT) {
                // Rien n'a été écrit : la transaction ne validera QUE cet événement (noRollbackFor).
                audit.record(AuditEvent.of(AuditAction.STOCK_EXIT_REJECTED_INSUFFICIENT, AuditOutcome.DENIED)
                        .actor(current.actorId())
                        .business(current.businessId())
                        .target(AuditTarget.PRODUCT, productId)
                        .with(AuditDetail.REASON, ErrorCode.INSUFFICIENT_STOCK.name()));
                throw new InsufficientStockException();
            }
            throw new ApiException(ErrorCode.INSUFFICIENT_STOCK);
        }
        Instant now = clock.instant();
        if (command.type() == StockMovementType.ENTRY) {
            stock.applyEntryCost(command.quantity(), command.unitCost());
        }
        stock.applyQuantity(after, now);
        StockMovement movement = new StockMovement(productId, command.type(), command.quantity(), before, after,
                reason, reference, command.unitCost(), current.actorId(), now, idempotencyKey, hash);
        try {
            stocks.saveAndFlush(stock);
            movements.saveAndFlush(movement);
        } catch (DataIntegrityViolationException conflict) {
            throw translate(conflict);
        }
        auditMovement(switch (command.type()) {
            case ENTRY -> AuditAction.STOCK_ENTRY_RECORDED;
            case EXIT -> AuditAction.STOCK_EXIT_RECORDED;
            default -> AuditAction.STOCK_ADJUSTED;
        }, current, movement);
        return result(movement, product, stock, current, false);
    }

    /**
     * Sorties de stock d'une vente, DANS la transaction de confirmation de la vente (aucune
     * transaction propre) : verrous pris dans l'ordre des identifiants de produit (pas d'interblocage
     * entre deux ventes), même contrôle du stock négatif que les sorties manuelles, un mouvement
     * {@code SALE_OUT} immuable par ligne. Le CMP courant est renvoyé pour être figé sur la ligne.
     * Tout refus lève une exception : la vente entière est annulée (aucune sortie partielle).
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public List<SaleExit> recordSaleExits(String saleReference, List<SaleExitLine> lines) {
        CatalogContext.Current current = context.current();
        boolean allowNegative = settings.allowsNegativeStock();
        List<SaleExitLine> ordered = lines.stream()
                .sorted(java.util.Comparator.comparing(SaleExitLine::productId).thenComparing(SaleExitLine::lineId))
                .toList();
        Map<UUID, SaleExit> results = new LinkedHashMap<>();
        Instant now = clock.instant();
        for (SaleExitLine line : ordered) {
            Product product = findProduct(line.productId(), current.businessId());
            ensureActive(product);
            Map<String, FieldErrorCode> errors = new LinkedHashMap<>();
            validateQuantity("quantity", line.quantity(), product.getUnit(), errors);
            if (!errors.isEmpty()) {
                throw new FieldValidationException(errors);
            }
            ProductStock stock = stocks.lockByBusinessIdAndProductId(current.businessId(), line.productId())
                    .orElseThrow(() -> new ApiException(ErrorCode.STOCK_NOT_INITIALIZED));
            BigDecimal before = stock.getQuantity();
            BigDecimal after = before.subtract(line.quantity());
            if (after.signum() < 0 && !allowNegative) {
                throw new ApiException(ErrorCode.INSUFFICIENT_STOCK);
            }
            BigDecimal unitCost = stock.getAverageCost();
            stock.applyQuantity(after, now);
            String reason = "Vente " + saleReference;
            String hash = requestHash(line.productId(), StockMovementType.SALE_OUT.name(), line.quantity(), null, reason,
                    saleReference);
            StockMovement movement = new StockMovement(line.productId(), StockMovementType.SALE_OUT, line.quantity(),
                    before, after, reason, saleReference, current.actorId(), now, line.lineId(), hash);
            try {
                stocks.saveAndFlush(stock);
                movements.saveAndFlush(movement);
            } catch (DataIntegrityViolationException conflict) {
                throw translate(conflict);
            }
            results.put(line.lineId(), new SaleExit(line.lineId(), movement.getId(), unitCost));
        }
        return lines.stream().map(line -> results.get(line.lineId())).toList();
    }

    /**
     * Quantités et seuils des produits (lecture pour la vente et l'assistante) : {@code null} si le
     * stock n'est pas initialisé.
     */
    @Transactional(readOnly = true)
    public Map<UUID, StockItemView> stockOf(java.util.Collection<UUID> productIds) {
        UUID businessId = context.current().businessId();
        Map<UUID, StockItemView> views = new java.util.HashMap<>();
        for (UUID productId : new java.util.HashSet<>(productIds)) {
            products.findByIdAndBusinessId(productId, businessId).ifPresent(product -> views.put(productId,
                    toView(product, stocks.findByBusinessIdAndProductId(businessId, productId).orElse(null),
                            categoryOf(product, businessId), context.current().canReadCosts())));
        }
        return views;
    }

    /** Seuil d'alerte « stock faible » (≥ 0 ; 0 = aucune alerte avant la rupture). */
    @Transactional
    public StockItemView changeMinimum(UUID productId, BigDecimal minimum) {
        CatalogContext.Current current = context.current();
        Product product = findProduct(productId, current.businessId());
        ensureActive(product);
        Map<String, FieldErrorCode> errors = new LinkedHashMap<>();
        validateMinimum("minimumQuantity", minimum, product.getUnit(), errors);
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
        ProductStock stock = stocks.lockByBusinessIdAndProductId(current.businessId(), productId)
                .orElseThrow(() -> new ApiException(ErrorCode.STOCK_NOT_INITIALIZED));
        if (stock.changeMinimum(minimum, clock.instant())) {
            stocks.saveAndFlush(stock);
            audit.record(AuditEvent.of(AuditAction.STOCK_MINIMUM_CHANGED, AuditOutcome.SUCCESS)
                    .actor(current.actorId())
                    .business(current.businessId())
                    .target(AuditTarget.PRODUCT, productId));
        }
        return toView(product, stock, categoryOf(product, current.businessId()));
    }

    // ─── Règles ────────────────────────────────────────────────────────────────────────────────

    /** Quantité saisie : obligatoire, strictement positive, précision de l'unité, taille raisonnable. */
    static void validateQuantity(String field, BigDecimal quantity, MeasurementUnit unit,
            Map<String, FieldErrorCode> errors) {
        if (quantity == null) {
            errors.put(field, FieldErrorCode.REQUIRED);
        } else if (quantity.signum() <= 0 || !fitsUnit(quantity, unit)) {
            errors.put(field, FieldErrorCode.INVALID_VALUE);
        }
    }

    /** Seuil : obligatoire, ≥ 0, précision de l'unité. */
    static void validateMinimum(String field, BigDecimal minimum, MeasurementUnit unit,
            Map<String, FieldErrorCode> errors) {
        if (minimum == null) {
            errors.put(field, FieldErrorCode.REQUIRED);
        } else if (minimum.signum() < 0 || !fitsUnit(minimum, unit)) {
            errors.put(field, FieldErrorCode.INVALID_VALUE);
        }
    }

    private static boolean fitsUnit(BigDecimal value, MeasurementUnit unit) {
        BigDecimal stripped = value.stripTrailingZeros();
        int scale = Math.max(stripped.scale(), 0);
        int integerDigits = stripped.precision() - stripped.scale();
        return scale <= unit.quantityDecimals() && integerDigits <= MAX_QUANTITY_INTEGER_DIGITS;
    }

    /** Coût unitaire : > 0, au plus les décimales de la devise (XOF 0, EUR 2), jamais arrondi. */
    static boolean validUnitCost(BigDecimal cost, String currency) {
        BigDecimal stripped = cost.stripTrailingZeros();
        return cost.signum() > 0 && CurrencyAmounts.fitsCurrency(cost, currency)
                && stripped.precision() - stripped.scale() <= MAX_UNIT_COST_INTEGER_DIGITS;
    }

    private static void ensureActive(Product product) {
        if (!product.isActive()) {
            throw new ApiException(ErrorCode.PRODUCT_ARCHIVED);
        }
    }

    // ─── Idempotence ───────────────────────────────────────────────────────────────────────────

    /** Demande déjà traitée avec cette clé : même contenu → résultat d'origine ; sinon 409. */
    private Optional<OperationResult> replay(UUID businessId, UUID key, String hash) {
        return movements.findByBusinessIdAndIdempotencyKey(businessId, key).map(existing -> {
            if (!existing.getRequestHash().equals(hash)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            CatalogContext.Current current = context.current();
            Product product = findProduct(existing.getProductId(), businessId);
            ProductStock stock = stocks.findByBusinessIdAndProductId(businessId, existing.getProductId()).orElse(null);
            return result(existing, product, stock, current, true);
        });
    }

    /** Empreinte canonique de la demande (quantités normalisées : « 5 » = « 5.000 »). */
    static String requestHash(UUID productId, String type, BigDecimal quantity, BigDecimal minimum, String reason,
            String reference) {
        return requestHash(productId, type, quantity, minimum, reason, reference, null);
    }

    /**
     * Le coût unitaire n'entre dans l'empreinte QUE s'il est renseigné : les empreintes des
     * mouvements antérieurs (sans coût) restent identiques, leurs rejeux restent reconnus.
     */
    static String requestHash(UUID productId, String type, BigDecimal quantity, BigDecimal minimum, String reason,
            String reference, BigDecimal unitCost) {
        String canonical = String.join("\u001f", productId.toString(), type, canonical(quantity), canonical(minimum),
                Objects.toString(reason, ""), Objects.toString(reference, ""));
        if (unitCost != null) {
            canonical = canonical + "\u001fUNIT_COST=" + canonical(unitCost);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 indisponible", impossible);
        }
    }

    private static String canonical(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static RuntimeException translate(DataIntegrityViolationException conflict) {
        String cause = String.valueOf(conflict.getMostSpecificCause().getMessage());
        if (cause.contains(IDEMPOTENCY_INDEX)) {
            return new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        if (cause.contains(STOCK_UNIQUE_INDEX)) {
            return new ApiException(ErrorCode.STOCK_ALREADY_INITIALIZED);
        }
        return conflict;
    }

    // ─── Vues et audit ─────────────────────────────────────────────────────────────────────────

    private void auditMovement(AuditAction action, CatalogContext.Current current, StockMovement movement) {
        // Aucune quantité ni motif dans l'événement : ils sont dans le mouvement (historique de référence).
        audit.record(AuditEvent.of(action, AuditOutcome.SUCCESS)
                .actor(current.actorId())
                .business(current.businessId())
                .target(AuditTarget.STOCK_MOVEMENT, movement.getId())
                .with(AuditDetail.REASON, movement.getType().name()));
    }

    private OperationResult result(StockMovement movement, Product product, ProductStock stock,
            CatalogContext.Current current, boolean replayed) {
        Map<UUID, String> authors = accounts.fullNamesOf(Set.of(movement.getCreatedBy()));
        return new OperationResult(toView(movement, product, authors, current.canReadCosts()),
                toView(product, stock, categoryOf(product, current.businessId())), replayed);
    }

    private EstimatedValue estimatedValue(CatalogContext.Current current) {
        int scale = CurrencyAmounts.fractionDigits(current.currency());
        BigDecimal total = BigDecimal.ZERO.setScale(scale);
        long withoutPrice = 0;
        for (StockOverviewRepository.ValuationLine line : overview.positiveStockValuationLines(current.businessId())) {
            if (line.referencePurchasePrice() == null) {
                withoutPrice++;
            } else {
                // Montant de chaque ligne arrondi à l'échelle de la devise (HALF_UP), puis somme.
                total = total.add(line.quantity().multiply(line.referencePurchasePrice()).setScale(scale, RoundingMode.HALF_UP));
            }
        }
        return new EstimatedValue(total, current.currency(), withoutPrice);
    }

    private Product findProduct(UUID productId, UUID businessId) {
        return products.findByIdAndBusinessId(productId, businessId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private Category categoryOf(Product product, UUID businessId) {
        return product.getCategoryId() == null ? null
                : categories.findByIdAndBusinessId(product.getCategoryId(), businessId).orElse(null);
    }

    private Map<UUID, Category> categoriesOf(UUID businessId, List<UUID> ids) {
        Set<UUID> distinct = ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        return distinct.isEmpty() ? Map.of()
                : categories.findByBusinessIdAndIdIn(businessId, distinct).stream()
                        .collect(Collectors.toMap(Category::getId, category -> category));
    }

    /** Quantité présentée : décimales significatives seulement (« 20 », « 1.5 »). */
    static BigDecimal display(BigDecimal quantity) {
        if (quantity == null) {
            return null;
        }
        BigDecimal stripped = quantity.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private StockItemView toView(Product product, ProductStock stock, Category category) {
        return toView(product, stock, category, context.current().canReadCosts());
    }

    private static StockItemView toView(Product product, ProductStock stock, Category category, boolean canReadCosts) {
        return new StockItemView(product.getId(), product.getName(), product.getSku(), product.getCategoryId(),
                category == null ? null : category.getName(), category == null ? null : category.getStatus(),
                product.getUnit(), product.getStatus(),
                stock == null ? null : display(stock.getQuantity()),
                stock == null ? null : display(stock.getMinimumQuantity()),
                stock == null ? StockStatus.NOT_INITIALIZED : stock.status(),
                stock == null ? null : stock.getUpdatedAt(),
                stock == null || !canReadCosts || stock.getAverageCost() == null ? null
                        : stock.getAverageCost().stripTrailingZeros().max(java.math.BigDecimal.ZERO).setScale(
                                Math.max(0, stock.getAverageCost().stripTrailingZeros().scale()), java.math.RoundingMode.UNNECESSARY));
    }

    /** @param canReadCosts sans COST_READ, le coût unitaire n'est JAMAIS placé dans la vue. */
    private static MovementView toView(StockMovement movement, Product product, Map<UUID, String> authors,
            boolean canReadCosts) {
        return new MovementView(movement.getId(), movement.getProductId(), product == null ? null : product.getName(),
                product == null ? null : product.getUnit(), movement.getType(), display(movement.getQuantity()),
                display(movement.getQuantityBefore()), display(movement.getQuantityAfter()), movement.getReason(),
                movement.getReference(), canReadCosts ? display(movement.getUnitCost()) : null,
                movement.getCreatedBy(), authors.get(movement.getCreatedBy()), movement.getCreatedAt());
    }
}
