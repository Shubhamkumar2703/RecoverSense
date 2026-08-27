package com.recoversense.repository;

import com.recoversense.domain.ExecutionStatus;
import com.recoversense.domain.Payment;
import com.recoversense.domain.RecoveryAction;
import com.recoversense.domain.RecoveryDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecoveryActionRepository extends JpaRepository<RecoveryAction, Long> {

    long countByRecoveryDecision_RecoveryCase_PaymentAndExecutionStatus(Payment payment, ExecutionStatus executionStatus);

    boolean existsByRecoveryDecision_RecoveryCase_PaymentAndExecutionStatus(Payment payment, ExecutionStatus executionStatus);

    List<RecoveryAction> findByRecoveryDecisionAndActionType(RecoveryDecision recoveryDecision, String actionType);
}
