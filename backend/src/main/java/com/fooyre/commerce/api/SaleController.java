package com.fooyre.commerce.api;

import com.fooyre.commerce.application.SaleService;
import com.fooyre.commerce.application.SaleService.DraftCommand;
import com.fooyre.commerce.application.SaleService.DraftResult;
import com.fooyre.commerce.application.SaleService.LineInput;
import com.fooyre.commerce.application.SaleView;
import com.fooyre.commerce.domain.SaleStatus;
import com.fooyre.shared.error.FieldErrorCode;
import com.fooyre.shared.error.FieldValidationException;
import com.fooyre.shared.web.PageRequests;
import com.fooyre.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
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
 * Ventes (docs/api-specification.md §2.4). Création, modification et confirmation :
 * {@code SALE_CREATE} ; consultation : {@code SALE_READ_ALL} ; abandon d'un brouillon :
 * {@code SALE_CREATE}. Le client n'envoie JAMAIS de prix ni de montant : le serveur les calcule.
 * {@code Idempotency-Key} obligatoire à la création et à la confirmation.
 */
@RestController
@RequestMapping("/api/v1/sales")
public class SaleController {

    private static final Map<String, String> SORTABLE = Map.of("createdAt", "createdAt", "confirmedAt", "confirmedAt");

    public record LineRequest(@NotNull UUID productId, @NotNull BigDecimal quantity, BigDecimal discount) {
    }

    public record SaleRequest(UUID customerId, @NotNull @Size(min = 1, max = 50) List<@Valid LineRequest> lines,
            BigDecimal globalDiscount, Integer version) {

        DraftCommand toCommand() {
            return new DraftCommand(customerId, lines.stream()
                    .map(line -> new LineInput(line.productId(), line.quantity(), line.discount())).toList(),
                    globalDiscount);
        }
    }

    public enum StatusParam { DRAFT, CONFIRMED, CANCELLED, ALL }

    private final SaleService sales;

    SaleController(SaleService sales) {
        this.sales = sales;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SALE_READ_ALL')")
    public PageResponse<SaleView.Summary> list(
            @RequestParam(defaultValue = "ALL") StatusParam status,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        var pageable = PageRequests.of(page, size, sort, SORTABLE, Sort.by(Sort.Order.desc("createdAt")), "id", Set.of());
        return PageResponse.of(sales.list(status == StatusParam.ALL ? null : SaleStatus.valueOf(status.name()),
                customerId, pageable), summary -> summary);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SALE_READ_ALL')")
    public SaleView get(@PathVariable UUID id) {
        return sales.get(id);
    }

    /** Récapitulatif calculé par le serveur (aucun enregistrement). */
    @PostMapping("/quote")
    @PreAuthorize("hasAuthority('SALE_CREATE')")
    public SaleView.Quote quote(@Valid @RequestBody SaleRequest request) {
        return sales.quote(request.toCommand());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SALE_CREATE')")
    public ResponseEntity<SaleView> create(@RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody SaleRequest request) {
        DraftResult result = sales.createDraft(request.toCommand(), idempotencyKey);
        return result.replayed()
                ? ResponseEntity.ok().header("Idempotent-Replay", "true").body(result.sale())
                : ResponseEntity.created(URI.create("/api/v1/sales/" + result.sale().id())).body(result.sale());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('SALE_CREATE')")
    public SaleView update(@PathVariable UUID id, @Valid @RequestBody SaleRequest request) {
        if (request.version() == null) {
            throw FieldValidationException.of("version", FieldErrorCode.REQUIRED);
        }
        return sales.updateDraft(id, request.version(), request.toCommand());
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAuthority('SALE_CREATE')")
    public ResponseEntity<SaleView> confirm(@PathVariable UUID id,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey) {
        DraftResult result = sales.confirm(id, idempotencyKey);
        return result.replayed()
                ? ResponseEntity.ok().header("Idempotent-Replay", "true").body(result.sale())
                : ResponseEntity.ok(result.sale());
    }

    /** Abandon d'un BROUILLON uniquement (l'annulation d'une vente confirmée relève d'un lot ultérieur, D11). */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('SALE_CREATE')")
    public SaleView cancel(@PathVariable UUID id) {
        return sales.cancelDraft(id);
    }
}
