package com.fooyre.commerce.application;

import com.fooyre.business.application.BusinessProfileService;
import com.fooyre.business.application.BusinessSettingsService;
import com.fooyre.business.application.BusinessSettingsService.SalesSettings;
import com.fooyre.commerce.domain.Sale;
import com.fooyre.commerce.domain.SaleLine;
import com.fooyre.commerce.domain.SaleLineRepository;
import com.fooyre.commerce.domain.SaleNumberGenerator;
import com.fooyre.commerce.domain.SaleRepository;
import com.fooyre.commerce.domain.SaleStatus;
import com.fooyre.commerce.domain.TaxCalculator;
import com.fooyre.gestion.application.SalesCatalogService;
import com.fooyre.gestion.application.SalesCatalogService.SaleCustomer;
import com.fooyre.gestion.application.SalesCatalogService.SaleProduct;
import com.fooyre.gestion.application.StockService;
import com.fooyre.gestion.application.StockViews.SaleExit;
import com.fooyre.gestion.application.StockViews.SaleExitLine;
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
import com.fooyre.shared.security.Permission;
import com.fooyre.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
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
 * Ventes. Règles :
 * <ul>
 *   <li>entreprise, auteur et droits issus UNIQUEMENT du contexte authentifié ;</li>
 *   <li>produits et client résolus dans l'entreprise active ({@code 404} sinon) ; produits actifs ;</li>
 *   <li>montants calculés ICI ({@link TaxCalculator}) avec les prix lus en base ; le client n'envoie
 *       que produits, quantités et remises ;</li>
 *   <li>remises : sans limite avec {@code SALE_DISCOUNT_UNLIMITED} ; sinon limite de l'entreprise (%
 *       du montant brut, 0 par défaut), contrôlée à l'enregistrement ET à la confirmation ; refus
 *       journalisé ({@code DISCOUNT_LIMIT_EXCEEDED}) ;</li>
 *   <li>confirmation ATOMIQUE sous verrou de la vente : prix relus, montants, numéro (séquence par
 *       entreprise), sorties de stock via le module Stock (verrous, stock négatif), photographie des
 *       lignes avec le CMP courant — tout ou rien ; une seconde confirmation ne refait rien ;</li>
 *   <li>statut de règlement CALCULÉ : aucun paiement n'existe encore (Wave non connecté) → non réglée.</li>
 * </ul>
 */
@Service
public class SaleService {

    static final int MAX_LINES = 50;
    static final String IDEMPOTENCY_INDEX = "ux_sale_business_idempotency";
    static final String CONFIRM_IDEMPOTENCY_INDEX = "ux_sale_business_confirm_idempotency";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public record LineInput(UUID productId, BigDecimal quantity, BigDecimal discount) {
    }

    public record DraftCommand(UUID customerId, List<LineInput> lines, BigDecimal globalDiscount) {
    }

    public record DraftResult(SaleView sale, boolean replayed) {
    }

    private final SaleRepository sales;
    private final SaleLineRepository lines;
    private final SaleNumberGenerator numbers;
    private final SalesCatalogService catalog;
    private final StockService stock;
    private final BusinessSettingsService settings;
    private final BusinessProfileService profiles;
    private final UserAccountService accounts;
    private final AuditService audit;
    private final Clock clock;

    SaleService(SaleRepository sales, SaleLineRepository lines, SaleNumberGenerator numbers, SalesCatalogService catalog,
            StockService stock, BusinessSettingsService settings, BusinessProfileService profiles,
            UserAccountService accounts, AuditService audit, Clock clock) {
        this.sales = sales;
        this.lines = lines;
        this.numbers = numbers;
        this.catalog = catalog;
        this.stock = stock;
        this.settings = settings;
        this.profiles = profiles;
        this.accounts = accounts;
        this.audit = audit;
        this.clock = clock;
    }

    // ─── Calcul (sans enregistrement) ──────────────────────────────────────────────────────────

    /** Récapitulatif calculé par le serveur avec les prix ACTUELS (panier en cours de saisie). */
    @Transactional(readOnly = true)
    public SaleView.Quote quote(DraftCommand command) {
        Prepared prepared = prepare(command);
        return prepared.quote(discountWithinLimit(prepared));
    }

    // ─── Brouillons ────────────────────────────────────────────────────────────────────────────

    @Transactional(noRollbackFor = DiscountLimitExceededException.class)
    public DraftResult createDraft(DraftCommand command, UUID idempotencyKey) {
        UUID businessId = TenantContext.requireBusinessId();
        String hash = requestHash(command);
        Optional<Sale> existing = sales.findByBusinessIdAndIdempotencyKey(businessId, idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().getRequestHash().equals(hash)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            return new DraftResult(view(existing.get()), true);
        }
        Prepared prepared = prepare(command);
        enforceDiscountLimit(prepared, null);
        Sale sale = new Sale(command.customerId(), prepared.currency(), prepared.globalDiscount(),
                TenantContext.requireUser().userId(), clock.instant(), idempotencyKey, hash);
        try {
            sales.saveAndFlush(sale);
        } catch (DataIntegrityViolationException conflict) {
            throw translate(conflict);
        }
        saveLines(sale.getId(), prepared);
        audit.record(event(AuditAction.SALE_DRAFT_SAVED, sale.getId(), AuditOutcome.SUCCESS));
        return new DraftResult(view(sale), false);
    }

    @Transactional(noRollbackFor = DiscountLimitExceededException.class)
    public SaleView updateDraft(UUID id, int expectedVersion, DraftCommand command) {
        UUID businessId = TenantContext.requireBusinessId();
        Sale sale = sales.lockByIdAndBusinessId(id, businessId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        requireDraft(sale);
        if (sale.getVersion() != expectedVersion) {
            throw new ApiException(ErrorCode.OPTIMISTIC_LOCK);
        }
        Prepared prepared = prepare(command);
        enforceDiscountLimit(prepared, sale.getId());
        lines.deleteDraftLines(businessId, id);
        sale.updateDraft(command.customerId(), prepared.globalDiscount(), clock.instant());
        sales.saveAndFlush(sale);
        saveLines(sale.getId(), prepared);
        audit.record(event(AuditAction.SALE_DRAFT_SAVED, sale.getId(), AuditOutcome.SUCCESS));
        return view(sale);
    }

    /** Abandon d'un BROUILLON (sans effet sur le stock ni les indicateurs). */
    @Transactional
    public SaleView cancelDraft(UUID id) {
        Sale sale = sales.lockByIdAndBusinessId(id, TenantContext.requireBusinessId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        requireDraft(sale);
        sale.cancelDraft(clock.instant());
        sales.saveAndFlush(sale);
        audit.record(event(AuditAction.SALE_DRAFT_CANCELLED, sale.getId(), AuditOutcome.SUCCESS));
        return view(sale);
    }

    // ─── Confirmation ──────────────────────────────────────────────────────────────────────────

    /**
     * Confirmation atomique. Rejouée avec la même clé : vente confirmée renvoyée, rien n'est refait.
     * Un refus (stock, droits, remise) annule TOUT : aucune sortie de stock partielle.
     */
    @Transactional(noRollbackFor = DiscountLimitExceededException.class)
    public DraftResult confirm(UUID id, UUID idempotencyKey) {
        UUID businessId = TenantContext.requireBusinessId();
        UUID actor = TenantContext.requireUser().userId();
        Sale sale = sales.lockByIdAndBusinessId(id, businessId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (sale.getStatus() == SaleStatus.CONFIRMED) {
            if (idempotencyKey.equals(sale.getConfirmIdempotencyKey())) {
                return new DraftResult(view(sale), true);
            }
            throw new ApiException(ErrorCode.SALE_ALREADY_CONFIRMED);
        }
        requireDraft(sale);
        List<SaleLine> saleLines = lines.findByBusinessIdAndSaleIdOrderByPosition(businessId, id);
        if (saleLines.isEmpty()) {
            throw FieldValidationException.of("lines", FieldErrorCode.REQUIRED);
        }
        DraftCommand command = new DraftCommand(sale.getCustomerId(), saleLines.stream()
                .map(line -> new LineInput(line.getProductId(), line.getQuantity(), line.getLineDiscount())).toList(),
                sale.getGlobalDiscount());
        Prepared prepared = prepare(command); // prix ACTUELS relus en base, produits et client revérifiés
        enforceDiscountLimit(prepared, sale.getId());

        long number = numbers.next();
        String reference = Sale.reference(number);
        List<SaleExit> exits = stock.recordSaleExits(reference, saleLines.stream()
                .map(line -> new SaleExitLine(line.getId(), line.getProductId(), line.getQuantity())).toList());
        Instant now = clock.instant();
        for (int i = 0; i < saleLines.size(); i++) {
            SaleLine line = saleLines.get(i);
            SaleProduct product = prepared.products().get(line.getProductId());
            line.freeze(product.name(), product.sku(), product.unit(), product.salePrice(),
                    prepared.result().lines().get(i), exits.get(i).unitCost(), exits.get(i).movementId());
        }
        lines.saveAllAndFlush(saleLines); // AVANT le changement de statut (déclencheur d'immutabilité)
        TaxCalculator.Result result = prepared.result();
        SalesSettings tax = prepared.settings();
        sale.confirm(number, tax.vatEnabled(), tax.vatRate(), tax.priceEntryMode(), result.gross(), result.discount(),
                result.totalExclTax(), result.taxAmount(), result.totalInclTax(), actor, idempotencyKey, now);
        try {
            sales.saveAndFlush(sale);
        } catch (DataIntegrityViolationException conflict) {
            throw translate(conflict);
        }
        audit.record(event(AuditAction.SALE_CONFIRMED, sale.getId(), AuditOutcome.SUCCESS));
        if (result.discount().signum() > 0) {
            audit.record(event(AuditAction.DISCOUNT_APPLIED, sale.getId(), AuditOutcome.SUCCESS));
        }
        return new DraftResult(view(sale), false);
    }

    // ─── Lecture ───────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<SaleView.Summary> list(SaleStatus status, UUID customerId, Pageable pageable) {
        UUID businessId = TenantContext.requireBusinessId();
        Page<Sale> page;
        if (customerId != null) {
            page = sales.findByBusinessIdAndCustomerIdAndStatus(businessId, customerId,
                    status == null ? SaleStatus.CONFIRMED : status, pageable);
        } else if (status != null) {
            page = sales.findByBusinessIdAndStatus(businessId, status, pageable);
        } else {
            page = sales.findByBusinessId(businessId, pageable);
        }
        Map<UUID, SaleCustomer> customers = catalog.customers(page.getContent().stream()
                .map(Sale::getCustomerId).filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<UUID, Long> lineCounts = lines.findByBusinessIdAndSaleIdIn(businessId,
                        page.getContent().stream().map(Sale::getId).toList()).stream()
                .collect(Collectors.groupingBy(SaleLine::getSaleId, Collectors.counting()));
        return page.map(sale -> new SaleView.Summary(sale.getId(), sale.reference(), sale.getStatus().name(),
                sale.getCustomerId(), sale.getCustomerId() == null || customers.get(sale.getCustomerId()) == null ? null
                        : customers.get(sale.getCustomerId()).label(),
                sale.getCurrency(), money(sale.getTotalInclTax(), sale.getCurrency()), paymentStatus(sale),
                lineCounts.getOrDefault(sale.getId(), 0L), sale.getCreatedAt(), sale.getConfirmedAt()));
    }

    /** Dernières ventes CONFIRMÉES ; montants retirés si {@code includeAmounts} est faux (employé, D14). */
    @Transactional(readOnly = true)
    public List<SaleView.Summary> recentConfirmed(int limit, boolean includeAmounts) {
        return list(SaleStatus.CONFIRMED, null, org.springframework.data.domain.PageRequest.of(0, limit,
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Order.desc("confirmedAt"))))
                .getContent().stream()
                .map(sale -> includeAmounts ? sale : new SaleView.Summary(sale.id(), sale.reference(), sale.status(),
                        sale.customerId(), sale.customerName(), sale.currency(), null, sale.paymentStatus(),
                        sale.lineCount(), sale.createdAt(), sale.confirmedAt()))
                .toList();
    }

    /** Ventes confirmées d'un client (historique commercial), les plus récentes d'abord. */
    @Transactional(readOnly = true)
    public Page<SaleView.Summary> customerHistory(UUID customerId, Pageable pageable) {
        return list(SaleStatus.CONFIRMED, customerId, pageable);
    }

    @Transactional(readOnly = true)
    public SaleView get(UUID id) {
        return view(sales.findByIdAndBusinessId(id, TenantContext.requireBusinessId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)));
    }

    // ─── Règles internes ───────────────────────────────────────────────────────────────────────

    /** Données vérifiées d'une vente : produits de l'entreprise, quantités, client, montants calculés. */
    record Prepared(List<LineInput> inputs, Map<UUID, SaleProduct> products, SaleCustomer customer,
            BigDecimal globalDiscount, SalesSettings settings, String currency, TaxCalculator.Result result) {

        SaleView.Quote quote(boolean discountWithinLimit) {
            List<SaleView.Line> views = new ArrayList<>();
            for (int i = 0; i < inputs.size(); i++) {
                LineInput input = inputs.get(i);
                SaleProduct product = products.get(input.productId());
                TaxCalculator.LineAmounts amounts = result.lines().get(i);
                views.add(new SaleView.Line(input.productId(), product.name(), product.sku(), product.unit(),
                        display(input.quantity()), product.salePrice(), amounts.lineDiscount(),
                        amounts.globalDiscountShare(), amounts.totalExclTax(), amounts.taxAmount(),
                        amounts.totalInclTax(), null));
            }
            return new SaleView.Quote(views, currency, globalDiscount, settings.vatEnabled(), settings.vatRate(),
                    settings.priceEntryMode(), result.gross(), result.discount(), result.totalExclTax(),
                    result.taxAmount(), result.totalInclTax(), discountWithinLimit);
        }
    }

    private Prepared prepare(DraftCommand command) {
        if (command.lines() == null || command.lines().isEmpty()) {
            throw FieldValidationException.of("lines", FieldErrorCode.REQUIRED);
        }
        if (command.lines().size() > MAX_LINES) {
            throw FieldValidationException.of("lines", FieldErrorCode.TOO_LONG);
        }
        String currency = profiles.activeBusinessProfile().currency();
        int scale = CurrencyAmounts.fractionDigits(currency);
        Map<UUID, SaleProduct> products = catalog.products(command.lines().stream()
                .map(LineInput::productId).filter(Objects::nonNull).toList());
        List<TaxCalculator.LineInput> inputs = new ArrayList<>();
        for (int i = 0; i < command.lines().size(); i++) {
            LineInput line = command.lines().get(i);
            String prefix = "lines[" + i + "].";
            SaleProduct product = line.productId() == null ? null : products.get(line.productId());
            if (product == null) {
                throw new ApiException(ErrorCode.NOT_FOUND); // produit inexistant ou d'une autre entreprise
            }
            if (!product.active()) {
                throw new ApiException(ErrorCode.PRODUCT_ARCHIVED);
            }
            if (line.quantity() == null || line.quantity().signum() <= 0
                    || Math.max(line.quantity().stripTrailingZeros().scale(), 0) > product.quantityDecimals()
                    || line.quantity().stripTrailingZeros().precision() - line.quantity().stripTrailingZeros().scale() > 15) {
                throw FieldValidationException.of(prefix + "quantity", FieldErrorCode.INVALID_VALUE);
            }
            BigDecimal discount = line.discount() == null ? BigDecimal.ZERO : line.discount();
            if (discount.signum() < 0 || !CurrencyAmounts.fitsCurrency(discount, currency)) {
                throw FieldValidationException.of(prefix + "discount", FieldErrorCode.INVALID_VALUE);
            }
            inputs.add(new TaxCalculator.LineInput(product.salePrice(), line.quantity(), discount));
        }
        BigDecimal global = command.globalDiscount() == null ? BigDecimal.ZERO : command.globalDiscount();
        if (global.signum() < 0 || !CurrencyAmounts.fitsCurrency(global, currency)) {
            throw FieldValidationException.of("globalDiscount", FieldErrorCode.INVALID_VALUE);
        }
        SaleCustomer customer = command.customerId() == null ? null : catalog.requireActiveCustomer(command.customerId());
        SalesSettings tax = settings.salesSettings();
        TaxCalculator.Result result;
        try {
            result = TaxCalculator.compute(inputs, global, tax.vatEnabled(), tax.vatRate(), tax.priceEntryMode(), scale);
        } catch (TaxCalculator.InvalidDiscountException invalid) {
            throw FieldValidationException.of(invalid.lineIndex() < 0 ? "globalDiscount"
                    : "lines[" + invalid.lineIndex() + "].discount", FieldErrorCode.INVALID_VALUE);
        }
        return new Prepared(command.lines(), products, customer, global, tax, currency, result);
    }

    private static boolean canGiveUnlimitedDiscount() {
        return TenantContext.requireUser().permissions().contains(Permission.SALE_DISCOUNT_UNLIMITED);
    }

    private static boolean discountWithinLimit(Prepared prepared) {
        if (canGiveUnlimitedDiscount() || prepared.result().discount().signum() == 0) {
            return true;
        }
        if (prepared.result().gross().signum() == 0) {
            return false;
        }
        BigDecimal rate = prepared.result().discount().multiply(HUNDRED)
                .divide(prepared.result().gross(), 4, RoundingMode.HALF_UP);
        return rate.compareTo(prepared.settings().employeeMaxDiscountRate()) <= 0;
    }

    /** Refus journalisé ; levé AVANT toute écriture (la transaction ne valide que l'événement). */
    private void enforceDiscountLimit(Prepared prepared, UUID saleId) {
        if (!discountWithinLimit(prepared)) {
            AuditEvent event = AuditEvent.of(AuditAction.DISCOUNT_LIMIT_EXCEEDED, AuditOutcome.DENIED)
                    .actor(TenantContext.requireUser().userId())
                    .business(TenantContext.requireBusinessId())
                    .with(AuditDetail.REASON, "DISCOUNT_LIMIT_EXCEEDED");
            audit.record(saleId == null ? event : event.target(AuditTarget.SALE, saleId));
            throw new DiscountLimitExceededException();
        }
    }

    private void saveLines(UUID saleId, Prepared prepared) {
        List<SaleLine> created = new ArrayList<>();
        for (int i = 0; i < prepared.inputs().size(); i++) {
            LineInput input = prepared.inputs().get(i);
            created.add(new SaleLine(saleId, i + 1, input.productId(), input.quantity(),
                    input.discount() == null ? BigDecimal.ZERO : input.discount()));
        }
        lines.saveAllAndFlush(created);
    }

    private static void requireDraft(Sale sale) {
        if (sale.getStatus() == SaleStatus.CONFIRMED) {
            throw new ApiException(ErrorCode.SALE_ALREADY_CONFIRMED);
        }
        if (sale.getStatus() != SaleStatus.DRAFT) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION);
        }
    }

    /** Aucun paiement validé n'existe encore (Wave non connecté) : jamais « réglée » sans preuve. */
    static String paymentStatus(Sale sale) {
        if (sale.getStatus() != SaleStatus.CONFIRMED) {
            return null;
        }
        return sale.getTotalInclTax().signum() == 0 ? "PAID" : "UNPAID";
    }

    private SaleView view(Sale sale) {
        UUID businessId = sale.getBusinessId();
        List<SaleLine> saleLines = lines.findByBusinessIdAndSaleIdOrderByPosition(businessId, sale.getId());
        boolean canReadCosts = TenantContext.requireUser().permissions().contains(Permission.COST_READ);
        SaleCustomer customer = sale.getCustomerId() == null ? null
                : catalog.customers(Set.of(sale.getCustomerId())).get(sale.getCustomerId());
        Map<UUID, String> authors = accounts.fullNamesOf(Set.of(sale.getCreatedBy()));
        SaleView.Quote quote;
        BigDecimal margin = null;
        boolean costsComplete = false;
        if (sale.getStatus() == SaleStatus.CONFIRMED) {
            String cur = sale.getCurrency();
            List<SaleView.Line> views = saleLines.stream().map(line -> new SaleView.Line(line.getProductId(),
                    line.getProductName(), line.getProductSku(), line.getUnit(), display(line.getQuantity()),
                    money(line.getUnitPrice(), cur), money(line.getLineDiscount(), cur),
                    money(line.getGlobalDiscountShare(), cur), money(line.getTotalExclTax(), cur),
                    money(line.getTaxAmount(), cur), money(line.getTotalInclTax(), cur),
                    canReadCosts && line.getUnitCost() != null
                            ? CurrencyAmounts.toUnitCostScale(line.getUnitCost(), cur) : null)).toList();
            quote = new SaleView.Quote(views, cur, money(sale.getGlobalDiscount(), cur), sale.getVatEnabled(),
                    sale.getVatRate(), sale.getPriceEntryMode(), money(sale.getGrossAmount(), cur),
                    money(sale.getDiscountAmount(), cur), money(sale.getTotalExclTax(), cur),
                    money(sale.getTaxAmount(), cur), money(sale.getTotalInclTax(), cur), true);
            if (canReadCosts) {
                costsComplete = saleLines.stream().allMatch(line -> line.getUnitCost() != null);
                if (costsComplete) {
                    // Marge BRUTE (jamais présentée comme un bénéfice) : HT − coût des marchandises vendues.
                    BigDecimal cost = saleLines.stream().map(line -> line.getQuantity().multiply(line.getUnitCost()))
                            .reduce(BigDecimal.ZERO, BigDecimal::add)
                            .setScale(CurrencyAmounts.fractionDigits(sale.getCurrency()), RoundingMode.HALF_UP);
                    margin = money(sale.getTotalExclTax(), sale.getCurrency()).subtract(cost);
                }
            }
        } else if (sale.getStatus() == SaleStatus.DRAFT) {
            DraftCommand command = new DraftCommand(sale.getCustomerId(), saleLines.stream()
                    .map(line -> new LineInput(line.getProductId(), line.getQuantity(), line.getLineDiscount())).toList(),
                    sale.getGlobalDiscount());
            Prepared prepared;
            try {
                prepared = prepare(command);
                quote = prepared.quote(discountWithinLimit(prepared));
            } catch (ApiException | FieldValidationException unavailable) {
                quote = null; // produit archivé ou client archivé depuis l'enregistrement du brouillon
            }
        } else {
            quote = null;
        }
        return new SaleView(sale.getId(), sale.reference(), sale.getStatus().name(), sale.getCustomerId(),
                customer == null ? null : customer.label(), sale.getCurrency(), sale.getGlobalDiscount(),
                saleLines.stream().map(line -> new SaleView.DraftLine(line.getProductId(), display(line.getQuantity()),
                        line.getLineDiscount())).toList(),
                quote, paymentStatus(sale), sale.getStatus() == SaleStatus.CONFIRMED ? BigDecimal.ZERO : null,
                canReadCosts && sale.getStatus() == SaleStatus.CONFIRMED ? margin : null,
                canReadCosts && sale.getStatus() == SaleStatus.CONFIRMED ? Boolean.valueOf(costsComplete) : null,
                sale.getCreatedBy(), authors.get(sale.getCreatedBy()), sale.getCreatedAt(), sale.getConfirmedAt(),
                sale.getVersion());
    }

    /** Montant à l'échelle de la devise (XOF : 0 décimale) ; valeurs enregistrées déjà exactes. */
    static BigDecimal money(BigDecimal amount, String currency) {
        return amount == null ? null : amount.setScale(CurrencyAmounts.fractionDigits(currency), RoundingMode.HALF_UP);
    }

    static BigDecimal display(BigDecimal quantity) {
        BigDecimal stripped = quantity.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private static AuditEvent event(AuditAction action, UUID saleId, AuditOutcome outcome) {
        // Aucun montant, nom de client ni produit dans l'événement.
        return AuditEvent.of(action, outcome)
                .actor(TenantContext.requireUser().userId())
                .business(TenantContext.requireBusinessId())
                .target(AuditTarget.SALE, saleId);
    }

    private static RuntimeException translate(DataIntegrityViolationException conflict) {
        String cause = String.valueOf(conflict.getMostSpecificCause().getMessage());
        if (cause.contains(IDEMPOTENCY_INDEX) || cause.contains(CONFIRM_IDEMPOTENCY_INDEX)) {
            return new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return conflict;
    }

    static String requestHash(DraftCommand command) {
        StringBuilder canonical = new StringBuilder(Objects.toString(command.customerId(), ""))
                .append('\u001f').append(canonical(command.globalDiscount()));
        if (command.lines() != null) {
            for (LineInput line : command.lines()) {
                canonical.append('\u001e').append(line.productId()).append('\u001f').append(canonical(line.quantity()))
                        .append('\u001f').append(canonical(line.discount()));
            }
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 indisponible", impossible);
        }
    }

    private static String canonical(BigDecimal value) {
        return value == null || value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }
}
