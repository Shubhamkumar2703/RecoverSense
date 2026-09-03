package com.recoversense.dashboard;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of the recent-cases table, derived from a RecoveryCase and its
 * most recent RecoveryDecision/RecoveryAction. policyResult/executionStatus/
 * verificationStatus are null only when the case has no decision yet;
 * policyResult is "BLOCKED" (with execution/verification left null) when the
 * decision exists but policy blocked it - no RecoveryAction is ever created
 * in that case (see RecoveryActionService.createIfAllowed).
 * <p>
 * M1.27: diagnosisSource ("CLAUDE"/"SIMULATED"/null) - see
 * DiagnosisSource.parsePrefix - so the UI can label a diagnosis truthfully
 * instead of leaving the provider unstated. Never inferred beyond what
 * DiagnosisService actually recorded.
 * <p>
 * M1.35: providerUrl mirrors RecoveryAction.providerUrl exactly (null unless
 * a PAYMENT_LINK action was created) - lets the frontend keep showing the
 * same hosted Payment Link on every later load of this case (including after
 * a page refresh), without re-executing anything.
 */
public record RecentCaseSummary(
        Long recoveryCaseId,
        Long paymentId,
        String externalPaymentId,
        BigDecimal amount,
        String currency,
        String failureReason,
        String diagnosisCategory,
        BigDecimal diagnosisConfidence,
        String strategy,
        String policyResult,
        String executionStatus,
        String verificationStatus,
        String caseStatus,
        Instant openedAt,
        String dataSource,
        String diagnosisSource,
        String providerUrl
) {
}
