package com.recoversense.service;

import com.recoversense.diagnosis.DiagnosisContext;
import com.recoversense.diagnosis.DiagnosisProvider;
import com.recoversense.diagnosis.RecoveryDiagnosis;
import com.recoversense.diagnosis.StrategyRouter;
import com.recoversense.domain.ExecutionStatus;
import com.recoversense.domain.Payment;
import com.recoversense.repository.PaymentRepository;
import com.recoversense.repository.RecoveryActionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gathers the persisted facts diagnosis needs (mirrors how
 * RecoveryPolicyService gathers facts for PolicyEngine), delegates
 * classification to the injected {@link DiagnosisProvider} (Claude when
 * configured, otherwise the always-available deterministic simulated
 * provider - see DiagnosisProvider's implementations), derives the strategy
 * deterministically via {@link StrategyRouter}, and produces a
 * RecoveryDiagnosisInput ready to hand to the existing, unmodified
 * RecoveryLifecycleService.processFailedPayment. Does not call
 * RecoveryLifecycleService itself - composing "diagnose then process" is
 * left to the caller, keeping this boundary's responsibility to diagnosis
 * only.
 * <p>
 * The provider's classification is never treated as authoritative beyond
 * that: it never supplies the strategy directly (StrategyRouter always
 * derives it fresh from the validated failureType), and any
 * DiagnosisUnavailableException it throws propagates uncaught - exactly like
 * PaymentNotFoundException already does - so no RecoveryCase/RecoveryAction
 * is ever created from a failed or untrusted diagnosis.
 */
@Service
public class DiagnosisService {

    private final PaymentRepository paymentRepository;
    private final RecoveryActionRepository recoveryActionRepository;
    private final DiagnosisProvider diagnosisProvider;
    private final StrategyRouter strategyRouter = new StrategyRouter();

    public DiagnosisService(PaymentRepository paymentRepository, RecoveryActionRepository recoveryActionRepository,
                             DiagnosisProvider diagnosisProvider) {
        this.paymentRepository = paymentRepository;
        this.recoveryActionRepository = recoveryActionRepository;
        this.diagnosisProvider = diagnosisProvider;
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

        RecoveryDiagnosis diagnosis = diagnosisProvider.diagnose(context);
        String strategy = strategyRouter.route(diagnosis.failureType());

        return new RecoveryDiagnosisInput(
                diagnosis.failureType(),
                diagnosis.confidence(),
                "[" + diagnosis.source() + "] " + diagnosis.reasoning(),
                strategy,
                strategy);
    }
}
