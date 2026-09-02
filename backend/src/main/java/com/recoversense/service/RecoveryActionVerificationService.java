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
 * already-VERIFIED action is rejected - that is the sole terminal state.
 * <p>
 * M1.26: a FAILED verification may be re-attempted (e.g. a real Razorpay
 * Payment Link genuinely was not yet paid when first checked) - this never
 * fabricates anything, since each attempt is still an independent re-fetch
 * through the same {@link RecoveryActionVerifier}; it only changes when a
 * caller is allowed to ask again. Only VERIFIED is a one-way door.
 * <p>
 * No Razorpay/HTTP client is wired here; the only current
 * {@link RecoveryActionVerifier} throws {@link UnsupportedOperationException},
 * which is surfaced honestly rather than treated as a verification result.
 * <p>
 * attemptVerification is annotated with noRollbackFor on that one exception
 * type: without it, a caller outside any ambient transaction (the real
 * application path - see RecoveryOrchestrationService) makes this method
 * the transaction's own owner, and rethrowing after the audit save would
 * trigger Spring's default rollback-on-RuntimeException behavior, discarding
 * the very audit event that records the failure - confirmed empirically.
 * noRollbackFor commits normally (including that audit row) while still
 * letting the exception propagate to the caller.
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

    @Transactional(noRollbackFor = UnsupportedOperationException.class)
    public RecoveryAction attemptVerification(Long recoveryActionId) {
        RecoveryAction action = recoveryActionRepository.findById(recoveryActionId)
                .orElseThrow(() -> new RecoveryActionNotFoundException(recoveryActionId));

        if (action.getExecutionStatus() != ExecutionStatus.EXECUTED) {
            throw new InvalidActionTransitionException(
                    "Cannot verify RecoveryAction " + recoveryActionId + ": executionStatus is " + action.getExecutionStatus() + ", expected EXECUTED");
        }
        if (action.getVerificationStatus() == VerificationStatus.VERIFIED) {
            throw new InvalidActionTransitionException(
                    "Cannot verify RecoveryAction " + recoveryActionId + ": verificationStatus is already VERIFIED");
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
