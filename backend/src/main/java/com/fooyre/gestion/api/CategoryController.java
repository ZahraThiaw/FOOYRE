package com.fooyre.gestion.api;

import com.fooyre.gestion.application.CategoryService;
import com.fooyre.gestion.application.CategoryService.CategoryCommand;
import com.fooyre.gestion.application.CategoryView;
import com.fooyre.gestion.domain.CatalogStatus;
import com.fooyre.shared.error.FieldErrorCode;
import com.fooyre.shared.error.FieldValidationException;
import com.fooyre.shared.web.PageRequests;
import com.fooyre.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
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
 * Catégories du catalogue (docs/api-specification.md §2.3). Lecture : {@code CATALOG_READ} ;
 * écriture : {@code CATALOG_WRITE}. {@code DELETE} = archivage (jamais de suppression physique).
 * Aucun identifiant d'entreprise n'est accepté : l'entreprise est celle du jeton vérifié.
 */
@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private static final Map<String, String> SORTABLE = Map.of("name", "name", "createdAt", "createdAt");

    /** Création ou modification ({@code version} obligatoire pour la modification). */
    public record CategoryRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description,
            Integer version) {
    }

    public record CategoryResponse(UUID id, String name, String description, CatalogStatus status,
            long activeProductCount, Instant createdAt, Instant updatedAt, int version) {

        static CategoryResponse of(CategoryView view) {
            return new CategoryResponse(view.id(), view.name(), view.description(), view.status(),
                    view.activeProductCount(), view.createdAt(), view.updatedAt(), view.version());
        }
    }

    private final CategoryService categories;

    CategoryController(CategoryService categories) {
        this.categories = categories;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CATALOG_READ')")
    public PageResponse<CategoryResponse> list(
            @RequestParam(defaultValue = "ACTIVE") StatusFilter status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        var pageable = PageRequests.of(page, size, sort, SORTABLE, Sort.by(Sort.Order.asc("name").ignoreCase()), "id",
                Set.of("name"));
        return PageResponse.of(categories.list(status.toStatus(), pageable), CategoryResponse::of);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('CATALOG_READ')")
    public CategoryResponse get(@PathVariable UUID id) {
        return CategoryResponse.of(categories.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('CATALOG_WRITE')")
    public ResponseEntity<CategoryResponse> create(@Valid @RequestBody CategoryRequest request) {
        CategoryResponse created = CategoryResponse.of(categories.create(new CategoryCommand(request.name(), request.description())));
        return ResponseEntity.created(URI.create("/api/v1/categories/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('CATALOG_WRITE')")
    public CategoryResponse update(@PathVariable UUID id, @Valid @RequestBody CategoryRequest request) {
        if (request.version() == null) {
            throw FieldValidationException.of("version", FieldErrorCode.REQUIRED);
        }
        return CategoryResponse.of(categories.update(id, request.version(),
                new CategoryCommand(request.name(), request.description())));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('CATALOG_WRITE')")
    public ResponseEntity<Void> archive(@PathVariable UUID id) {
        categories.archive(id);
        return ResponseEntity.noContent().build();
    }
}
