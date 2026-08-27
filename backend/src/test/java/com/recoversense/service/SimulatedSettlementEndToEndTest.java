package com.recoversense.service;

import com.recoversense.domain.Customer;
import com.recoversense.domain.CustomerStatus;
import com.recoversense.domain.ExecutionStatus;
import com.recoversense.domain.Payment;
import com.recoversense.domain.PaymentStatus;
import com.recoversense.domain.PolicyResult;
import com.recoversense.domain.RecoveryAction;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the full Payment -> diagnosis -> RecoveryLifecycleService pipeline
 * can reach ALLOWED end to end using only the SIMULATED settlement verifier
 * - never real Razorpay, never a weakened PolicyEngine. RecoveryPolicyService
 * and RecoveryLifecycleService are constructed directly here (not the
 * @Autowired Spring-wired instances, which always resolve SettlementVerifier
 * to the fail-closed UnavailableSettlementVerifier bean) purely to swap in
 * the simulator for this test.
 */
@SpringBootTest
@Transactional
class SimulatedSettlementEndToEndTest {

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
    private RecoveryActionService recoveryActionService;

    private static final Instant STALE_ENOUGH = Instant.now().minus(Duration.ofMinutes(10));

    private Payment seedFailedPayment(String externalPaymentId) {
        Customer customer = new Customer("cust_" + externalPaymentId, "user+" + externalPaymentId + "@example.com");
        customer.setStatus(CustomerStatus.ACTIVE);
        customerRepository.save(customer);

        Payment payment = new Payment(externalPaymentId, customer, new BigDecimal("1000"), "INR", PaymentStatus.FAILED);
        payment.setSubscriptionStatus("active");
        payment.setFailedAt(STALE_ENOUGH);
        return paymentRepository.save(payment);
    }

    private RecoveryDiagnosisInput validDiagnosisInput() {
        return new RecoveryDiagnosisInput("MANDATE_INVALID", new BigDecimal("0.9000"),
                "raw diagnosis text", "REACQUIRE_MANDATE", "REACQUIRE_MANDATE");
    }

    private RecoveryLifecycleService lifecycleServiceWithSimulator(SimulatedSettlementVerifier simulator) {
        RecoveryPolicyService policyService = new RecoveryPolicyService(
                recoveryCaseRepository, recoveryActionRepository, auditEventRepository, simulator);
        return new RecoveryLifecycleService(
                paymentRepository, recoveryCaseRepository, recoveryDecisionRepository,
                auditEventRepository, policyService, recoveryActionService);
    }

    @Test
    void notSettled_reachesAllowAndCreatesExactlyOnePendingUnverifiedAction() {
        Payment payment = seedFailedPayment("pay_allow_e2e");
        SimulatedSettlementVerifier simulator = new SimulatedSettlementVerifier()
                .seedSettlementState("pay_allow_e2e", SettlementState.NOT_SETTLED);

        RecoveryLifecycleResult result = lifecycleServiceWithSimulator(simulator)
                .processFailedPayment(payment.getId(), validDiagnosisInput());

        assertEquals(PolicyResult.ALLOWED, result.policyDecision().result());
        assertTrue(result.action().isPresent());
        RecoveryAction action = result.action().orElseThrow();
        assertEquals(PolicyResult.ALLOWED, action.getPolicyResult());
        assertEquals(ExecutionStatus.PENDING, action.getExecutionStatus());
        assertEquals(VerificationStatus.UNVERIFIED, action.getVerificationStatus());
        assertNull(action.getExecutedAt());
        assertNull(action.getVerifiedAt());

        List<RecoveryAction> actionsForDecision = recoveryActionRepository
                .findByRecoveryDecisionAndActionType(result.decision(), "REACQUIRE_MANDATE");
        assertEquals(1, actionsForDecision.size());
    }

    @Test
    void settled_blocksRecovery_noActionCreated() {
        Payment payment = seedFailedPayment("pay_settled_e2e");
        SimulatedSettlementVerifier simulator = new SimulatedSettlementVerifier()
                .seedSettlementState("pay_settled_e2e", SettlementState.SETTLED);

        RecoveryLifecycleResult result = lifecycleServiceWithSimulator(simulator)
                .processFailedPayment(payment.getId(), validDiagnosisInput());

        assertEquals(PolicyResult.BLOCKED, result.policyDecision().result());
        assertTrue(result.action().isEmpty());
    }

    @Test
    void unknown_blocksRecovery_noActionCreated() {
        Payment payment = seedFailedPayment("pay_unknown_e2e");
        // Deliberately not seeded - simulator must answer UNKNOWN for it.
        SimulatedSettlementVerifier simulator = new SimulatedSettlementVerifier();

        RecoveryLifecycleResult result = lifecycleServiceWithSimulator(simulator)
                .processFailedPayment(payment.getId(), validDiagnosisInput());

        assertEquals(PolicyResult.BLOCKED, result.policyDecision().result());
        assertTrue(result.action().isEmpty());
    }

    @Test
    void allowPath_neverExecutesOrVerifies() {
        Payment payment = seedFailedPayment("pay_no_exec_e2e");
        SimulatedSettlementVerifier simulator = new SimulatedSettlementVerifier()
                .seedSettlementState("pay_no_exec_e2e", SettlementState.NOT_SETTLED);

        RecoveryLifecycleResult result = lifecycleServiceWithSimulator(simulator)
                .processFailedPayment(payment.getId(), validDiagnosisInput());

        RecoveryAction action = result.action().orElseThrow();
        assertEquals(ExecutionStatus.PENDING, action.getExecutionStatus());
        assertEquals(VerificationStatus.UNVERIFIED, action.getVerificationStatus());

        List<RecoveryAction> actionsForDecision = recoveryActionRepository
                .findByRecoveryDecisionAndActionType(result.decision(), "REACQUIRE_MANDATE");
        assertEquals(1, actionsForDecision.size());
        assertEquals(ExecutionStatus.PENDING, actionsForDecision.get(0).getExecutionStatus());
        assertEquals(VerificationStatus.UNVERIFIED, actionsForDecision.get(0).getVerificationStatus());
    }
}
