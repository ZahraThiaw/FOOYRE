package com.fooyre.dashboard.api;

import com.fooyre.dashboard.application.DashboardService;
import com.fooyre.dashboard.application.DashboardService.Dashboard;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tableau de bord ({@code DASHBOARD_FULL} ou {@code DASHBOARD_OWN_DAY} ; montants filtrés par le service). */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService dashboard;

    DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('DASHBOARD_FULL', 'DASHBOARD_OWN_DAY')")
    public Dashboard get() {
        return dashboard.summary();
    }
}
