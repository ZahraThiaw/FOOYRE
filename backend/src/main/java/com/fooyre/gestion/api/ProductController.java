package com.fooyre.gestion.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fooyre.gestion.application.ProductService;
import com.fooyre.gestion.application.ProductService.ProductCommand;
import com.fooyre.gestion.application.ProductView;
import com.fooyre.gestion.domain.CatalogStatus;
import com.fooyre.gestion.domain.MeasurementUnit;
import com.fooyre.gestion.domain.ProductSearch;
import com.fooyre.shared.error.FieldErrorCode;
import com.fooyre.shared.error.FieldValidationException;
import com.fooyre.shared.web.PageRequests;
import com.fooyre.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Produits du catalogue (docs/api-specification.md §2.3). Lecture : {@code CATALOG_READ} ;
 * écriture : {@code CATALOG_WRITE}. Le prix d'achat n'est présent dans la réponse qu'avec
 * {@code COST_READ} (filtré par le serveur). Montants en chaînes décimales. {@code DELETE} =
 * archivage.
 */
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private static final int MAX_QUERY_LENGTH = 100;

    /** Création ou modification ({@code version} obligatoire pour la modification). */
    public record ProductRequest(
            @NotBlank @Size(max = 150) String name,
            @Size(max = 1000) String description,
            @Size(max = 64) String sku,
            @Size(max = 64) String barcode,
            UUID categoryId,
            @NotNull MeasurementUnit unit,
            @Digits(integer = 15, fraction = 2) BigDecimal purchasePrice,
            @NotNull @Digits(integer = 15, fraction = 2) BigDecimal salePrice,
            Integer version) {
    }

    public record CategorySummary(UUID id, String name, CatalogStatus status) {
    }

    public record ProductResponse(
            UUID id,
            String name,
            String description,
            String sku,
            String barcode,
            MeasurementUnit unit,
            CategorySummary category,
            /** Absent sans la permission COST_READ (ou si non renseigné). */
            @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal purchasePrice,
            BigDecimal salePrice,
            String currency,
            CatalogStatus status,
            Instant createdAt,
            Instant updatedAt,
            int version) {

        static ProductResponse of(ProductView view) {
            return new ProductResponse(view.id(), view.name(), view.description(), view.sku(), view.barcode(),
                    view.unit(),
                    view.categoryId() == null ? null
                            : new CategorySummary(view.categoryId(), view.categoryName(), view.categoryStatus()),
                    view.purchasePrice(), view.salePrice(), view.currency(), view.status(), view.createdAt(),
                    view.updatedAt(), view.version());
        }
    }

    private final ProductService products;

    ProductController(ProductService products) {
        this.products = products;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CATALOG_READ')")
    public PageResponse<ProductResponse> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "ACTIVE") StatusFilter status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        if (q != null && q.length() > MAX_QUERY_LENGTH) {
            throw FieldValidationException.of("q", FieldErrorCode.TOO_LONG);
        }
        var pageable = PageRequests.of(page, size, sort, ProductService.sortableFields(),
                Sort.by(Sort.Order.asc("name").ignoreCase()), "id", Set.of("name"));
        return PageResponse.of(products.search(new ProductSearch(q, categoryId, status.toStatus()), pageable),
                ProductResponse::of);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('CATALOG_READ')")
    public ProductResponse get(@PathVariable UUID id) {
        return ProductResponse.of(products.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('CATALOG_WRITE')")
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        ProductResponse created = ProductResponse.of(products.create(toCommand(request)));
        return ResponseEntity.created(URI.create("/api/v1/products/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('CATALOG_WRITE')")
    public ProductResponse update(@PathVariable UUID id, @Valid @RequestBody ProductRequest request) {
        if (request.version() == null) {
            throw FieldValidationException.of("version", FieldErrorCode.REQUIRED);
        }
        return ProductResponse.of(products.update(id, request.version(), toCommand(request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('CATALOG_WRITE')")
    public ResponseEntity<Void> archive(@PathVariable UUID id) {
        products.archive(id);
        return ResponseEntity.noContent().build();
    }

    private static ProductCommand toCommand(ProductRequest request) {
        return new ProductCommand(request.name(), request.description(), request.sku(), request.barcode(),
                request.categoryId(), request.unit(), request.purchasePrice(), request.salePrice());
    }
}
