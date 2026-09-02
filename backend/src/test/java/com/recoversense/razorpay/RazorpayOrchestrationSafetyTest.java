package com.recoversense.razorpay;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the existing policy gate still governs Razorpay execution: a
 * BLOCKED policy decision must mean the Razorpay client is never invoked at
 * all - this is the M1.11/M1.6 defense-in-depth already in place, exercised
 * here with a real Razorpay-backed executor plugged in, not a fake.
 */
@SpringBootTest
@Transactional
class RazorpayOrchestrationSafetyTest {

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

    private static final Instant STALE_ENOUGH = Instant.now().minus(Duration.ofMinutes(10));

    @Test
    void blockedPolicy_neverInvokesRazorpayClient() {
        Customer customer = new Customer("cust_razorpay_gate", "user+razorpaygate@example.com");
        customer.setStatus(CustomerStatus.ACTIVE);
        customerRepository.save(customer);
        Payment payment = new Payment("pay_razorpay_gate", customer, new BigDecimal("1000.00"), "INR", PaymentStatus.FAILED);
        payment.setSubscriptionStatus("active");
        payment.setFailureReason("mandate_revoked");
        payment.setFailedAt(STALE_ENOUGH);
        paymentRepository.save(payment);

        // Settlement deliberately not seeded -> UNKNOWN -> PolicyEngine fails
        // closed -> BLOCKED, regardless of the Razorpay wiring below.
        SimulatedSettlementVerifier simulator = new SimulatedSettlementVerifier();
        RecoveryPolicyService policyService = new RecoveryPolicyService(
                recoveryCaseRepository, recoveryActionRepository, auditEventRepository, simulator);
        RecoveryLifecycleService lifecycleService = new RecoveryLifecycleService(
                paymentRepository, recoveryCaseRepository, recoveryDecisionRepository, auditEventRepository,
                policyService, new RecoveryActionService(recoveryActionRepository, auditEventRepository));

        RazorpayPaymentLinkClient neverCalledClient = new RazorpayPaymentLinkClient() {
            @Override
            public RazorpayPaymentLink create(CreatePaymentLinkRequest request) {
                throw new AssertionError("Razorpay client must not be called when policy blocks the action");
            }

            @Override
            public RazorpayPaymentLink fetchById(String paymentLinkId) {
                throw new AssertionError("Razorpay client must not be called when policy blocks the action");
            }

            @Override
            public Optional<RazorpayPaymentLink> findByReferenceId(String referenceId) {
                throw new AssertionError("Razorpay client must not be called when policy blocks the action");
            }
        };
        RecoveryActionExecutionService executionService = new RecoveryActionExecutionService(
                recoveryActionRepository, auditEventRepository, new RazorpayRecoveryActionExecutor(neverCalledClient));
        RecoveryActionVerificationService verificationService = new RecoveryActionVerificationService(
                recoveryActionRepository, auditEventRepository, new RazorpayRecoveryActionVerifier(neverCalledClient));

        RecoveryOrchestrationService orchestration = new RecoveryOrchestrationService(
                diagnosisService, lifecycleService, executionService, verificationService,
                recoveryCaseRepository, recoveryActionRepository);

        RecoveryOrchestrationResult result = orchestration.recover(payment.getId());

        assertEquals(RecoveryOutcome.BLOCKED, result.outcome());
        assertTrue(result.action().isEmpty());
        assertEquals(0, recoveryActionRepository.count());
    }
}
