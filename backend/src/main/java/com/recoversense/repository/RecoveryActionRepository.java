package com.recoversense.repository;

import com.recoversense.domain.ExecutionStatus;
import com.recoversense.domain.Payment;
import com.recoversense.domain.RecoveryAction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecoveryActionRepository extends JpaRepository<RecoveryAction, Long> {

    long countByRecoveryDecision_RecoveryCase_PaymentAndExecutionStatus(Payment payment, ExecutionStatus executionStatus);

    boolean existsByRecoveryDecision_RecoveryCase_PaymentAndExecutionStatus(Payment payment, ExecutionStatus executionStatus);
}
