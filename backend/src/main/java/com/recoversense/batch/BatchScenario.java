package com.recoversense.batch;

import com.recoversense.domain.CustomerStatus;
import com.recoversense.settlement.SettlementState;

import java.math.BigDecimal;

/**
 * One fixed, non-persisted representative failed-payment scenario. Never
 * saved to the database, never sent to Razorpay - {@link BatchEvaluationService}
 * runs each one through the real, unmodified DiagnosisEngine/StrategyRouter/
 * PolicyEngine to derive a diagnosis/strategy/policy outcome exactly as the
 * live pipeline would.
 * <p>
 * {@code scriptedVerified} only matters for a scenario that actually reaches
 * an ALLOWED policy decision with the one executable strategy (PAYMENT_LINK):
 * {@code true} models a real Razorpay Payment Link that was paid and
 * independently verified, {@code false} models one that was created but not
 * yet paid/verified (execution never implies recovery). For every other
 * scenario it is {@code null} and never consulted, because policy or
 * strategy already stops those before execution would be considered.
 */
record BatchScenario(
        String externalPaymentId,
        String description,
        BigDecimal amount,
        String failureReason,
        CustomerStatus customerStatus,
        String subscriptionStatus,
        int retryCountForProposedAction,
        boolean pendingReacquisitionExists,
        SettlementState settlementState,
        long minutesSinceFailure,
        Boolean scriptedVerified
) {
}
