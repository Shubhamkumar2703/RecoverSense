package com.recoversense.service;

import com.recoversense.domain.AuditEvent;
import com.recoversense.domain.Payment;
import com.recoversense.domain.PaymentStatus;
import com.recoversense.domain.RecoveryCase;
import com.recoversense.domain.RecoveryCaseStatus;
import com.recoversense.domain.RecoveryDecision;
import com.recoversense.policy.PolicyDecision;
import com.recoversense.repository.AuditEventRepository;
import com.recoversense.repository.PaymentRepository;
import com.recoversense.repository.RecoveryCaseRepository;
import com.recoversense.repository.RecoveryDecisionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrates the failed-payment recovery lifecycle: Payment -> RecoveryCase
 * -> RecoveryDecision -> policy evaluation -> policy audit result.
 * <p>
 * Does not create or execute a RecoveryAction, call any external provider,
 * or run an LLM - those are later concerns. Policy evaluation and its audit
 * trail are delegated entirely to {@link RecoveryPolicyService}; the 7
 * checks are never re-implemented here.
 */
@Service
public class RecoveryLifecycleService {

    private static final Map<RecoveryCaseStatus, Set<RecoveryCaseStatus>> ALLOWED_TRANSITIONS = new EnumMap<>(RecoveryCaseStatus.class);

    static {
        ALLOWED_TRANSITIONS.put(RecoveryCaseStatus.OPEN,
                EnumSet.of(RecoveryCaseStatus.RECOVERED, RecoveryCaseStatus.CLOSED, RecoveryCaseStatus.FAILED));
        // RECOVERED, CLOSED, FAILED are terminal: no entry means no outgoing transition.
    }

    private final PaymentRepository paymentRepository;
    private final RecoveryCaseRepository recoveryCaseRepository;
    private final RecoveryDecisionRepository recoveryDecisionRepository;
    private final AuditEventRepository auditEventRepository;
    private final RecoveryPolicyService recoveryPolicyService;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public RecoveryLifecycleService(PaymentRepository paymentRepository,
                                     RecoveryCaseRepository recoveryCaseRepository,
                                     RecoveryDecisionRepository recoveryDecisionRepository,
                                     AuditEventRepository auditEventRepository,
                                     RecoveryPolicyService recoveryPolicyService) {
        this.paymentRepository = paymentRepository;
        this.recoveryCaseRepository = recoveryCaseRepository;
        this.recoveryDecisionRepository = recoveryDecisionRepository;
        this.auditEventRepository = auditEventRepository;
        this.recoveryPolicyService = recoveryPolicyService;
    }

    @Transactional
    public RecoveryLifecycleResult processFailedPayment(Long paymentId, RecoveryDiagnosisInput diagnosisInput) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        if (payment.getStatus() != PaymentStatus.FAILED) {
            throw new InvalidPaymentStateException(paymentId, payment.getStatus());
        }

        RecoveryCase recoveryCase = findOrCreateOpenCase(payment);

        RecoveryDecision decision = recoveryDecisionRepository.save(new RecoveryDecision(
                recoveryCase,
                diagnosisInput.diagnosisCategory(),
                diagnosisInput.diagnosisConfidence(),
                diagnosisInput.diagnosisRaw(),
                diagnosisInput.strategy()));

        auditEventRepository.save(new AuditEvent(recoveryCase, "RECOVERY_DECISION_RECORDED",
                toJson(new DecisionRecordedPayload(decision.getId(), diagnosisInput.diagnosisCategory(),
                        diagnosisInput.diagnosisConfidence(), diagnosisInput.strategy(), decision.getDecidedAt()))));

        PolicyDecision policyDecision = recoveryPolicyService.evaluate(recoveryCase.getId());

        return new RecoveryLifecycleResult(recoveryCase, decision, policyDecision);
    }

    @Transactional
    public RecoveryCase transitionCase(Long recoveryCaseId, RecoveryCaseStatus targetStatus) {
        RecoveryCase recoveryCase = recoveryCaseRepository.findById(recoveryCaseId)
                .orElseThrow(() -> new RecoveryCaseNotFoundException(recoveryCaseId));
        RecoveryCaseStatus currentStatus = recoveryCase.getStatus();

        if (!isAllowedTransition(currentStatus, targetStatus)) {
            throw new InvalidCaseTransitionException(currentStatus, targetStatus);
        }

        recoveryCase.setStatus(targetStatus);
        recoveryCase.setClosedAt(Instant.now());

        auditEventRepository.save(new AuditEvent(recoveryCase, "CASE_STATUS_CHANGED",
                toJson(new CaseStatusChangedPayload(currentStatus.name(), targetStatus.name(), Instant.now()))));

        return recoveryCase;
    }

    private boolean isAllowedTransition(RecoveryCaseStatus from, RecoveryCaseStatus to) {
        Set<RecoveryCaseStatus> allowed = ALLOWED_TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    /**
     * Finds the payment's existing OPEN case, or creates one. The lookup is
     * only a fast-path optimization: the real protection against a duplicate
     * OPEN case is the DB's partial unique index
     * (uq_recovery_cases_open_payment). If two callers race, one insert wins
     * and the other throws org.springframework.dao.DataIntegrityViolationException,
     * which propagates out of processFailedPayment and rolls back this whole
     * call - nothing partially created. The loser is expected to retry the
     * full call; the retry's fast-path lookup then finds the case the winner
     * committed.
     */
    private RecoveryCase findOrCreateOpenCase(Payment payment) {
        return recoveryCaseRepository.findByPaymentAndStatus(payment, RecoveryCaseStatus.OPEN)
                .orElseGet(() -> createOpenCase(payment));
    }

    private RecoveryCase createOpenCase(Payment payment) {
        RecoveryCase created = recoveryCaseRepository.save(new RecoveryCase(payment));
        auditEventRepository.save(new AuditEvent(created, "RECOVERY_CASE_OPENED",
                toJson(new CaseOpenedPayload(payment.getId(), created.getId(), created.getOpenedAt()))));
        return created;
    }

    private String toJson(Object payload) {
        return jsonMapper.writeValueAsString(payload);
    }

    private record CaseOpenedPayload(Long paymentId, Long recoveryCaseId, Instant openedAt) {
    }

    private record DecisionRecordedPayload(Long recoveryDecisionId, String diagnosisCategory,
                                            BigDecimal diagnosisConfidence, String strategy, Instant decidedAt) {
    }

    private record CaseStatusChangedPayload(String fromStatus, String toStatus, Instant transitionedAt) {
    }
}
