package com.recoversense.service;

import com.recoversense.domain.AuditEvent;
import com.recoversense.domain.Customer;
import com.recoversense.domain.ExecutionStatus;
import com.recoversense.domain.Payment;
import com.recoversense.domain.RecoveryCase;
import com.recoversense.policy.PolicyContext;
import com.recoversense.policy.PolicyDecision;
import com.recoversense.policy.PolicyEngine;
import com.recoversense.repository.AuditEventRepository;
import com.recoversense.repository.RecoveryActionRepository;
import com.recoversense.repository.RecoveryCaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

/**
 * Orchestrates a single policy evaluation: loads persisted state, derives
 * the facts PolicyEngine needs that aren't columns on any entity, calls the
 * pure PolicyEngine, and persists the outcome as an AuditEvent. Contains no
 * policy logic of its own - PolicyEngine remains the single source of truth
 * for the 7 checks - and never calls Razorpay, an LLM, or an execution
 * service.
 */
@Service
public class RecoveryPolicyService {

    private static final String EVENT_TYPE = "POLICY_EVALUATED";

    private final RecoveryCaseRepository recoveryCaseRepository;
    private final RecoveryActionRepository recoveryActionRepository;
    private final AuditEventRepository auditEventRepository;
    private final PolicyEngine policyEngine = new PolicyEngine();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public RecoveryPolicyService(RecoveryCaseRepository recoveryCaseRepository,
                                  RecoveryActionRepository recoveryActionRepository,
                                  AuditEventRepository auditEventRepository) {
        this.recoveryCaseRepository = recoveryCaseRepository;
        this.recoveryActionRepository = recoveryActionRepository;
        this.auditEventRepository = auditEventRepository;
    }

    @Transactional
    public PolicyDecision evaluate(Long recoveryCaseId) {
        RecoveryCase recoveryCase = recoveryCaseRepository.findById(recoveryCaseId)
                .orElseThrow(() -> new RecoveryCaseNotFoundException(recoveryCaseId));
        Payment payment = recoveryCase.getPayment();
        Customer customer = payment.getCustomer();

        int retryCount = (int) recoveryActionRepository
                .countByRecoveryDecision_RecoveryCase_PaymentAndExecutionStatus(payment, ExecutionStatus.EXECUTED);

        boolean pendingReacquisitionExists = recoveryActionRepository
                .existsByRecoveryDecision_RecoveryCase_PaymentAndExecutionStatus(payment, ExecutionStatus.PENDING);

        Instant evaluatedAt = Instant.now();

        // alreadySettledElsewhere is genuinely unknown until an external settlement
        // source (Razorpay) is integrated. Left null on purpose: PolicyEngine already
        // fails closed on unknown required state, so this must not be guessed at here.
        PolicyContext context = new PolicyContext(
                customer,
                payment,
                retryCount,
                pendingReacquisitionExists,
                null,
                evaluatedAt
        );

        PolicyDecision decision = policyEngine.evaluate(context);

        auditEventRepository.save(new AuditEvent(recoveryCase, EVENT_TYPE, toPayloadJson(decision, evaluatedAt)));

        return decision;
    }

    private String toPayloadJson(PolicyDecision decision, Instant evaluatedAt) {
        List<PolicyAuditPayload.CheckEntry> entries = decision.checks().stream()
                .map(check -> new PolicyAuditPayload.CheckEntry(check.checkName(), check.passed(), check.reason()))
                .toList();
        PolicyAuditPayload payload = new PolicyAuditPayload(decision.result().name(), evaluatedAt, entries);
        return jsonMapper.writeValueAsString(payload);
    }
}
