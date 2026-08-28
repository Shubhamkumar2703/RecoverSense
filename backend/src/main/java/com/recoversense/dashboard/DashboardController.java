package com.recoversense.dashboard;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only HTTP boundary for the dashboard. Every method here is a GET
 * backed by a DashboardMetricsService query - nothing in this controller (or
 * reachable from it) creates a RecoveryAction, executes a provider call,
 * transitions a RecoveryCase, or writes an AuditEvent.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardMetricsService dashboardMetricsService;

    public DashboardController(DashboardMetricsService dashboardMetricsService) {
        this.dashboardMetricsService = dashboardMetricsService;
    }

    @GetMapping("/metrics")
    public DashboardResponse metrics() {
        return dashboardMetricsService.buildDashboard();
    }

    @GetMapping("/cases/{recoveryCaseId}/audit")
    public List<AuditEventSummary> auditTrail(@PathVariable Long recoveryCaseId) {
        return dashboardMetricsService.auditTrailFor(recoveryCaseId);
    }
}
