package com.recoversense.batch;

import java.math.BigDecimal;

/**
 * revenueRecovered is summed ONLY from items whose outcome is
 * VERIFIED_RECOVERED - never from actionsAttempted or a payment-link-created
 * count. recoveryRate is a fraction (0..1), matching DashboardSummary's own
 * convention, so the frontend's existing formatPercent works unchanged.
 */
public record BatchMetrics(
        int batchSize,
        BigDecimal revenueAtRisk,
        int policyEligible,
        int policyBlocked,
        int actionsAttempted,
        int verifiedRecoveries,
        BigDecimal revenueRecovered,
        BigDecimal recoveryRate
) {
}
