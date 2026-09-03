package com.recoversense.batch;

import com.recoversense.policy.PolicyCheckResult;

import java.math.BigDecimal;
import java.util.List;

/**
 * One evaluated batch item. {@code policyChecks} reuses PolicyCheckResult
 * exactly as PolicyEngine produces it (same shape the real POLICY_EVALUATED
 * audit payload already serializes) - the frontend's existing
 * policyCheckLabel/policyCheckState helpers work on this unchanged.
 * {@code recoveredAmount} is non-null only when {@code outcome} is
 * VERIFIED_RECOVERED - this is the only evidence a batch total may sum.
 */
public record BatchItemResult(
        String externalPaymentId,
        String description,
        BigDecimal amount,
        String failureReason,
        String diagnosisCategory,
        BigDecimal diagnosisConfidence,
        String strategy,
        String policyResult,
        List<PolicyCheckResult> policyChecks,
        String outcome,
        BigDecimal recoveredAmount
) {
}
