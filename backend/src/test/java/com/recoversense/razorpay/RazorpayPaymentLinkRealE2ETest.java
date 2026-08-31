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
import com.recoversense.service.RecoveryLifecycleService;
import com.recoversense.service.RecoveryOrchestrationResult;
import com.recoversense.service.RecoveryOrchestrationService;
import com.recoversense.service.RecoveryOutcome;
import com.recoversense.service.RecoveryPolicyService;
import com.recoversense.settlement.SettlementState;
import com.recoversense.settlement.SimulatedSettlementVerifier;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1.16/M1.17 E2E: REAL RAZORPAY + MOCK CLAUDE + real RecoverSense
 * orchestration, persistence, policy, execution/verification, and audit.
 * Never mocks Razorpay - the executor/verifier here are real
 * RazorpayRecoveryActionExecutor/Verifier wired to RealRazorpayTestSupport's
 * real HttpRazorpayPaymentLinkClient. Diagnosis is the real, unmodified
 * SimulatedDiagnosisProvider ({@link #realDiagnosisService}, autowired -
 * DiagnosisEngine's actual keyword/retry-count classification) - this is
 * "Claude mocked" via the existing M1.15 default, not a hand-set stub.
 * <p>
 * M1.16 originally found that the natural REPEATED_FAILURE -&gt; PAYMENT_LINK
 * path could never pass policy (retry_limit_not_exceeded read the same
 * unscoped retry count DiagnosisEngine used to escalate strategy) and had to
 * work around it with a stand-in DiagnosisProvider. M1.17 fixed the root
 * cause (RecoveryPolicyService now scopes retryCount to the proposed action
 * type - see its javadoc), so this test now drives the real, natural
 * diagnosis path end to end with no stub of any kind.
 * <p>
 * Skipped (not failed) without RAZORPAY_KEY_ID.
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "RAZORPAY_KEY_ID", matches = ".+")
class RazorpayPaymentLinkRealE2ETest {

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

    private RecoveryOrchestrationService realRazorpayOrchestration(String externalPaymentId) {
        SimulatedSettlementVerifier simulator = new SimulatedSettlementVerifier()
                .seedSettlementState(externalPaymentId, SettlementState.NOT_SETTLED);
        RecoveryPolicyService policyService = new RecoveryPolicyService(
                recoveryCaseRepository, recoveryActionRepository, auditEventRepository, simulator);
        RecoveryLifecycleService lifecycleService = new RecoveryLifecycleService(
                paymentRepository, recoveryCaseRepository, recoveryDecisionRepository, auditEventRepository,
                policyService, new RecoveryActionService(recoveryActionRepository, auditEventRepository));

        HttpRazorpayPaymentLinkClient realClient = RealRazorpayTestSupport.realClient();
        RecoveryActionExecutionService executionService = new RecoveryActionExecutionService(
                recoveryActionRepository, auditEventRepository, new RazorpayRecoveryActionExecutor(realClient));
        RecoveryActionVerificationService verificationService = new RecoveryActionVerificationService(
                recoveryActionRepository, auditEventRepository, new RazorpayRecoveryActionVerifier(realClient));

        return new RecoveryOrchestrationService(realDiagnosisService, lifecycleService, executionService, verificationService);
    }

    /**
     * The full natural path: 3 prior EXECUTED WAIT_RETRY actions push the
     * real DiagnosisEngine to REPEATED_FAILURE (retry_count=3, unscoped -
     * diagnosis semantics are unchanged by M1.17), StrategyRouter derives
     * PAYMENT_LINK, and - the M1.17 fix - policy's retry limit is scoped to
     * PAYMENT_LINK specifically (0 prior PAYMENT_LINK executions) so it is
     * no longer blocked by the 3 prior WAIT_RETRY attempts. A real Payment
     * Link is created against Razorpay Test Mode; nobody pays it in this
     * automated run, so independent re-verification must NOT report recovery.
     */
    @Test
    void naturalRepeatedFailureDiagnosis_reachesRealRazorpay_createdButUnpaidIsNotVerified() {
        Payment payment = seedFailedPayment("real_natural_repeated", "card_declined", new BigDecimal("10.00"));
        RecoveryCase recoveryCase = recoveryCaseRepository.save(new RecoveryCase(payment));
        for (int i = 0; i < 3; i++) {
            RecoveryDecision priorDecision = recoveryDecisionRepository.save(new RecoveryDecision(
                    recoveryCase, "TEMPORARY_FAILURE", new BigDecimal("0.4000"), "raw", "WAIT_RETRY"));
            RecoveryAction priorAction = new RecoveryAction(priorDecision, "WAIT_RETRY", PolicyResult.ALLOWED);
            // Set EXECUTED before the first save - the V3 partial unique
            // index only allows one PENDING (recovery_case_id, action_type)
            // at a time, and inserting as PENDING first (even briefly, before
            // a follow-up UPDATE) collides across these 3 same-type fixtures.
            priorAction.setExecutionStatus(ExecutionStatus.EXECUTED);
            recoveryActionRepository.save(priorAction);
        }

        RecoveryOrchestrationService orchestration = realRazorpayOrchestration("pay_real_natural_repeated");
        RecoveryOrchestrationResult result = orchestration.recover(payment.getId());

        assertEquals("REPEATED_FAILURE", result.decision().getDiagnosisCategory());
        assertEquals("PAYMENT_LINK", result.decision().getStrategy());
        assertEquals(PolicyResult.ALLOWED, result.policyDecision().result(),
                "PAYMENT_LINK must not be blocked by WAIT_RETRY's retry limit: " + result.policyDecision().failedReasons());

        // A real payment link was created (execution succeeded) but nobody
        // paid it in this automated run, so verification must NOT report
        // recovery - this is the honest, expected real-provider outcome,
        // not a test failure.
        assertEquals(RecoveryOutcome.VERIFICATION_FAILED, result.outcome());
        assertEquals(RecoveryCaseStatus.OPEN, result.recoveryCase().getStatus());

        RecoveryAction action = result.action().orElseThrow();
        assertEquals(ExecutionStatus.EXECUTED, action.getExecutionStatus());
        assertEquals(VerificationStatus.FAILED, action.getVerificationStatus());
        assertNotNull(action.getExternalReference(), "a real Razorpay payment_link id must be persisted");
        assertTrue(action.getExternalReference().startsWith("plink_"), "external reference must be a real Razorpay id");

        // Independent re-fetch, not trust in the create() response.
        RazorpayPaymentLink fetched = RealRazorpayTestSupport.realClient().fetchById(action.getExternalReference());
        assertFalse(fetched.status() == RazorpayPaymentLinkStatus.PAID, "the link genuinely was not paid");

        List<String> eventTypes = auditEventRepository.findAll().stream()
                .filter(e -> e.getRecoveryCase().getId().equals(result.recoveryCase().getId()))
                .map(e -> e.getEventType())
                .toList();
        Set<String> eventTypeSet = eventTypes.stream().collect(Collectors.toSet());
        assertTrue(eventTypeSet.containsAll(Set.of("RECOVERY_DECISION_RECORDED",
                "POLICY_EVALUATED", "ACTION_CREATED", "ACTION_EXECUTION_ATTEMPTED", "ACTION_VERIFICATION_ATTEMPTED")),
                "audit trail must record every stage against the real provider: " + eventTypes);
        assertFalse(eventTypeSet.contains("CASE_STATUS_CHANGED"), "case must never transition to RECOVERED on an unpaid link");
    }
}
