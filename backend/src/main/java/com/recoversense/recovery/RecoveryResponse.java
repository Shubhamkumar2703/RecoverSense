package com.recoversense.recovery;

import com.recoversense.domain.RecoveryAction;
import com.recoversense.service.RecoveryOrchestrationResult;

/**
 * HTTP-facing summary of one {@link com.recoversense.service.RecoveryOrchestrationService#recover}
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
        String outcome
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
                result.outcome().name());
    }
}
