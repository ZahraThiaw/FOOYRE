package com.fooyre.dashboard.application;

import com.fooyre.commerce.application.SaleService;
import com.fooyre.commerce.application.SaleView;
import com.fooyre.finance.application.FinancialMetricsService;
import com.fooyre.finance.application.FinancialMetricsService.SalesMetrics;
import com.fooyre.gestion.application.SalesCatalogService;
import com.fooyre.gestion.application.StockService;
import com.fooyre.gestion.application.StockViews.SummaryView;
import com.fooyre.shared.security.Permission;
import com.fooyre.shared.tenant.TenantContext;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tableau de bord : uniquement des données réelles de la base. Indicateurs financiers (chiffre
 * d'affaires, encaissements, marge) réservés à {@code DASHBOARD_FULL} (OWNER, MANAGER) ; marge
 * avec {@code COST_READ} et seulement si les coûts sont fiables. Les employés voient les volumes
 * (nombre de ventes, clients, alertes de stock) sans montants (D14).
 */
@Service
public class DashboardService {

    public record Dashboard(SalesMetrics today, SalesMetrics month, long salesCountToday, long salesCountMonth,
            long activeCustomers, long outOfStock, long lowStock, long trackedProducts,
            List<SaleView.Summary> recentSales, boolean financialsVisible, boolean paymentsConnected) {
    }

    private final FinancialMetricsService metrics;
    private final StockService stock;
    private final SalesCatalogService catalog;
    private final SaleService sales;

    DashboardService(FinancialMetricsService metrics, StockService stock, SalesCatalogService catalog,
            SaleService sales) {
        this.metrics = metrics;
        this.stock = stock;
        this.catalog = catalog;
        this.sales = sales;
    }

    @Transactional(readOnly = true)
    public Dashboard summary() {
        Set<Permission> permissions = TenantContext.requireUser().permissions();
        boolean financials = permissions.contains(Permission.DASHBOARD_FULL);
        boolean margin = financials && permissions.contains(Permission.COST_READ);
        SalesMetrics today = metrics.salesMetrics(metrics.today(), margin);
        SalesMetrics month = metrics.salesMetrics(metrics.currentMonth(), margin);
        SummaryView stockSummary = permissions.contains(Permission.STOCK_READ) ? stock.summary() : null;
        List<SaleView.Summary> recent = permissions.contains(Permission.SALE_READ_ALL)
                ? sales.recentConfirmed(5, financials) : List.of();
        return new Dashboard(financials ? today : null, financials ? month : null, today.salesCount(), month.salesCount(),
                permissions.contains(Permission.CUSTOMER_READ) ? catalog.activeCustomerCount() : 0,
                stockSummary == null ? 0 : stockSummary.outOfStock(), stockSummary == null ? 0 : stockSummary.lowStock(),
                stockSummary == null ? 0 : stockSummary.trackedProducts(), recent, financials, false);
    }
}
