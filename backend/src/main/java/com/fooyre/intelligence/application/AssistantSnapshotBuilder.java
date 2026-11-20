package com.fooyre.intelligence.application;

import com.fooyre.business.application.BusinessProfileService;
import com.fooyre.commerce.application.SaleService;
import com.fooyre.commerce.application.SaleView;
import com.fooyre.commerce.application.SalesQueryService;
import com.fooyre.finance.application.FinancialMetricsService;
import com.fooyre.finance.application.FinancialMetricsService.SalesMetrics;
import com.fooyre.gestion.application.SalesCatalogService;
import com.fooyre.gestion.application.SalesCatalogService.SaleProduct;
import com.fooyre.gestion.application.StockService;
import com.fooyre.gestion.application.StockViews.StockItemView;
import com.fooyre.gestion.application.StockViews.SummaryView;
import com.fooyre.shared.security.Permission;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Instantané PRÉ‑CALCULÉ, AGRÉGÉ et PSEUDONYMISÉ transmis à l'IA (CLAUDE.md §9, D18). Construit
 * uniquement avec les services applicatifs et SELON LES PERMISSIONS de l'utilisatrice, AVANT tout
 * appel : sans {@code COST_READ}, aucun coût ni marge ; sans {@code DASHBOARD_FULL}, aucun montant
 * de chiffre d'affaires ; jamais de soldes, avoirs, téléphones ni noms de clients (références C1…).
 */
@Component
class AssistantSnapshotBuilder {

    static final int MAX_PRODUCTS = 80;
    static final int RECENT_SALES = 10;

    /** Instantané + correspondances références → identifiants (jamais envoyées au fournisseur). */
    record Snapshot(String text, Map<String, SaleProduct> products, Map<String, UUID> customerIds,
            Map<String, String> customerNames) {
    }

    private final SalesCatalogService catalog;
    private final StockService stock;
    private final SaleService sales;
    private final SalesQueryService salesQuery;
    private final FinancialMetricsService metrics;
    private final BusinessProfileService profiles;
    private final Clock clock;

    AssistantSnapshotBuilder(SalesCatalogService catalog, StockService stock, SaleService sales,
            SalesQueryService salesQuery, FinancialMetricsService metrics, BusinessProfileService profiles, Clock clock) {
        this.catalog = catalog;
        this.stock = stock;
        this.sales = sales;
        this.salesQuery = salesQuery;
        this.metrics = metrics;
        this.profiles = profiles;
        this.clock = clock;
    }

    Snapshot build(Set<Permission> permissions) {
        boolean costs = permissions.contains(Permission.COST_READ);
        boolean financials = permissions.contains(Permission.DASHBOARD_FULL);
        String currency = profiles.activeBusinessProfile().currency();
        String zone = profiles.activeBusinessProfile().timezone();
        StringBuilder text = new StringBuilder();
        LocalDate today;
        try {
            today = LocalDate.now(clock.withZone(ZoneId.of(zone)));
        } catch (RuntimeException invalid) {
            today = LocalDate.now(clock);
        }
        text.append("INSTANTANÉ FOOYRE\nDate du jour : ").append(today).append("\nDevise : ").append(currency).append('\n');

        // Produits et stock.
        Map<String, SaleProduct> productRefs = new LinkedHashMap<>();
        if (permissions.contains(Permission.STOCK_READ) || permissions.contains(Permission.CATALOG_READ)) {
            List<SaleProduct> products = catalog.activeProducts(MAX_PRODUCTS);
            Map<UUID, StockItemView> stocks = permissions.contains(Permission.STOCK_READ)
                    ? stock.stockOf(products.stream().map(SaleProduct::id).toList()) : Map.of();
            text.append("\nPRODUITS ACTIFS (").append(products.size()).append(products.size() == MAX_PRODUCTS
                    ? ", liste limitée aux " + MAX_PRODUCTS + " premiers par ordre alphabétique" : "").append(") :\n");
            int index = 1;
            for (SaleProduct product : products) {
                String ref = "P" + index++;
                productRefs.put(ref, product);
                text.append("- ").append(ref).append(" | ").append(product.name()).append(" | unité ")
                        .append(product.unit()).append(" | prix de vente ").append(plain(product.salePrice()))
                        .append(' ').append(currency);
                StockItemView item = stocks.get(product.id());
                if (item != null) {
                    if (item.quantity() == null) {
                        text.append(" | stock non suivi");
                    } else {
                        text.append(" | quantité disponible ").append(plain(item.quantity()))
                                .append(" | seuil minimal ").append(plain(item.minimumQuantity()))
                                .append(" | état ").append(String.valueOf(item.status()));
                    }
                    if (costs) {
                        text.append(" | coût moyen pondéré ").append(item.averageCost() == null ? "inconnu"
                                : plain(item.averageCost()) + " " + currency);
                    }
                }
                text.append('\n');
            }
        }
        if (permissions.contains(Permission.STOCK_READ)) {
            SummaryView summary = stock.summary();
            text.append("\nSTOCK (synthèse) : produits suivis ").append(summary.trackedProducts())
                    .append(", en stock ").append(summary.inStock()).append(", stock faible ").append(summary.lowStock())
                    .append(", en rupture ").append(summary.outOfStock()).append(", non suivis ")
                    .append(summary.notInitializedProducts()).append('\n');
        }

        // Ventes (confirmées uniquement).
        Map<String, UUID> customerIds = new LinkedHashMap<>();
        Map<String, String> customerNames = new LinkedHashMap<>();
        if (permissions.contains(Permission.SALE_READ_ALL)) {
            SalesMetrics day = metrics.salesMetrics(metrics.today(), costs && financials);
            SalesMetrics month = metrics.salesMetrics(metrics.currentMonth(), costs && financials);
            text.append("\nVENTES CONFIRMÉES (les brouillons ne comptent pas ; aucun paiement enregistré) :\n")
                    .append("- aujourd'hui : ").append(day.salesCount()).append(" vente(s)");
            if (financials) {
                text.append(", chiffre d'affaires TTC ").append(plain(day.revenueInclTax())).append(' ').append(currency);
            }
            text.append("\n- ce mois-ci : ").append(month.salesCount()).append(" vente(s)");
            if (financials) {
                text.append(", chiffre d'affaires TTC ").append(plain(month.revenueInclTax())).append(' ').append(currency)
                        .append(", encaissements enregistrés ").append(plain(month.collections())).append(' ').append(currency);
                if (costs) {
                    text.append(", marge brute ").append(month.grossMargin() == null
                            ? "inconnue (coûts d'achat historiques incomplets)" : plain(month.grossMargin()) + " " + currency);
                }
            }
            text.append('\n');
            FinancialMetricsService.Period period = metrics.currentMonth();
            List<SalesQueryService.ProductSales> top = salesQuery.topProducts(period.from(), period.to(), 10);
            text.append("PRODUITS LES PLUS VENDUS CE MOIS-CI :\n");
            if (top.isEmpty()) {
                text.append("- aucune vente confirmée ce mois-ci\n");
            }
            for (SalesQueryService.ProductSales line : top) {
                text.append("- ").append(line.productName()).append(" : quantité ").append(plain(line.quantity()));
                if (financials) {
                    text.append(", montant TTC ").append(plain(line.totalInclTax())).append(' ').append(currency);
                }
                text.append('\n');
            }
            text.append("DERNIÈRES VENTES CONFIRMÉES :\n");
            List<SaleView.Summary> recent = sales.recentConfirmed(RECENT_SALES, financials);
            if (recent.isEmpty()) {
                text.append("- aucune\n");
            }
            int customerIndex = 1;
            for (SaleView.Summary sale : recent) {
                String customerRef = "aucun client";
                if (sale.customerId() != null && permissions.contains(Permission.CUSTOMER_READ)) {
                    customerRef = customerIds.entrySet().stream().filter(e -> e.getValue().equals(sale.customerId()))
                            .map(Map.Entry::getKey).findFirst().orElse(null);
                    if (customerRef == null) {
                        customerRef = "C" + customerIndex++;
                        customerIds.put(customerRef, sale.customerId());
                        customerNames.put(customerRef, sale.customerName());
                    }
                } else if (sale.customerId() != null) {
                    customerRef = "client non communiqué";
                }
                text.append("- ").append(sale.reference()).append(" | ").append(sale.confirmedAt().atZone(ZoneId.of("UTC"))
                        .toLocalDate()).append(" | client ").append(customerRef).append(" | ").append(sale.lineCount())
                        .append(" ligne(s)");
                if (financials && sale.totalInclTax() != null) {
                    text.append(" | total TTC ").append(plain(sale.totalInclTax())).append(' ').append(currency);
                }
                text.append(" | règlement : non réglée\n");
            }
        }
        if (permissions.contains(Permission.CUSTOMER_READ)) {
            text.append("\nCLIENTS ACTIFS : ").append(catalog.activeCustomerCount())
                    .append(" (noms jamais transmis ; seuls les clients des dernières ventes ont une référence)\n");
        }
        return new Snapshot(text.toString(), productRefs, customerIds, customerNames);
    }

    static String plain(BigDecimal value) {
        if (value == null) {
            return "inconnu";
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }
}
