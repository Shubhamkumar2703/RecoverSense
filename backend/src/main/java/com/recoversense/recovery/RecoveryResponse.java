package com.recoversense.recovery;

import com.recoversense.diagnosis.DiagnosisSource;
import com.recoversense.domain.RecoveryAction;
import com.recoversense.service.RecoveryOrchestrationResult;
import com.recoversense.service.RecoveryVerificationResult;

/**
 * HTTP-facing summary of one {@link com.recoversense.service.RecoveryOrchestrationService#recover}
 * or {@link com.recoversense.service.RecoveryOrchestrationService#verify}
 * run - deliberately not the domain result itself, so persistence/entity
 * structure (lazy associations, other entities' internals) never leaks
 * through the API boundary. Only the fields the demo needs to explain what
 * happened: no Razorpay/Claude credentials, no raw provider response, no
 * customer PII beyond what the caller already supplied as paymentId.
 */
record RecoveryResponse(
        Long paymentId,
        Long recoveryCaseId,
        String caseStatus,
        String diagnosisCategory,
        String strategy,
        String policyResult,
        String executionStatus,
        String verificationStatus,
        String externalReference,
        String outcome,
        String providerUrl,
        String diagnosisSource
) {

    static RecoveryResponse from(Long paymentId, RecoveryOrchestrationResult result) {
        RecoveryAction action = result.action().orElse(null);
        return new RecoveryResponse(
                paymentId,
                result.recoveryCase().getId(),
                result.recoveryCase().getStatus().name(),
                result.decision().getDiagnosisCategory(),
                result.decision().getStrategy(),
                result.policyDecision().result().name(),
                action == null ? null : action.getExecutionStatus().name(),
                action == null ? null : action.getVerificationStatus().name(),
                action == null ? null : action.getExternalReference(),
                result.outcome().name(),
                // M1.25: only ever non-null in the same response that just
                // executed a fresh PAYMENT_LINK action - see
                // RecoveryAction.providerUrl. Never present on a verify()
                // response (fromVerification never sets it): the operator
                // already had it from the recover() response.
                action == null ? null : action.getProviderUrl(),
                diagnosisSourceName(result.decision().getDiagnosisRaw()));
    }

    static RecoveryResponse fromVerification(Long paymentId, RecoveryVerificationResult result) {
        RecoveryAction action = result.action();
        return new RecoveryResponse(
                paymentId,
                result.recoveryCase().getId(),
                result.recoveryCase().getStatus().name(),
                result.decision().getDiagnosisCategory(),
                result.decision().getStrategy(),
                result.policyResult().name(),
                action.getExecutionStatus().name(),
                action.getVerificationStatus().name(),
                action.getExternalReference(),
                result.outcome().name(),
                null,
                diagnosisSourceName(result.decision().getDiagnosisRaw()));
    }

    // M1.27: "CLAUDE"/"SIMULATED"/null - see DiagnosisSource.parsePrefix -
    // so the frontend can label a diagnosis truthfully instead of leaving
    // the provider unstated.
    private static String diagnosisSourceName(String diagnosisRaw) {
        DiagnosisSource source = DiagnosisSource.parsePrefix(diagnosisRaw);
        return source == null ? null : source.name();
    }
}
