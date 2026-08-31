package com.recoversense.repository;

import com.recoversense.domain.ExecutionStatus;
import com.recoversense.domain.Payment;
import com.recoversense.domain.RecoveryAction;
import com.recoversense.domain.RecoveryDecision;
import com.recoversense.domain.VerificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecoveryActionRepository extends JpaRepository<RecoveryAction, Long> {

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
}
