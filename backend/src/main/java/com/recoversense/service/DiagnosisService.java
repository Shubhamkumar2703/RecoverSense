package com.recoversense.service;

import com.recoversense.diagnosis.DiagnosisContext;
import com.recoversense.diagnosis.DiagnosisEngine;
import com.recoversense.diagnosis.DiagnosisResult;
import com.recoversense.domain.ExecutionStatus;
import com.recoversense.domain.Payment;
import com.recoversense.repository.PaymentRepository;
import com.recoversense.repository.RecoveryActionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gathers the persisted facts DiagnosisEngine needs (mirrors how
 * RecoveryPolicyService gathers facts for PolicyEngine) and produces a
 * RecoveryDiagnosisInput ready to hand to the existing, unmodified
 * RecoveryLifecycleService.processFailedPayment. Does not call
 * RecoveryLifecycleService itself - composing "diagnose then process" is
 * left to the caller, keeping this boundary's responsibility to diagnosis
 * only.
 */
@Service
public class DiagnosisService {

    private final PaymentRepository paymentRepository;
    private final RecoveryActionRepository recoveryActionRepository;
    private final DiagnosisEngine diagnosisEngine = new DiagnosisEngine();

    public DiagnosisService(PaymentRepository paymentRepository, RecoveryActionRepository recoveryActionRepository) {
        this.paymentRepository = paymentRepository;
        this.recoveryActionRepository = recoveryActionRepository;
    }

    @Transactional(readOnly = true)
    public RecoveryDiagnosisInput diagnose(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        int retryCount = (int) recoveryActionRepository
                .countByRecoveryDecision_RecoveryCase_PaymentAndExecutionStatus(payment, ExecutionStatus.EXECUTED);

        DiagnosisContext context = new DiagnosisContext(
                payment.getFailureReason(),
                payment.getSubscriptionStatus(),
                payment.getCustomer().getStatus(),
                retryCount);

        DiagnosisResult result = diagnosisEngine.diagnose(context);

        return new RecoveryDiagnosisInput(
                result.diagnosisCategory(),
                result.confidence(),
                result.reasoning(),
                result.strategy(),
                result.actionType());
    }
}
