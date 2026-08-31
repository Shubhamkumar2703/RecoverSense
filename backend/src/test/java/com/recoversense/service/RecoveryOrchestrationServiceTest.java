package com.recoversense.service;

import com.recoversense.domain.AuditEvent;
import com.recoversense.domain.Customer;
import com.recoversense.domain.CustomerStatus;
import com.recoversense.domain.ExecutionStatus;
import com.recoversense.domain.Payment;
import com.recoversense.domain.PaymentStatus;
import com.recoversense.domain.RecoveryAction;
import com.recoversense.domain.RecoveryCase;
import com.recoversense.domain.RecoveryCaseStatus;
import com.recoversense.domain.VerificationStatus;
import com.recoversense.repository.AuditEventRepository;
import com.recoversense.repository.CustomerRepository;
import com.recoversense.repository.PaymentRepository;
import com.recoversense.repository.RecoveryActionRepository;
import com.recoversense.repository.RecoveryCaseRepository;
import com.recoversense.repository.RecoveryDecisionRepository;
import com.recoversense.settlement.SettlementState;
import com.recoversense.settlement.SimulatedSettlementVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Proves the M1.11 orchestration wiring itself (call order, branch handling,
 * final outcome) - not the individual step logic, which is already covered
 * by RecoveryLifecycleServiceTest, RecoveryActionExecutionServiceTest,
 * RecoveryActionVerificationServiceTest and DiagnosisServiceTest.
 */
@SpringBootTest
@Transactional
class RecoveryOrchestrationServiceTest {

    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private RecoveryCaseRepository recoveryCaseRepository;
    @Autowired
    private RecoveryDecisionRepository recoveryDecisionRepository;
    @Autowired
    private RecoveryActionRepository recoveryActionRepository;
    @Autowired
    private AuditEventRepository auditEventRepository;
    @Autowired
    private DiagnosisService diagnosisService;
    @Autowired
    private RecoveryOrchestrationService realOrchestrationService;

    private static final Instant STALE_ENOUGH = Instant.now().minus(Duration.ofMinutes(10));

    private Payment seedFailedPayment(String externalPaymentId, String failureReason) {
        Customer customer = new Customer("cust_" + externalPaymentId, "user+" + externalPaymentId + "@example.com");
        customer.setStatus(CustomerStatus.ACTIVE);
        customerRepository.save(customer);

        Payment payment = new Payment(externalPaymentId, customer, new BigDecimal("1000"), "INR", PaymentStatus.FAILED);
        payment.setSubscriptionStatus("active");
        payment.setFailureReason(failureReason);
        payment.setFailedAt(STALE_ENOUGH);
        return paymentRepository.save(payment);
    }

    private RecoveryOrchestrationService orchestrationServiceWith(SettlementState settlementState, String externalPaymentId,
                                                                    RecoveryActionExecutor executor, RecoveryActionVerifier verifier) {
        SimulatedSettlementVerifier simulator = new SimulatedSettlementVerifier()
                .seedSettlementState(externalPaymentId, settlementState);
        RecoveryPolicyService policyService = new RecoveryPolicyService(
                recoveryCaseRepository, recoveryActionRepository, auditEventRepository, simulator);
        RecoveryLifecycleService lifecycleService = new RecoveryLifecycleService(
                paymentRepository, recoveryCaseRepository, recoveryDecisionRepository, auditEventRepository,
                policyService, new RecoveryActionService(recoveryActionRepository, auditEventRepository));
        RecoveryActionExecutionService executionService =
                new RecoveryActionExecutionService(recoveryActionRepository, auditEventRepository, executor);
        RecoveryActionVerificationService verificationService =
                new RecoveryActionVerificationService(recoveryActionRepository, auditEventRepository, verifier);
        return new RecoveryOrchestrationService(diagnosisService, lifecycleService, executionService, verificationService);
    }

    private RecoveryActionExecutor neverCalledExecutor() {
        return action -> fail("executor must not be called");
    }

    private RecoveryActionVerifier neverCalledVerifier() {
        return action -> fail("verifier must not be called");
    }

    @Test
    void happyPath_allowedExecutedVerified_reachesRecovered() {
        Payment payment = seedFailedPayment("pay_happy", "mandate_revoked");
        RecoveryOrchestrationService orchestration = orchestrationServiceWith(SettlementState.NOT_SETTLED, "pay_happy",
                a -> ExecutionStatus.EXECUTED, a -> VerificationStatus.VERIFIED);

        RecoveryOrchestrationResult result = orchestration.recover(payment.getId());

        assertEquals(RecoveryOutcome.RECOVERED, result.outcome());
        assertEquals(RecoveryCaseStatus.RECOVERED, result.recoveryCase().getStatus());
        RecoveryAction action = result.action().orElseThrow();
        assertEquals(ExecutionStatus.EXECUTED, action.getExecutionStatus());
        assertEquals(VerificationStatus.VERIFIED, action.getVerificationStatus());

        List<AuditEvent> events = auditEventRepository.findAll().stream()
                .filter(e -> e.getRecoveryCase().getId().equals(result.recoveryCase().getId()))
                .toList();
        Set<String> eventTypes = events.stream().map(AuditEvent::getEventType).collect(Collectors.toSet());
        assertTrue(eventTypes.containsAll(Set.of("RECOVERY_CASE_OPENED", "RECOVERY_DECISION_RECORDED",
                "POLICY_EVALUATED", "ACTION_CREATED", "ACTION_EXECUTION_ATTEMPTED",
                "ACTION_VERIFICATION_ATTEMPTED", "CASE_STATUS_CHANGED")));
    }

    @Test
    void policyRejection_neverExecutes() {
        // No settlement seeded: RecoveryPolicyService's real evaluate() also
        // fails closed on unknown settlement, so this blocks regardless -
        // the simulator here only needs to exist, not matter.
        Payment payment = seedFailedPayment("pay_blocked", "card_declined");
        RecoveryOrchestrationService orchestration = orchestrationServiceWith(SettlementState.SETTLED, "pay_blocked",
                neverCalledExecutor(), neverCalledVerifier());

        RecoveryOrchestrationResult result = orchestration.recover(payment.getId());

        assertEquals(RecoveryOutcome.BLOCKED, result.outcome());
        assertTrue(result.action().isEmpty());
        assertEquals(0, recoveryActionRepository.count());
    }

    @Test
    void executionFailure_doesNotClaimRecoveryOrVerify() {
        Payment payment = seedFailedPayment("pay_exec_fail", "mandate_revoked");
        RecoveryOrchestrationService orchestration = orchestrationServiceWith(SettlementState.NOT_SETTLED, "pay_exec_fail",
                a -> ExecutionStatus.FAILED, neverCalledVerifier());

        RecoveryOrchestrationResult result = orchestration.recover(payment.getId());

        assertEquals(RecoveryOutcome.EXECUTION_FAILED, result.outcome());
        assertEquals(RecoveryCaseStatus.OPEN, result.recoveryCase().getStatus());
        assertEquals(ExecutionStatus.FAILED, result.action().orElseThrow().getExecutionStatus());
        assertEquals(VerificationStatus.UNVERIFIED, result.action().orElseThrow().getVerificationStatus());
    }

    @Test
    void verificationFailure_doesNotMarkCaseRecovered() {
        Payment payment = seedFailedPayment("pay_verify_fail", "mandate_revoked");
        RecoveryOrchestrationService orchestration = orchestrationServiceWith(SettlementState.NOT_SETTLED, "pay_verify_fail",
                a -> ExecutionStatus.EXECUTED, a -> VerificationStatus.FAILED);

        RecoveryOrchestrationResult result = orchestration.recover(payment.getId());

        assertEquals(RecoveryOutcome.VERIFICATION_FAILED, result.outcome());
        assertEquals(RecoveryCaseStatus.OPEN, result.recoveryCase().getStatus());
        assertEquals(ExecutionStatus.EXECUTED, result.action().orElseThrow().getExecutionStatus());
        assertEquals(VerificationStatus.FAILED, result.action().orElseThrow().getVerificationStatus());
    }

    @Test
    void unimplementedProviders_reportUnavailableRatherThanFabricatingOutcome() {
        Payment payment = seedFailedPayment("pay_unavailable", "mandate_revoked");
        RecoveryOrchestrationService orchestration = orchestrationServiceWith(SettlementState.NOT_SETTLED, "pay_unavailable",
                a -> { throw new UnsupportedOperationException("no provider wired up"); }, neverCalledVerifier());

        RecoveryOrchestrationResult result = orchestration.recover(payment.getId());

        assertEquals(RecoveryOutcome.EXECUTION_UNAVAILABLE, result.outcome());
        assertEquals(RecoveryCaseStatus.OPEN, result.recoveryCase().getStatus());
        assertEquals(ExecutionStatus.PENDING, result.action().orElseThrow().getExecutionStatus());
    }

    @Test
    void diagnosisFailure_unknownPayment_neverProcessesOrExecutes() {
        assertThrows(PaymentNotFoundException.class, () -> realOrchestrationService.recover(-1L));
        assertEquals(0, recoveryCaseRepository.count());
    }

    /**
     * M1.22 Part 2.D: a second recover() call for a payment that already
     * reached RECOVERED must be rejected before touching the provider -
     * neverCalledExecutor()/neverCalledVerifier() would fail the test if
     * either were invoked, proving the guard runs ahead of any provider
     * mutation, not just ahead of a successful one.
     */
    @Test
    void secondRecoverCall_afterAlreadyRecovered_isRejectedWithoutTouchingProviderAgain() {
        Payment payment = seedFailedPayment("pay_sequential_duplicate", "mandate_revoked");
        RecoveryOrchestrationService firstAttemptOrchestration = orchestrationServiceWith(SettlementState.NOT_SETTLED,
                "pay_sequential_duplicate", a -> ExecutionStatus.EXECUTED, a -> VerificationStatus.VERIFIED);

        RecoveryOrchestrationResult firstResult = firstAttemptOrchestration.recover(payment.getId());
        assertEquals(RecoveryOutcome.RECOVERED, firstResult.outcome());

        RecoveryOrchestrationService secondAttemptOrchestration = orchestrationServiceWith(SettlementState.NOT_SETTLED,
                "pay_sequential_duplicate", neverCalledExecutor(), neverCalledVerifier());

        assertThrows(PaymentAlreadyRecoveredException.class, () -> secondAttemptOrchestration.recover(payment.getId()));

        List<RecoveryCase> cases = recoveryCaseRepository.findAll().stream()
                .filter(c -> c.getPayment().getId().equals(payment.getId()))
                .toList();
        assertEquals(1, cases.size(), "no second RecoveryCase must be created");
        assertEquals(1, recoveryDecisionRepository.count(), "no second RecoveryDecision must be created");
        assertEquals(1, recoveryActionRepository.count(), "no second RecoveryAction must be created");
    }
}
