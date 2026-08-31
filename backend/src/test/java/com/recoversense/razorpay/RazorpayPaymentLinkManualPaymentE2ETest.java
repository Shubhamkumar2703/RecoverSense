package com.recoversense.razorpay;

import com.recoversense.domain.Customer;
import com.recoversense.domain.CustomerStatus;
import com.recoversense.domain.ExecutionStatus;
import com.recoversense.domain.Payment;
import com.recoversense.domain.PaymentStatus;
import com.recoversense.domain.PolicyResult;
import com.recoversense.domain.RecoveryAction;
import com.recoversense.domain.RecoveryCase;
import com.recoversense.domain.RecoveryCaseStatus;
import com.recoversense.domain.RecoveryDecision;
import com.recoversense.domain.VerificationStatus;
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
import com.recoversense.service.RecoveryDiagnosisInput;
import com.recoversense.service.RecoveryLifecycleResult;
import com.recoversense.service.RecoveryLifecycleService;
import com.recoversense.service.RecoveryPolicyService;
import com.recoversense.settlement.SettlementState;
import com.recoversense.settlement.SimulatedSettlementVerifier;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1.18: REAL RAZORPAY + a genuinely paid Test Mode Payment Link, the positive
 * counterpart to {@link RazorpayPaymentLinkRealE2ETest}'s unpaid path. Drives
 * the same natural REPEATED_FAILURE -&gt; PAYMENT_LINK -&gt; ALLOWED path, but
 * calls the lifecycle/execution/verification services directly instead of
 * through RecoveryOrchestrationService, because verification is single-shot
 * (RecoveryActionVerificationService rejects re-verifying a non-UNVERIFIED
 * action) and a real human has to pay the link between execution and
 * verification.
 * <p>
 * Razorpay does not document any server-to-server way to complete a Test Mode
 * Payment Link payment (confirmed against official docs for M1.18) - every
 * path requires opening the hosted checkout. So this test prints the
 * short_url, polls the real API for a bounded window, and skips (not fails)
 * if nobody paid it in time.
 * <p>
 * Opt-in on top of the usual credential gate, since it blocks on a human:
 * requires RAZORPAY_MANUAL_PAYMENT_TEST=true in addition to RAZORPAY_KEY_ID.
 * To run: start this test, open the printed short_url, choose a test payment
 * method, and select Success before the poll window elapses.
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "RAZORPAY_KEY_ID", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAZORPAY_MANUAL_PAYMENT_TEST", matches = "true")
class RazorpayPaymentLinkManualPaymentE2ETest {

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
    private DiagnosisService realDiagnosisService;

    private static final Instant STALE_ENOUGH = Instant.now().minus(Duration.ofMinutes(10));
    private static final Duration POLL_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);

    private Payment seedFailedPayment(String suffix, String failureReason, BigDecimal amount) {
        Customer customer = new Customer("cust_" + suffix, "user+" + suffix + "@example.com");
        customer.setStatus(CustomerStatus.ACTIVE);
        customerRepository.save(customer);

        Payment payment = new Payment("pay_" + suffix, customer, amount, "INR", PaymentStatus.FAILED);
        payment.setSubscriptionStatus("active");
        payment.setFailureReason(failureReason);
        payment.setFailedAt(STALE_ENOUGH);
        return paymentRepository.save(payment);
    }

    @Test
    void realPaymentLinkPaid_isIndependentlyVerifiedAndRecoveryCompletes() {
        Payment payment = seedFailedPayment("real_manual_paid", "card_declined", new BigDecimal("10.00"));
        RecoveryCase recoveryCase = recoveryCaseRepository.save(new RecoveryCase(payment));
        for (int i = 0; i < 3; i++) {
            RecoveryDecision priorDecision = recoveryDecisionRepository.save(new RecoveryDecision(
                    recoveryCase, "TEMPORARY_FAILURE", new BigDecimal("0.4000"), "raw", "WAIT_RETRY"));
            RecoveryAction priorAction = new RecoveryAction(priorDecision, "WAIT_RETRY", PolicyResult.ALLOWED);
            priorAction.setExecutionStatus(ExecutionStatus.EXECUTED);
            recoveryActionRepository.save(priorAction);
        }

        HttpRazorpayPaymentLinkClient realClient = RealRazorpayTestSupport.realClient();
        SimulatedSettlementVerifier simulator = new SimulatedSettlementVerifier()
                .seedSettlementState("pay_real_manual_paid", SettlementState.NOT_SETTLED);
        RecoveryPolicyService policyService = new RecoveryPolicyService(
                recoveryCaseRepository, recoveryActionRepository, auditEventRepository, simulator);
        RecoveryLifecycleService lifecycleService = new RecoveryLifecycleService(
                paymentRepository, recoveryCaseRepository, recoveryDecisionRepository, auditEventRepository,
                policyService, new RecoveryActionService(recoveryActionRepository, auditEventRepository));
        RecoveryActionExecutionService executionService = new RecoveryActionExecutionService(
                recoveryActionRepository, auditEventRepository, new RazorpayRecoveryActionExecutor(realClient));
        RecoveryActionVerificationService verificationService = new RecoveryActionVerificationService(
                recoveryActionRepository, auditEventRepository, new RazorpayRecoveryActionVerifier(realClient));

        // Same natural diagnosis -> policy path as RazorpayPaymentLinkRealE2ETest,
        // driven in phases (not RecoveryOrchestrationService.recover) so a human
        // can pay the link between execution and the single-shot verification.
        RecoveryDiagnosisInput diagnosisInput = realDiagnosisService.diagnose(payment.getId());
        assertEquals("REPEATED_FAILURE", diagnosisInput.diagnosisCategory());
        assertEquals("PAYMENT_LINK", diagnosisInput.strategy());

        RecoveryLifecycleResult lifecycleResult = lifecycleService.processFailedPayment(payment.getId(), diagnosisInput);
        assertEquals(PolicyResult.ALLOWED, lifecycleResult.policyDecision().result(),
                "PAYMENT_LINK must not be blocked by WAIT_RETRY's retry limit: " + lifecycleResult.policyDecision().failedReasons());

        RecoveryAction pendingAction = lifecycleResult.action().orElseThrow();
        RecoveryAction executedAction = executionService.attemptExecution(pendingAction.getId());
        assertEquals(ExecutionStatus.EXECUTED, executedAction.getExecutionStatus());
        String paymentLinkId = executedAction.getExternalReference();
        assertNotNull(paymentLinkId, "a real Razorpay payment_link id must be persisted");

        RazorpayPaymentLink created = realClient.fetchById(paymentLinkId);
        System.out.println("M1.18 MANUAL TEST PAYMENT REQUIRED - open this Razorpay Test Mode link and complete a SUCCESS test payment within "
                + POLL_TIMEOUT + ": " + created.shortUrl());

        RazorpayPaymentLink paidLink = pollUntilPaid(realClient, paymentLinkId);
        Assumptions.assumeTrue(paidLink != null, "Payment Link " + paymentLinkId
                + " was not paid within " + POLL_TIMEOUT + " - skipping (manual test: re-run and pay the printed short_url in time)");

        // Independent re-fetch confirms provider state before trusting the verifier.
        assertEquals(RazorpayPaymentLinkStatus.PAID, paidLink.status());

        RecoveryAction verifiedAction = verificationService.attemptVerification(executedAction.getId());
        assertEquals(VerificationStatus.VERIFIED, verifiedAction.getVerificationStatus());

        RecoveryCase recovered = lifecycleService.transitionCase(recoveryCase.getId(), RecoveryCaseStatus.RECOVERED);
        assertEquals(RecoveryCaseStatus.RECOVERED, recovered.getStatus());

        List<String> eventTypes = auditEventRepository.findAll().stream()
                .filter(e -> e.getRecoveryCase().getId().equals(recoveryCase.getId()))
                .map(e -> e.getEventType())
                .toList();
        Set<String> eventTypeSet = eventTypes.stream().collect(Collectors.toSet());
        assertTrue(eventTypeSet.containsAll(Set.of("RECOVERY_DECISION_RECORDED", "POLICY_EVALUATED", "ACTION_CREATED",
                "ACTION_EXECUTION_ATTEMPTED", "ACTION_VERIFICATION_ATTEMPTED", "CASE_STATUS_CHANGED")),
                "audit trail must record the full successful recovery lifecycle: " + eventTypes);
    }

    /**
     * Bounded poll, not an indefinite wait: Test Mode payment status can lag
     * the hosted checkout's own success screen by a few seconds. Returns null
     * (never throws) on timeout so the caller can skip rather than fail.
     */
    private RazorpayPaymentLink pollUntilPaid(HttpRazorpayPaymentLinkClient client, String paymentLinkId) {
        Instant deadline = Instant.now().plus(POLL_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            RazorpayPaymentLink link = client.fetchById(paymentLinkId);
            if (link.status() == RazorpayPaymentLinkStatus.PAID) {
                return link;
            }
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }
}
