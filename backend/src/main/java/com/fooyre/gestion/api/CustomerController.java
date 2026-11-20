package com.fooyre.gestion.api;

import com.fooyre.gestion.application.CustomerService;
import com.fooyre.gestion.application.CustomerService.CreationResult;
import com.fooyre.gestion.application.CustomerService.CustomerCommand;
import com.fooyre.gestion.application.CustomerView;
import com.fooyre.gestion.domain.CatalogStatus;
import com.fooyre.shared.web.PageRequests;
import com.fooyre.shared.web.PageResponse;
import com.fooyre.shared.error.FieldErrorCode;
import com.fooyre.shared.error.FieldValidationException;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Clients (docs/api-specification.md §2.3). Permissions (matrice D3, aucune nouvelle) :
 * lecture {@code CUSTOMER_READ}, création et modification {@code CUSTOMER_WRITE}, archivage
 * {@code CUSTOMER_ARCHIVE}. {@code DELETE} = archivage (jamais de suppression physique).
 * Aucun identifiant d'entreprise n'est accepté : l'entreprise est celle du jeton vérifié.
 */
@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private static final Map<String, String> SORTABLE = Map.of("name", "name", "createdAt", "createdAt",
            "updatedAt", "updatedAt");
    private static final int MAX_QUERY_LENGTH = 100;

    /** Création ou modification ({@code version} obligatoire pour la modification). */
    public record CustomerRequest(
            @Size(max = 150) String name,
            @Size(max = 30) String phone,
            @Size(max = 254) String email,
            @Size(max = 500) String address,
            @Size(max = 2000) String notes,
            Integer version) {

        CustomerCommand toCommand() {
            return new CustomerCommand(name, phone, email, address, notes);
        }
    }

    public record CustomerResponse(UUID id, String name, String phone, String email, String address, String notes,
            CatalogStatus status, Instant archivedAt, Instant createdAt, Instant updatedAt, int version) {

        static CustomerResponse of(CustomerView view) {
            return new CustomerResponse(view.id(), view.name(), view.phone(), view.email(), view.address(),
                    view.notes(), view.status(), view.archivedAt(), view.createdAt(), view.updatedAt(), view.version());
        }
    }

    private final CustomerService customers;

    CustomerController(CustomerService customers) {
        this.customers = customers;
    }

    /** Recherche (nom, téléphone, e-mail), filtre de statut, pagination et tri côté serveur. */
    @GetMapping
    @PreAuthorize("hasAuthority('CUSTOMER_READ')")
    public PageResponse<CustomerResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "ACTIVE") StatusFilter status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        if (q != null && q.length() > MAX_QUERY_LENGTH) {
            throw FieldValidationException.of("q", FieldErrorCode.TOO_LONG);
        }
        var pageable = PageRequests.of(page, size, sort, SORTABLE, Sort.by(Sort.Order.asc("name").ignoreCase()), "id",
                Set.of("name"));
        return PageResponse.of(customers.search(q, status.toStatus(), pageable), CustomerResponse::of);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('CUSTOMER_READ')")
    public CustomerResponse get(@PathVariable UUID id) {
        return CustomerResponse.of(customers.get(id));
    }

    /**
     * {@code Idempotency-Key} (UUID) facultative : un rejeu identique renvoie le client existant
     * ({@code 200} + {@code Idempotent-Replay: true}) au lieu d'en créer un second.
     */
    @PostMapping
    @PreAuthorize("hasAuthority('CUSTOMER_WRITE')")
    public ResponseEntity<CustomerResponse> create(
            @RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey,
            @Valid @RequestBody CustomerRequest request) {
        CreationResult result = customers.create(request.toCommand(), idempotencyKey);
        CustomerResponse body = CustomerResponse.of(result.customer());
        return result.replayed()
                ? ResponseEntity.ok().header("Idempotent-Replay", "true").body(body)
                : ResponseEntity.created(URI.create("/api/v1/customers/" + body.id())).body(body);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('CUSTOMER_WRITE')")
    public CustomerResponse update(@PathVariable UUID id, @Valid @RequestBody CustomerRequest request) {
        if (request.version() == null) {
            throw FieldValidationException.of("version", FieldErrorCode.REQUIRED);
        }
        return CustomerResponse.of(customers.update(id, request.version(), request.toCommand()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('CUSTOMER_ARCHIVE')")
    public ResponseEntity<Void> archive(@PathVariable UUID id) {
        customers.archive(id);
        return ResponseEntity.noContent().build();
    }
}
