package com.recoversense.dashboard;

import java.math.BigDecimal;

/**
 * revenueAtRisk: sum of payment.amount across every RecoveryCase (every case
 * originates from a FAILED payment - see RecoveryLifecycleService).
 * recoveredRevenue: same sum restricted to cases whose status is RECOVERED,
 * which only happens after independent verification (see
 * RecoveryOrchestrationService.recover). recoveryRate: recoveredRevenue /
 * revenueAtRisk, zero when nothing is at risk. verifiedActions: count of
 * RecoveryAction rows with verificationStatus VERIFIED. policyBlocks: count
 * of ACTION_NOT_CREATED audit events, the only place a policy BLOCK is
 * recorded (a blocked decision never gets a RecoveryAction row).
 */
public record DashboardSummary(
        BigDecimal revenueAtRisk,
        BigDecimal recoveredRevenue,
        BigDecimal recoveryRate,
        long verifiedActions,
        long policyBlocks
) {
}
