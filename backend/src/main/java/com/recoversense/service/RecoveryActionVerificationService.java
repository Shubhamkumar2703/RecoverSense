package com.recoversense.service;

import com.recoversense.domain.AuditEvent;
import com.recoversense.domain.ExecutionStatus;
import com.recoversense.domain.RecoveryAction;
import com.recoversense.domain.VerificationStatus;
import com.recoversense.repository.AuditEventRepository;
import com.recoversense.repository.RecoveryActionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

/**
 * Verification boundary for a RecoveryAction, kept fully independent of
 * execution: verification is never inferred from executionStatus alone,
 * only gated by it (must be EXECUTED first). Re-verifying an
 * already-VERIFIED/FAILED action is rejected - those are terminal.
 * <p>
 * No Razorpay/HTTP client is wired here; the only current
 * {@link RecoveryActionVerifier} throws {@link UnsupportedOperationException},
 * which is surfaced honestly rather than treated as a verification result.
 */
@Service
public class RecoveryActionVerificationService {

    private final RecoveryActionRepository recoveryActionRepository;
    private final AuditEventRepository auditEventRepository;
    private final RecoveryActionVerifier recoveryActionVerifier;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public RecoveryActionVerificationService(RecoveryActionRepository recoveryActionRepository,
                                              AuditEventRepository auditEventRepository,
                                              RecoveryActionVerifier recoveryActionVerifier) {
        this.recoveryActionRepository = recoveryActionRepository;
        this.auditEventRepository = auditEventRepository;
        this.recoveryActionVerifier = recoveryActionVerifier;
    }

    @Transactional
    public RecoveryAction attemptVerification(Long recoveryActionId) {
        RecoveryAction action = recoveryActionRepository.findById(recoveryActionId)
                .orElseThrow(() -> new RecoveryActionNotFoundException(recoveryActionId));

        if (action.getExecutionStatus() != ExecutionStatus.EXECUTED) {
            throw new InvalidActionTransitionException(
                    "Cannot verify RecoveryAction " + recoveryActionId + ": executionStatus is " + action.getExecutionStatus() + ", expected EXECUTED");
        }
        if (action.getVerificationStatus() != VerificationStatus.UNVERIFIED) {
            throw new InvalidActionTransitionException(
                    "Cannot verify RecoveryAction " + recoveryActionId + ": verificationStatus is " + action.getVerificationStatus() + ", expected UNVERIFIED");
        }

        VerificationStatus outcome;
        try {
            outcome = recoveryActionVerifier.verify(action);
        } catch (UnsupportedOperationException unavailable) {
            auditEventRepository.save(new AuditEvent(action.getRecoveryDecision().getRecoveryCase(), "ACTION_VERIFICATION_UNAVAILABLE",
                    toJson(new VerificationUnavailablePayload(action.getId(), Instant.now()))));
            throw unavailable;
        }
        if (outcome == VerificationStatus.UNVERIFIED) {
            throw new IllegalStateException("RecoveryActionVerifier must not return UNVERIFIED");
        }

        action.setVerificationStatus(outcome);
        action.setVerifiedAt(Instant.now());
        recoveryActionRepository.save(action);

        auditEventRepository.save(new AuditEvent(action.getRecoveryDecision().getRecoveryCase(), "ACTION_VERIFICATION_ATTEMPTED",
                toJson(new VerificationAttemptedPayload(action.getId(), outcome.name(), action.getVerifiedAt()))));

        return action;
    }

    private String toJson(Object payload) {
        return jsonMapper.writeValueAsString(payload);
    }

    private record VerificationAttemptedPayload(Long recoveryActionId, String outcome, Instant verifiedAt) {
    }

    private record VerificationUnavailablePayload(Long recoveryActionId, Instant attemptedAt) {
    }
}
