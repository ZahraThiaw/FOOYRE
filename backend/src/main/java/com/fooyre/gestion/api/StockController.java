package com.fooyre.gestion.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fooyre.gestion.application.StockService;
import com.fooyre.gestion.application.StockService.InitializeCommand;
import com.fooyre.gestion.application.StockService.MovementCommand;
import com.fooyre.gestion.application.StockViews.MovementView;
import com.fooyre.gestion.application.StockViews.OperationResult;
import com.fooyre.gestion.application.StockViews.StockItemView;
import com.fooyre.gestion.application.StockViews.SummaryView;
import com.fooyre.gestion.domain.CatalogStatus;
import com.fooyre.gestion.domain.MeasurementUnit;
import com.fooyre.gestion.domain.StockMovementType;
import com.fooyre.gestion.domain.StockOverviewRepository;
import com.fooyre.gestion.domain.StockStatus;
import com.fooyre.shared.error.FieldErrorCode;
import com.fooyre.shared.error.FieldValidationException;
import com.fooyre.shared.web.PageRequests;
import com.fooyre.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stock (MVP-3, docs/api-specification.md §2.3). Lecture : {@code STOCK_READ} (tous les rôles) ;
 * opérations et seuil : {@code STOCK_ADJUST} (OWNER, MANAGER). Valeur estimée : {@code COST_READ}
 * (filtrée par le service). Opérations : en‑tête {@code Idempotency-Key} (UUID) OBLIGATOIRE ;
 * réponse {@code 201} (nouveau mouvement) ou {@code 200} + {@code Idempotent-Replay: true} (rejeu).
 * Aucun identifiant d'entreprise accepté ; aucun mouvement modifiable ni supprimable.
 */
@RestController
@RequestMapping("/api/v1/stock")
public class StockController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final Map<String, String> SORTABLE = Map.of("name", "name", "quantity", "quantity",
            "updatedAt", "updatedAt");
    private static final Map<String, String> MOVEMENT_SORTABLE = Map.of("createdAt", "createdAt");

    // ─── Contrat JSON ──────────────────────────────────────────────────────────────────────────

    public record CategorySummary(UUID id, String name, CatalogStatus status) {
    }

    public record StockItemResponse(
            UUID productId,
            String productName,
            String sku,
            CategorySummary category,
            MeasurementUnit unit,
            CatalogStatus productStatus,
            boolean initialized,
            BigDecimal quantity,
            BigDecimal minimumQuantity,
            StockStatus status,
            Instant updatedAt,
            /** CMP : absent sans COST_READ ou si inconnu. */
            @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal averageCost) {

        static StockItemResponse of(StockItemView view) {
            return new StockItemResponse(view.productId(), view.productName(), view.sku(),
                    view.categoryId() == null ? null
                            : new CategorySummary(view.categoryId(), view.categoryName(), view.categoryStatus()),
                    view.unit(), view.productStatus(), view.quantity() != null, view.quantity(),
                    view.minimumQuantity(), view.status(), view.updatedAt(), view.averageCost());
        }
    }

    public record Author(UUID id, String fullName) {
    }

    public record MovementResponse(UUID id, UUID productId, String productName, MeasurementUnit unit,
            StockMovementType type, BigDecimal quantity, BigDecimal quantityBefore, BigDecimal quantityAfter,
            String reason, String reference,
            /** Absent sans COST_READ (filtré par le service) et pour un mouvement sans coût. */
            @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal unitCost,
            Author author, Instant createdAt) {

        static MovementResponse of(MovementView view) {
            return new MovementResponse(view.id(), view.productId(), view.productName(), view.unit(), view.type(),
                    view.quantity(), view.quantityBefore(), view.quantityAfter(), view.reason(), view.reference(),
                    view.unitCost(), new Author(view.authorId(), view.authorName()), view.createdAt());
        }
    }

    public record OperationResponse(MovementResponse movement, StockItemResponse stock) {
    }

    public record EstimatedValueResponse(BigDecimal amount, String currency, long productsWithoutReferencePrice) {
    }

    public record SummaryResponse(long trackedProducts, long notInitializedProducts, long inStock, long lowStock,
            long outOfStock,
            /** Absente sans COST_READ. Estimation au prix d'achat de RÉFÉRENCE, pas une valeur comptable. */
            @JsonInclude(JsonInclude.Include.NON_NULL) EstimatedValueResponse estimatedValue) {

        static SummaryResponse of(SummaryView view) {
            return new SummaryResponse(view.trackedProducts(), view.notInitializedProducts(), view.inStock(),
                    view.lowStock(), view.outOfStock(),
                    view.estimatedValue() == null ? null : new EstimatedValueResponse(view.estimatedValue().amount(),
                            view.estimatedValue().currency(), view.estimatedValue().productsWithoutReferencePrice()));
        }
    }

    public record InitializeRequest(@NotNull BigDecimal quantity, BigDecimal minimumQuantity,
            @Size(max = 255) String note) {
    }

    /**
     * Entrée ou sortie. {@code unitCost} : coût unitaire d'achat, ENTRÉES uniquement (refusé sur une
     * sortie), facultatif, réservé à COST_READ ; déclaré ici pour être refusé explicitement plutôt
     * qu'ignoré en silence.
     */
    public record MovementRequest(@NotNull BigDecimal quantity, @Size(max = 255) String reason,
            @Size(max = 100) String reference, BigDecimal unitCost) {
    }

    public enum AdjustmentDirection { IN, OUT }

    /** {@code unitCost} déclaré pour être REFUSÉ (un ajustement n'a pas de coût d'acquisition). */
    public record AdjustmentRequest(@NotNull AdjustmentDirection direction, @NotNull BigDecimal quantity,
            @Size(max = 255) String reason, BigDecimal unitCost) {
    }

    public record MinimumRequest(@NotNull BigDecimal minimumQuantity) {
    }

    public enum StatusFilterParam { IN_STOCK, LOW_STOCK, OUT_OF_STOCK, NOT_INITIALIZED, TRACKED, ALERT, ALL }

    private final StockService stock;

    StockController(StockService stock) {
        this.stock = stock;
    }

    // ─── Lecture ───────────────────────────────────────────────────────────────────────────────

    @GetMapping
    @PreAuthorize("hasAuthority('STOCK_READ')")
    public PageResponse<StockItemResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "ALL") StatusFilterParam status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return search(q, categoryId, status == StatusFilterParam.ALL ? null
                : StockOverviewRepository.StatusFilter.valueOf(status.name()), page, size, sort);
    }

    /** Produits en stock faible ou en rupture. */
    @GetMapping("/alerts")
    @PreAuthorize("hasAuthority('STOCK_READ')")
    public PageResponse<StockItemResponse> alerts(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return search(null, null, StockOverviewRepository.StatusFilter.ALERT, page, size, "quantity,asc");
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('STOCK_READ')")
    public SummaryResponse summary() {
        return SummaryResponse.of(stock.summary());
    }

    /** Historique de toute l'entreprise (filtre produit facultatif), du plus récent au plus ancien. */
    @GetMapping("/movements")
    @PreAuthorize("hasAuthority('STOCK_READ')")
    public PageResponse<MovementResponse> movements(
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return movementPage(productId, page, size);
    }

    @GetMapping("/{productId}")
    @PreAuthorize("hasAuthority('STOCK_READ')")
    public StockItemResponse get(@PathVariable UUID productId) {
        return StockItemResponse.of(stock.get(productId));
    }

    @GetMapping("/{productId}/movements")
    @PreAuthorize("hasAuthority('STOCK_READ')")
    public PageResponse<MovementResponse> productMovements(
            @PathVariable UUID productId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return movementPage(productId, page, size);
    }

    // ─── Opérations ────────────────────────────────────────────────────────────────────────────

    @PostMapping("/{productId}/initialize")
    @PreAuthorize("hasAuthority('STOCK_ADJUST')")
    public ResponseEntity<OperationResponse> initialize(@PathVariable UUID productId,
            @RequestHeader(IDEMPOTENCY_HEADER) UUID idempotencyKey, @Valid @RequestBody InitializeRequest request) {
        return respond(stock.initialize(productId, idempotencyKey,
                new InitializeCommand(request.quantity(), request.minimumQuantity(), request.note())));
    }

    @PostMapping("/{productId}/entries")
    @PreAuthorize("hasAuthority('STOCK_ADJUST')")
    public ResponseEntity<OperationResponse> entry(@PathVariable UUID productId,
            @RequestHeader(IDEMPOTENCY_HEADER) UUID idempotencyKey, @Valid @RequestBody MovementRequest request) {
        return respond(stock.record(productId, idempotencyKey,
                new MovementCommand(StockMovementType.ENTRY, request.quantity(), request.reason(), request.reference(),
                        request.unitCost())));
    }

    @PostMapping("/{productId}/exits")
    @PreAuthorize("hasAuthority('STOCK_ADJUST')")
    public ResponseEntity<OperationResponse> exit(@PathVariable UUID productId,
            @RequestHeader(IDEMPOTENCY_HEADER) UUID idempotencyKey, @Valid @RequestBody MovementRequest request) {
        return respond(stock.record(productId, idempotencyKey,
                new MovementCommand(StockMovementType.EXIT, request.quantity(), request.reason(), request.reference(),
                        request.unitCost())));
    }

    @PostMapping("/{productId}/adjustments")
    @PreAuthorize("hasAuthority('STOCK_ADJUST')")
    public ResponseEntity<OperationResponse> adjustment(@PathVariable UUID productId,
            @RequestHeader(IDEMPOTENCY_HEADER) UUID idempotencyKey, @Valid @RequestBody AdjustmentRequest request) {
        StockMovementType type = request.direction() == AdjustmentDirection.IN
                ? StockMovementType.ADJUSTMENT_IN : StockMovementType.ADJUSTMENT_OUT;
        return respond(stock.record(productId, idempotencyKey,
                new MovementCommand(type, request.quantity(), request.reason(), null, request.unitCost())));
    }

    @PutMapping("/{productId}/minimum-quantity")
    @PreAuthorize("hasAuthority('STOCK_ADJUST')")
    public StockItemResponse minimum(@PathVariable UUID productId, @Valid @RequestBody MinimumRequest request) {
        return StockItemResponse.of(stock.changeMinimum(productId, request.minimumQuantity()));
    }

    // ─── Utilitaires ───────────────────────────────────────────────────────────────────────────

    private PageResponse<StockItemResponse> search(String q, UUID categoryId,
            StockOverviewRepository.StatusFilter status, Integer page, Integer size, String sort) {
        if (q != null && q.length() > 100) {
            throw FieldValidationException.of("q", FieldErrorCode.TOO_LONG);
        }
        var pageable = PageRequests.of(page, size, sort, SORTABLE, Sort.by(Sort.Order.asc("name")), "id", Set.of());
        return PageResponse.of(stock.search(q, categoryId, status, pageable), StockItemResponse::of);
    }

    private PageResponse<MovementResponse> movementPage(UUID productId, Integer page, Integer size) {
        var pageable = PageRequests.of(page, size, null, MOVEMENT_SORTABLE, Sort.by(Sort.Order.desc("createdAt")), "id",
                Set.of());
        return PageResponse.of(stock.movements(productId, pageable), MovementResponse::of);
    }

    private static ResponseEntity<OperationResponse> respond(OperationResult result) {
        OperationResponse body = new OperationResponse(MovementResponse.of(result.movement()),
                StockItemResponse.of(result.stock()));
        return result.replayed()
                ? ResponseEntity.ok().header("Idempotent-Replay", "true").body(body)
                : ResponseEntity.status(HttpStatus.CREATED).body(body);
    }
}
