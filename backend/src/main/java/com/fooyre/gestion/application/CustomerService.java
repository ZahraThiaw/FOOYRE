package com.fooyre.gestion.application;

import com.fooyre.gestion.domain.CatalogStatus;
import com.fooyre.gestion.domain.CatalogText;
import com.fooyre.gestion.domain.Customer;
import com.fooyre.gestion.domain.CustomerRepository;
import com.fooyre.gestion.domain.CustomerSearch;
import com.fooyre.shared.audit.AuditAction;
import com.fooyre.shared.audit.AuditEvent;
import com.fooyre.shared.audit.AuditOutcome;
import com.fooyre.shared.audit.AuditService;
import com.fooyre.shared.audit.AuditTarget;
import com.fooyre.shared.error.ApiException;
import com.fooyre.shared.error.ErrorCode;
import com.fooyre.shared.error.FieldErrorCode;
import com.fooyre.shared.error.FieldValidationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Clients (MVP-4A). Règles :
 * <ul>
 *   <li>entreprise et auteur issus du contexte authentifié uniquement ; client d'une autre
 *       entreprise : {@code 404} ;</li>
 *   <li>au moins un NOM ou un TÉLÉPHONE (une cliente connue seulement par son numéro peut être
 *       enregistrée) ; aucun autre champ obligatoire ;</li>
 *   <li>téléphone souple : chiffres, espaces, {@code ( ) . -} et un seul {@code +}, de 6 à 15 chiffres (norme E.164),
 *       conservé tel que saisi ; aucune unicité (numéro partagé possible) ;</li>
 *   <li>e-mail facultatif, format simple, enregistré en minuscules ;</li>
 *   <li>client archivé : non modifiable ({@code 422 INVALID_STATE_TRANSITION}) ; nouvel archivage
 *       refusé ({@code 409 ALREADY_ARCHIVED}) ; aucune suppression physique ;</li>
 *   <li>création idempotente si une clé est fournie (rejeu identique : client existant, sans
 *       nouvel audit ; autre contenu : {@code 409 IDEMPOTENCY_KEY_REUSED}) ;</li>
 *   <li>audit sans aucune donnée personnelle (identifiant du client seulement).</li>
 * </ul>
 */
@Service
public class CustomerService {

    static final int MIN_PHONE_DIGITS = 6;
    static final int MAX_PHONE_DIGITS = 15;
    static final String IDEMPOTENCY_INDEX = "ux_customer_business_idempotency";
    private static final Pattern PHONE_CHARACTERS = Pattern.compile("[+0-9 ().\\-]+");
    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");

    public record CustomerCommand(String name, String phone, String email, String address, String notes) {
    }

    /** Résultat d'une création ; {@code replayed} : même demande déjà traitée (aucun nouveau client). */
    public record CreationResult(CustomerView customer, boolean replayed) {
    }

    private final CustomerRepository customers;
    private final CatalogContext context;
    private final AuditService audit;
    private final Clock clock;

    CustomerService(CustomerRepository customers, CatalogContext context, AuditService audit, Clock clock) {
        this.customers = customers;
        this.context = context;
        this.audit = audit;
        this.clock = clock;
    }

    /** @param status {@code null} : tous les clients */
    @Transactional(readOnly = true)
    public Page<CustomerView> search(String text, CatalogStatus status, Pageable pageable) {
        UUID businessId = context.current().businessId();
        return customers.findAll(new CustomerSearch(text, status).forBusiness(businessId), pageable)
                .map(CustomerService::toView);
    }

    @Transactional(readOnly = true)
    public CustomerView get(UUID id) {
        return toView(find(id, context.current().businessId()));
    }

    /** @param idempotencyKey facultative ({@code null} : pas de contrôle de rejeu) */
    @Transactional
    public CreationResult create(CustomerCommand command, UUID idempotencyKey) {
        CatalogContext.Current current = context.current();
        Customer.Details details = validate(command);
        String hash = idempotencyKey == null ? null : requestHash(details);
        if (idempotencyKey != null) {
            Optional<CreationResult> replay = replay(current.businessId(), idempotencyKey, hash);
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        Customer customer = new Customer(details, idempotencyKey, hash, Instant.now(clock));
        try {
            customers.saveAndFlush(customer);
        } catch (DataIntegrityViolationException conflict) {
            // Même clé envoyée deux fois simultanément : la seconde retrouve la première.
            if (idempotencyKey != null
                    && String.valueOf(conflict.getMostSpecificCause().getMessage()).contains(IDEMPOTENCY_INDEX)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            throw conflict;
        }
        audit.record(event(AuditAction.CUSTOMER_CREATED, current, customer.getId()));
        return new CreationResult(toView(customer), false);
    }

    @Transactional
    public CustomerView update(UUID id, int expectedVersion, CustomerCommand command) {
        CatalogContext.Current current = context.current();
        Customer customer = find(id, current.businessId());
        CategoryService.ensureVersion(customer.getVersion(), expectedVersion);
        if (!customer.isActive()) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION);
        }
        customer.update(validate(command), Instant.now(clock));
        customers.saveAndFlush(customer);
        audit.record(event(AuditAction.CUSTOMER_UPDATED, current, id));
        return toView(customer);
    }

    @Transactional
    public void archive(UUID id) {
        CatalogContext.Current current = context.current();
        Customer customer = find(id, current.businessId());
        if (!customer.archive(Instant.now(clock))) {
            throw new ApiException(ErrorCode.ALREADY_ARCHIVED);
        }
        customers.saveAndFlush(customer);
        audit.record(event(AuditAction.CUSTOMER_ARCHIVED, current, id));
    }

    // ─── Règles ────────────────────────────────────────────────────────────────────────────────

    static Customer.Details validate(CustomerCommand command) {
        Map<String, FieldErrorCode> errors = new LinkedHashMap<>();
        String name = CatalogText.normalizeName(CatalogText.optional(command.name()));
        String phone = CatalogText.normalizeName(CatalogText.optional(command.phone()));
        String email = CatalogText.optional(command.email());
        String address = CatalogText.optional(command.address());
        String notes = CatalogText.optional(command.notes());

        if (name == null && phone == null) {
            // Ni nom ni téléphone : impossible d'identifier le client.
            errors.put("name", FieldErrorCode.REQUIRED);
        }
        if (name != null && name.length() > Customer.MAX_NAME_LENGTH) {
            errors.put("name", FieldErrorCode.TOO_LONG);
        }
        if (phone != null) {
            if (phone.length() > Customer.MAX_PHONE_LENGTH) {
                errors.put("phone", FieldErrorCode.TOO_LONG);
            } else if (!validPhone(phone)) {
                errors.put("phone", FieldErrorCode.INVALID_FORMAT);
            }
        }
        if (email != null) {
            email = email.toLowerCase(Locale.ROOT);
            if (email.length() > Customer.MAX_EMAIL_LENGTH) {
                errors.put("email", FieldErrorCode.TOO_LONG);
            } else if (!EMAIL.matcher(email).matches()) {
                errors.put("email", FieldErrorCode.INVALID_FORMAT);
            }
        }
        if (address != null && address.length() > Customer.MAX_ADDRESS_LENGTH) {
            errors.put("address", FieldErrorCode.TOO_LONG);
        }
        if (notes != null && notes.length() > Customer.MAX_NOTES_LENGTH) {
            errors.put("notes", FieldErrorCode.TOO_LONG);
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
        return new Customer.Details(name, phone, email, address, notes);
    }

    /** Numéros nationaux et internationaux (« 77 123 45 67 », « +221 77… », « 00221… »). */
    static boolean validPhone(String phone) {
        // Un seul « + » (indicatif), éventuellement entre parenthèses : « (+33) 6 12… ».
        if (!PHONE_CHARACTERS.matcher(phone).matches() || phone.chars().filter(c -> c == '+').count() > 1) {
            return false;
        }
        long digits = phone.chars().filter(Character::isDigit).count();
        return digits >= MIN_PHONE_DIGITS && digits <= MAX_PHONE_DIGITS;
    }

    // ─── Idempotence ───────────────────────────────────────────────────────────────────────────

    private Optional<CreationResult> replay(UUID businessId, UUID key, String hash) {
        return customers.findByBusinessIdAndIdempotencyKey(businessId, key).map(existing -> {
            if (!Objects.equals(existing.getRequestHash(), hash)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            return new CreationResult(toView(existing), true);
        });
    }

    /** Empreinte des informations NORMALISÉES (jamais journalisée : elle dérive de données personnelles). */
    static String requestHash(Customer.Details details) {
        String canonical = String.join("\u001f", Objects.toString(details.name(), ""),
                Objects.toString(details.phone(), ""), Objects.toString(details.email(), ""),
                Objects.toString(details.address(), ""), Objects.toString(details.notes(), ""));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 indisponible", impossible);
        }
    }

    // ─── Utilitaires ───────────────────────────────────────────────────────────────────────────

    private Customer find(UUID id, UUID businessId) {
        return customers.findByIdAndBusinessId(id, businessId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private static AuditEvent event(AuditAction action, CatalogContext.Current current, UUID customerId) {
        // Aucune donnée personnelle (nom, téléphone, e-mail, adresse, notes) dans l'événement.
        return AuditEvent.of(action, AuditOutcome.SUCCESS)
                .actor(current.actorId())
                .business(current.businessId())
                .target(AuditTarget.CUSTOMER, customerId);
    }

    private static CustomerView toView(Customer customer) {
        return new CustomerView(customer.getId(), customer.getName(), customer.getPhone(), customer.getEmail(),
                customer.getAddress(), customer.getNotes(), customer.getStatus(), customer.getArchivedAt(),
                customer.getCreatedAt(), customer.getUpdatedAt(), customer.getVersion());
    }
}
