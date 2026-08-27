package com.recoversense.service;

import com.recoversense.domain.ExecutionStatus;
import com.recoversense.domain.RecoveryAction;
import com.recoversense.domain.RecoveryCase;
import com.recoversense.domain.RecoveryCaseStatus;
import com.recoversense.domain.VerificationStatus;
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

    public RecoveryOrchestrationService(DiagnosisService diagnosisService,
                                         RecoveryLifecycleService recoveryLifecycleService,
                                         RecoveryActionExecutionService recoveryActionExecutionService,
                                         RecoveryActionVerificationService recoveryActionVerificationService) {
        this.diagnosisService = diagnosisService;
        this.recoveryLifecycleService = recoveryLifecycleService;
        this.recoveryActionExecutionService = recoveryActionExecutionService;
        this.recoveryActionVerificationService = recoveryActionVerificationService;
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

        RecoveryAction verifiedAction;
        try {
            verifiedAction = recoveryActionVerificationService.attemptVerification(executedAction.getId());
        } catch (UnsupportedOperationException verificationUnavailable) {
            return result(lifecycleResult, Optional.of(executedAction), RecoveryOutcome.VERIFICATION_UNAVAILABLE);
        }
        if (verifiedAction.getVerificationStatus() != VerificationStatus.VERIFIED) {
            return result(lifecycleResult, Optional.of(verifiedAction), RecoveryOutcome.VERIFICATION_FAILED);
        }

        RecoveryCase recoveredCase = recoveryLifecycleService.transitionCase(
                lifecycleResult.recoveryCase().getId(), RecoveryCaseStatus.RECOVERED);
        return new RecoveryOrchestrationResult(recoveredCase, lifecycleResult.decision(),
                lifecycleResult.policyDecision(), Optional.of(verifiedAction), RecoveryOutcome.RECOVERED);
    }

    private RecoveryOrchestrationResult result(RecoveryLifecycleResult lifecycleResult, Optional<RecoveryAction> action,
                                                RecoveryOutcome outcome) {
        return new RecoveryOrchestrationResult(lifecycleResult.recoveryCase(), lifecycleResult.decision(),
                lifecycleResult.policyDecision(), action, outcome);
    }
}
