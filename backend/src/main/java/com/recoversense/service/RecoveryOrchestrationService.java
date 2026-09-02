package com.recoversense.service;

import com.recoversense.domain.ExecutionStatus;
import com.recoversense.domain.RecoveryAction;
import com.recoversense.domain.RecoveryCase;
import com.recoversense.domain.RecoveryCaseStatus;
import com.recoversense.domain.VerificationStatus;
import com.recoversense.repository.RecoveryActionRepository;
import com.recoversense.repository.RecoveryCaseRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Application-level composition root for the fixed lifecycle: Diagnosis ->
 * Strategy -> Policy -> Execution -> Verification -> Audit. Every step is
 * delegated to its existing, unmodified owner - this class contains no
 * diagnosis, policy, execution, or verification logic of its own, and never
 * calls PolicyEngine, an executor, or a verifier directly.
 * <p>
 * Diagnosis already produces the strategy (DiagnosisResult.strategy()/
 * actionType() - see DiagnosisEngine), so there is no separate strategy step
 * to call here.
 * <p>
 * Deliberately NOT wrapped in a single @Transactional: each delegate
 * (RecoveryLifecycleService, RecoveryActionExecutionService,
 * RecoveryActionVerificationService) already commits its own step in its own
 * transaction. Joining them into one outer transaction would mean an
 * execution/verification failure - expected, routine outcomes, not
 * exceptional ones - rolls back the case/decision/policy audit trail that
 * was already correctly committed. Each phase must stand on its own.
 */
@Service
public class RecoveryOrchestrationService {

    private final DiagnosisService diagnosisService;
    private final RecoveryLifecycleService recoveryLifecycleService;
    private final RecoveryActionExecutionService recoveryActionExecutionService;
    private final RecoveryActionVerificationService recoveryActionVerificationService;
    private final RecoveryCaseRepository recoveryCaseRepository;
    private final RecoveryActionRepository recoveryActionRepository;

    public RecoveryOrchestrationService(DiagnosisService diagnosisService,
                                         RecoveryLifecycleService recoveryLifecycleService,
                                         RecoveryActionExecutionService recoveryActionExecutionService,
                                         RecoveryActionVerificationService recoveryActionVerificationService,
                                         RecoveryCaseRepository recoveryCaseRepository,
                                         RecoveryActionRepository recoveryActionRepository) {
        this.diagnosisService = diagnosisService;
        this.recoveryLifecycleService = recoveryLifecycleService;
        this.recoveryActionExecutionService = recoveryActionExecutionService;
        this.recoveryActionVerificationService = recoveryActionVerificationService;
        this.recoveryCaseRepository = recoveryCaseRepository;
        this.recoveryActionRepository = recoveryActionRepository;
    }

    /**
     * Runs one payment through the full lifecycle. Throws (does not execute
     * anything) if the payment does not exist or is not FAILED - see
     * DiagnosisService.diagnose and RecoveryLifecycleService.processFailedPayment.
     */
    public RecoveryOrchestrationResult recover(Long paymentId) {
        RecoveryDiagnosisInput diagnosisInput = diagnosisService.diagnose(paymentId);

        RecoveryLifecycleResult lifecycleResult = recoveryLifecycleService.processFailedPayment(paymentId, diagnosisInput);

        if (lifecycleResult.action().isEmpty()) {
            return result(lifecycleResult, Optional.empty(), RecoveryOutcome.BLOCKED);
        }
        RecoveryAction pendingAction = lifecycleResult.action().orElseThrow();

        RecoveryAction executedAction;
        try {
            executedAction = recoveryActionExecutionService.attemptExecution(pendingAction.getId());
        } catch (UnsupportedOperationException executionUnavailable) {
            return result(lifecycleResult, Optional.of(pendingAction), RecoveryOutcome.EXECUTION_UNAVAILABLE);
        }
        if (executedAction.getExecutionStatus() != ExecutionStatus.EXECUTED) {
            return result(lifecycleResult, Optional.of(executedAction), RecoveryOutcome.EXECUTION_FAILED);
        }

        // M1.25: verification is a deliberate second phase (see #verify),
        // never automatic - some actions (a real Payment Link) require a
        // human to act out-of-band first, and RecoveryActionVerificationService's
        // verification is one-shot/terminal, so attempting it here before
        // that happens would permanently burn the only real attempt.
        return result(lifecycleResult, Optional.of(executedAction), RecoveryOutcome.EXECUTED_AWAITING_VERIFICATION);
    }

    /**
     * M1.25 phase 2 / M1.26: independently re-verifies the action recover()
     * already created and executed for this case, and transitions the case
     * to RECOVERED only if that verification confirms it. Never creates a
     * case/decision/action and never calls an executor - reuses
     * RecoveryActionVerificationService's verification boundary unchanged.
     * <p>
     * Idempotent against the one truly terminal state: once VERIFIED (from
     * an earlier verify() call), this returns that existing RECOVERED state
     * without attempting verification again - a repeated "Verify payment"
     * click after success is always safe and never re-transitions the case.
     * <p>
     * M1.26: a prior FAILED result is NOT short-circuited - it is retried
     * through the real independent re-fetch, because "FAILED" from a real
     * Payment Link most often means "not paid yet", not "never verifiable"
     * (see RecoveryActionVerificationService's relaxed guard). This never
     * fabricates anything and never touches the executor either way - only
     * VERIFIED skips a repeat provider call.
     */
    public RecoveryVerificationResult verify(Long recoveryCaseId) {
        RecoveryCase recoveryCase = recoveryCaseRepository.findById(recoveryCaseId)
                .orElseThrow(() -> new RecoveryCaseNotFoundException(recoveryCaseId));
        RecoveryAction action = recoveryActionRepository.findTopByRecoveryCaseOrderByIdDesc(recoveryCase)
                .orElseThrow(() -> new RecoveryActionNotFoundException(
                        "No recovery action exists for recovery case " + recoveryCaseId));

        if (action.getVerificationStatus() == VerificationStatus.VERIFIED) {
            return verificationResult(recoveryCase, action, RecoveryOutcome.RECOVERED);
        }

        RecoveryAction verifiedAction;
        try {
            verifiedAction = recoveryActionVerificationService.attemptVerification(action.getId());
        } catch (UnsupportedOperationException verificationUnavailable) {
            return verificationResult(recoveryCase, action, RecoveryOutcome.VERIFICATION_UNAVAILABLE);
        }
        if (verifiedAction.getVerificationStatus() != VerificationStatus.VERIFIED) {
            return verificationResult(recoveryCase, verifiedAction, RecoveryOutcome.VERIFICATION_FAILED);
        }

        RecoveryCase recoveredCase = recoveryLifecycleService.transitionCase(recoveryCaseId, RecoveryCaseStatus.RECOVERED);
        return verificationResult(recoveredCase, verifiedAction, RecoveryOutcome.RECOVERED);
    }

    private RecoveryOrchestrationResult result(RecoveryLifecycleResult lifecycleResult, Optional<RecoveryAction> action,
                                                RecoveryOutcome outcome) {
        return new RecoveryOrchestrationResult(lifecycleResult.recoveryCase(), lifecycleResult.decision(),
                lifecycleResult.policyDecision(), action, outcome);
    }

    private RecoveryVerificationResult verificationResult(RecoveryCase recoveryCase, RecoveryAction action, RecoveryOutcome outcome) {
        return new RecoveryVerificationResult(recoveryCase, action.getRecoveryDecision(), action.getPolicyResult(), action, outcome);
    }
}
