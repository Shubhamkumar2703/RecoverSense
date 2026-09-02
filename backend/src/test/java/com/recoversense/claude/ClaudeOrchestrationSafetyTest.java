package com.recoversense.claude;

import com.recoversense.diagnosis.DiagnosisProvider;
import com.recoversense.diagnosis.DiagnosisSource;
import com.recoversense.diagnosis.DiagnosisUnavailableException;
import com.recoversense.diagnosis.RecoveryDiagnosis;
import com.recoversense.domain.Customer;
import com.recoversense.domain.CustomerStatus;
import com.recoversense.domain.Payment;
import com.recoversense.domain.PaymentStatus;
import com.recoversense.repository.AuditEventRepository;
import com.recoversense.repository.CustomerRepository;
import com.recoversense.repository.PaymentRepository;
import com.recoversense.repository.RecoveryActionRepository;
import com.recoversense.repository.RecoveryCaseRepository;
import com.recoversense.repository.RecoveryDecisionRepository;
import com.recoversense.service.DiagnosisService;
import com.recoversense.service.RecoveryActionExecutionService;
import com.recoversense.service.RecoveryActionService;
import com.recoversense.service.RecoveryActionVerificationService;
import com.recoversense.service.RecoveryLifecycleService;
import com.recoversense.service.RecoveryOrchestrationResult;
import com.recoversense.service.RecoveryOrchestrationService;
import com.recoversense.service.RecoveryOutcome;
import com.recoversense.service.RecoveryPolicyService;
import com.recoversense.settlement.SimulatedSettlementVerifier;
import com.recoversense.settlement.SettlementState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1.15 safety proof at the full orchestration level (compare
 * RazorpayOrchestrationSafetyTest, which proves the same shape of guarantee
 * for the execution/policy boundary): a diagnosis provider - Claude included
 * - can never reach execution on its own, whether it fails outright or
 * produces a diagnosis that policy then blocks.
 */
@SpringBootTest
@Transactional
class ClaudeOrchestrationSafetyTest {

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

    private Payment seedPayment(String suffix) {
        Customer customer = new Customer("cust_" + suffix, "user+" + suffix + "@example.com");
        customer.setStatus(CustomerStatus.ACTIVE);
        customerRepository.save(customer);
        Payment payment = new Payment("pay_" + suffix, customer, new BigDecimal("1000"), "INR", PaymentStatus.FAILED);
        payment.setSubscriptionStatus("active");
        payment.setFailureReason("mandate_revoked");
        return paymentRepository.save(payment);
    }

    private RecoveryOrchestrationService orchestrationWith(DiagnosisProvider diagnosisProvider, SettlementState settlementState,
                                                             String externalPaymentId) {
        DiagnosisService diagnosisService = new DiagnosisService(paymentRepository, recoveryActionRepository, diagnosisProvider);
        SimulatedSettlementVerifier simulator = new SimulatedSettlementVerifier()
                .seedSettlementState(externalPaymentId, settlementState);
        RecoveryPolicyService policyService = new RecoveryPolicyService(
                recoveryCaseRepository, recoveryActionRepository, auditEventRepository, simulator);
        RecoveryLifecycleService lifecycleService = new RecoveryLifecycleService(
                paymentRepository, recoveryCaseRepository, recoveryDecisionRepository, auditEventRepository,
                policyService, new RecoveryActionService(recoveryActionRepository, auditEventRepository));
        RecoveryActionExecutionService executionService = new RecoveryActionExecutionService(
                recoveryActionRepository, auditEventRepository,
                action -> { throw new AssertionError("executor must not be called"); });
        RecoveryActionVerificationService verificationService = new RecoveryActionVerificationService(
                recoveryActionRepository, auditEventRepository,
                action -> { throw new AssertionError("verifier must not be called"); });
        return new RecoveryOrchestrationService(diagnosisService, lifecycleService, executionService, verificationService,
                recoveryCaseRepository, recoveryActionRepository);
    }

    @Test
    void diagnosisProviderFailure_neverCreatesACaseOrReachesExecution() {
        Payment payment = seedPayment("claude_unavailable");
        DiagnosisProvider failingProvider = context -> {
            throw new DiagnosisUnavailableException("simulated Claude outage");
        };
        RecoveryOrchestrationService orchestration =
                orchestrationWith(failingProvider, SettlementState.NOT_SETTLED, "pay_claude_unavailable");

        assertThrows(DiagnosisUnavailableException.class, () -> orchestration.recover(payment.getId()));

        assertEquals(0, recoveryCaseRepository.count());
        assertEquals(0, recoveryActionRepository.count());
    }

    @Test
    void claudeDiagnosis_stillGovernedByPolicy_blockedNeverReachesExecution() {
        Payment payment = seedPayment("claude_blocked");
        DiagnosisProvider stubClaude = context -> new RecoveryDiagnosis(
                "MANDATE_INVALID", new BigDecimal("0.9400"), "mandate revoked while subscription active",
                DiagnosisSource.CLAUDE);
        // Settlement deliberately not seeded for this payment -> UNKNOWN ->
        // PolicyEngine fails closed -> BLOCKED, regardless of Claude's
        // (valid, confident) diagnosis.
        RecoveryOrchestrationService orchestration =
                orchestrationWith(stubClaude, SettlementState.SETTLED, "pay_claude_blocked");

        RecoveryOrchestrationResult result = orchestration.recover(payment.getId());

        assertEquals(RecoveryOutcome.BLOCKED, result.outcome());
        assertTrue(result.action().isEmpty());
        assertEquals(0, recoveryActionRepository.count());
    }
}
