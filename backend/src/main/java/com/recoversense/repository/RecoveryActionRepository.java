package com.recoversense.repository;

import com.recoversense.domain.ExecutionStatus;
import com.recoversense.domain.Payment;
import com.recoversense.domain.RecoveryAction;
import com.recoversense.domain.RecoveryCase;
import com.recoversense.domain.RecoveryDecision;
import com.recoversense.domain.VerificationStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RecoveryActionRepository extends JpaRepository<RecoveryAction, Long> {

    /**
     * M1.25: the action a case's verify step should act on. A case is
     * expected to have at most one action across RecoverSense's current
     * lifecycle (one decision per recover() call before a case reaches a
     * terminal status) - ordering by id desc is a defensive tie-breaker, not
     * evidence multiple actions are a supported/expected state.
     * <p>
     * M1.28: eagerly fetches recoveryDecision. RecoveryOrchestrationService#verify
     * is deliberately not @Transactional (each phase owns its own transaction -
     * see its class Javadoc), so the two branches that respond using this exact
     * action (the idempotent already-VERIFIED short-circuit, and the
     * VERIFICATION_UNAVAILABLE catch) would otherwise hand RecoveryResponse a
     * RecoveryDecision proxy with no session left to initialize it -
     * LazyInitializationException on RecoveryResponse.fromVerification's
     * decision.getDiagnosisCategory(). A targeted join here (not a global
     * FetchType.EAGER on the entity) fixes exactly that.
     */
    @EntityGraph(attributePaths = "recoveryDecision")
    Optional<RecoveryAction> findTopByRecoveryCaseOrderByIdDesc(RecoveryCase recoveryCase);

    long countByRecoveryDecision_RecoveryCase_PaymentAndExecutionStatus(Payment payment, ExecutionStatus executionStatus);

    /**
     * Scoped to a single action type - see PolicyEngine's retry_limit_not_exceeded
     * check (P01) as used by RecoveryPolicyService: the limit constrains how
     * many times the SAME proposed action has already been executed for this
     * payment, not how many recovery attempts of any kind have happened
     * (that broader count is DiagnosisService's own, separate, unscoped use
     * of countByRecoveryDecision_RecoveryCase_PaymentAndExecutionStatus above,
     * for deciding when to escalate strategy - see M1.17).
     */
    long countByRecoveryDecision_RecoveryCase_PaymentAndExecutionStatusAndActionType(
            Payment payment, ExecutionStatus executionStatus, String actionType);

    boolean existsByRecoveryDecision_RecoveryCase_PaymentAndExecutionStatus(Payment payment, ExecutionStatus executionStatus);

    List<RecoveryAction> findByRecoveryDecisionAndActionType(RecoveryDecision recoveryDecision, String actionType);

    long countByVerificationStatus(VerificationStatus verificationStatus);

    long countByExecutionStatusAndVerificationStatus(ExecutionStatus executionStatus, VerificationStatus verificationStatus);

    long countByExecutionStatus(ExecutionStatus executionStatus);
}
