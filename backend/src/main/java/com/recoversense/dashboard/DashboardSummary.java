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
 * <p>
 * M1.26: failedPaymentsCount/recoveredCasesCount/pendingVerificationCount/
 * executionIssuesCount - all computed from the same persisted rows, never
 * hardcoded - support the batch-measured-outcome view (docs/DEMO.md).
 * pendingVerificationCount is EXECUTED actions still UNVERIFIED (the
 * EXECUTED_AWAITING_VERIFICATION state - see RecoveryOutcome).
 * executionIssuesCount is executions that genuinely FAILED plus attempts
 * where no provider was wired up at all (ACTION_EXECUTION_UNAVAILABLE audit
 * events) - both are "did not execute successfully", counted together since
 * neither ever produces a RecoveryAction the operator can act on further.
 */
public record DashboardSummary(
        BigDecimal revenueAtRisk,
        BigDecimal recoveredRevenue,
        BigDecimal recoveryRate,
        long verifiedActions,
        long policyBlocks,
        long failedPaymentsCount,
        long recoveredCasesCount,
        long pendingVerificationCount,
        long executionIssuesCount
) {
}
