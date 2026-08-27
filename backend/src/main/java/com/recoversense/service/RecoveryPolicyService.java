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
import com.recoversense.settlement.SettlementState;
import com.recoversense.settlement.SettlementVerifier;
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
 * service directly; external settlement state is obtained only through the
 * SettlementVerifier abstraction, never a concrete HTTP client.
 */
@Service
public class RecoveryPolicyService {

    private static final String EVENT_TYPE = "POLICY_EVALUATED";

    private final RecoveryCaseRepository recoveryCaseRepository;
    private final RecoveryActionRepository recoveryActionRepository;
    private final AuditEventRepository auditEventRepository;
    private final SettlementVerifier settlementVerifier;
    private final PolicyEngine policyEngine = new PolicyEngine();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public RecoveryPolicyService(RecoveryCaseRepository recoveryCaseRepository,
                                  RecoveryActionRepository recoveryActionRepository,
                                  AuditEventRepository auditEventRepository,
                                  SettlementVerifier settlementVerifier) {
        this.recoveryCaseRepository = recoveryCaseRepository;
        this.recoveryActionRepository = recoveryActionRepository;
        this.auditEventRepository = auditEventRepository;
        this.settlementVerifier = settlementVerifier;
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

        // Settlement state comes only from the SettlementVerifier port, never
        // guessed here. SETTLED -> true, NOT_SETTLED -> false, UNKNOWN -> null;
        // PolicyEngine already fails closed on null. Today's only wired
        // implementation (UnavailableSettlementVerifier) makes no network call
        // and always answers UNKNOWN, so this remains null in practice until a
        // real settlement source exists - see M1.9 report for the transaction
        // implication once a real (network-calling) implementation is added.
        //
        // A caught exception here is treated exactly like UNKNOWN, never as
        // NOT_SETTLED and never allowed to abort evaluation: a misbehaving
        // future implementation that throws instead of returning UNKNOWN must
        // still fail closed, not skip the check or crash the whole evaluation.
        SettlementState settlementState;
        try {
            settlementState = settlementVerifier.checkSettlement(payment.getExternalPaymentId());
        } catch (RuntimeException settlementCheckFailed) {
            settlementState = SettlementState.UNKNOWN;
        }
        Boolean alreadySettledElsewhere = switch (settlementState) {
            case SETTLED -> Boolean.TRUE;
            case NOT_SETTLED -> Boolean.FALSE;
            case UNKNOWN -> null;
        };

        PolicyContext context = new PolicyContext(
                customer,
                payment,
                retryCount,
                pendingReacquisitionExists,
                alreadySettledElsewhere,
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
