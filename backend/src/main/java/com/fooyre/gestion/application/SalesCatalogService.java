package com.fooyre.gestion.application;

import com.fooyre.gestion.domain.CatalogStatus;
import com.fooyre.gestion.domain.Customer;
import com.fooyre.gestion.domain.CustomerRepository;
import com.fooyre.gestion.domain.Product;
import com.fooyre.gestion.domain.ProductRepository;
import com.fooyre.shared.error.ApiException;
import com.fooyre.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Interface PUBLIQUE du module gestion pour la vente et l'assistante : produits, clients et
 * précision des quantités, sans exposer les types internes du domaine. Toujours filtré par
 * l'entreprise du contexte authentifié.
 */
@Service
public class SalesCatalogService {

    /** Produit tel que la vente en a besoin (prix de vente COURANT lu en base). */
    public record SaleProduct(UUID id, String name, String sku, String unit, int quantityDecimals,
            BigDecimal salePrice, boolean active) {
    }

    /** Client (données minimales). */
    public record SaleCustomer(UUID id, String name, String phone, boolean active) {

        public String label() {
            return name != null ? name : phone;
        }
    }

    private final ProductRepository products;
    private final CustomerRepository customers;
    private final CatalogContext context;

    SalesCatalogService(ProductRepository products, CustomerRepository customers, CatalogContext context) {
        this.products = products;
        this.customers = customers;
        this.context = context;
    }

    @Transactional(readOnly = true)
    public Map<UUID, SaleProduct> products(Collection<UUID> ids) {
        UUID businessId = context.current().businessId();
        Map<UUID, SaleProduct> found = new HashMap<>();
        for (UUID id : new HashSet<>(ids)) {
            products.findByIdAndBusinessId(id, businessId).ifPresent(product -> found.put(id, toView(product)));
        }
        return found;
    }

    /** Client actif de l'entreprise : {@code 404} s'il n'existe pas, {@code 422} s'il est archivé. */
    @Transactional(readOnly = true)
    public SaleCustomer requireActiveCustomer(UUID id) {
        Customer customer = customers.findByIdAndBusinessId(id, context.current().businessId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!customer.isActive()) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION);
        }
        return toView(customer);
    }

    @Transactional(readOnly = true)
    public Map<UUID, SaleCustomer> customers(Collection<UUID> ids) {
        UUID businessId = context.current().businessId();
        Map<UUID, SaleCustomer> found = new HashMap<>();
        for (UUID id : new HashSet<>(ids)) {
            if (id != null) {
                customers.findByIdAndBusinessId(id, businessId).ifPresent(c -> found.put(id, toView(c)));
            }
        }
        return found;
    }

    /** Nombre de clients actifs (tableau de bord). */
    @Transactional(readOnly = true)
    public long activeCustomerCount() {
        UUID businessId = context.current().businessId();
        return customers.findAll(new com.fooyre.gestion.domain.CustomerSearch(null, CatalogStatus.ACTIVE)
                .forBusiness(businessId), PageRequest.of(0, 1)).getTotalElements();
    }

    /** Produits ACTIFS de l'entreprise, triés par nom (assistante : instantané borné). */
    @Transactional(readOnly = true)
    public java.util.List<SaleProduct> activeProducts(int limit) {
        UUID businessId = context.current().businessId();
        return products.findAll(new com.fooyre.gestion.domain.ProductSearch(null, null, CatalogStatus.ACTIVE)
                        .forBusiness(businessId),
                PageRequest.of(0, limit, org.springframework.data.domain.Sort.by("name"))).getContent().stream()
                .map(SalesCatalogService::toView).toList();
    }

    /** Produit de l'entreprise par identifiant. */
    @Transactional(readOnly = true)
    public Optional<SaleProduct> product(UUID id) {
        return products.findByIdAndBusinessId(id, context.current().businessId()).map(SalesCatalogService::toView);
    }

    private static SaleProduct toView(Product product) {
        return new SaleProduct(product.getId(), product.getName(), product.getSku(), product.getUnit().name(),
                product.getUnit().quantityDecimals(), product.getSalePrice(), product.isActive());
    }

    private static SaleCustomer toView(Customer customer) {
        return new SaleCustomer(customer.getId(), customer.getName(), customer.getPhone(), customer.isActive());
    }
}
