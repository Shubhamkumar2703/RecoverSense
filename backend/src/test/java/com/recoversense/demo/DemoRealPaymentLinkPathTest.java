package com.recoversense.demo;

import com.recoversense.domain.Customer;
import com.recoversense.domain.CustomerStatus;
import com.recoversense.domain.Payment;
import com.recoversense.domain.PaymentStatus;
import com.recoversense.domain.PolicyResult;
import com.recoversense.domain.RecoveryCase;
import com.recoversense.policy.PolicyDecision;
import com.recoversense.repository.CustomerRepository;
import com.recoversense.repository.PaymentRepository;
import com.recoversense.repository.RecoveryCaseRepository;
import com.recoversense.service.DiagnosisService;
import com.recoversense.service.RecoveryDiagnosisInput;
import com.recoversense.service.RecoveryPolicyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * M1.25: proves the demo Payment Link scenario reaches the real pipeline's
 * ALLOWED policy result naturally - REPEATED_FAILURE from DiagnosisEngine's
 * reason-text rule (not a fake RecoveryAction manipulating retry_count, and
 * not a special-cased externalPaymentId inside DiagnosisEngine itself), then
 * PAYMENT_LINK's policy check passing only because DemoSettlementVerifier
 * explicitly answers NOT_SETTLED for this one demo id - proven against the
 * real, unmodified DiagnosisService/RecoveryPolicyService/PolicyEngine, not
 * a hand-built stand-in.
 */
@SpringBootTest
@ActiveProfiles("demo")
@Transactional
class DemoRealPaymentLinkPathTest {

    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private RecoveryCaseRepository recoveryCaseRepository;
    @Autowired
    private DiagnosisService diagnosisService;
    @Autowired
    private RecoveryPolicyService recoveryPolicyService;

    private static final Instant STALE_ENOUGH = Instant.now().minus(Duration.ofMinutes(30));
    private static final String REPEATED_FAILURE_REASON = "Repeated payment failure - card declined multiple times";

    /**
     * DemoDataSeeder's CommandLineRunner already ran (non-transactionally,
     * at this demo-profile context's own startup) and seeded exactly the
     * demo payment id this test also wants - reuse that existing row instead
     * of inserting a duplicate, which would violate the unique constraint on
     * external_payment_id. Any other, non-seeded id is created fresh as
     * usual.
     */
    private Payment seedFailedPayment(String externalPaymentId) {
        return paymentRepository.findByExternalPaymentId(externalPaymentId)
                .orElseGet(() -> insertFailedPayment(externalPaymentId));
    }

    private Payment insertFailedPayment(String externalPaymentId) {
        Customer customer = new Customer("cust_" + externalPaymentId, "user+" + externalPaymentId + "@example.com");
        customer.setStatus(CustomerStatus.ACTIVE);
        customerRepository.save(customer);

        Payment payment = new Payment(externalPaymentId, customer, new BigDecimal("1000.00"), "INR", PaymentStatus.FAILED);
        payment.setSubscriptionStatus("ACTIVE");
        payment.setFailureReason(REPEATED_FAILURE_REASON);
        payment.setFailedAt(STALE_ENOUGH);
        return paymentRepository.save(payment);
    }

    @Test
    void demoPaymentLinkPayment_naturallyDiagnosesAsRepeatedFailure_routedToPaymentLink() {
        Payment payment = seedFailedPayment(DemoSettlementVerifier.DEMO_PAYMENT_LINK_EXTERNAL_ID);

        RecoveryDiagnosisInput diagnosis = diagnosisService.diagnose(payment.getId());

        assertEquals("REPEATED_FAILURE", diagnosis.diagnosisCategory());
        assertEquals("PAYMENT_LINK", diagnosis.strategy());
    }

    @Test
    void demoPaymentLinkPayment_policyIsAllowedOnlyBecauseOfDemoSettlementEvidence() {
        Payment payment = seedFailedPayment(DemoSettlementVerifier.DEMO_PAYMENT_LINK_EXTERNAL_ID);
        RecoveryCase recoveryCase = recoveryCaseRepository.save(new RecoveryCase(payment));

        PolicyDecision decision = recoveryPolicyService.evaluate(recoveryCase.getId(), "PAYMENT_LINK");

        assertEquals(PolicyResult.ALLOWED, decision.result(), "expected ALLOWED: " + decision.failedReasons());
    }

    /**
     * The same otherwise-valid setup for a DIFFERENT payment id must still
     * BLOCK - DemoSettlementVerifier must not leak simulated NOT_SETTLED
     * evidence to any payment other than the one explicit demo id.
     */
    @Test
    void otherPayment_sameDemoProfile_stillBlockedOnUnknownSettlement() {
        Payment payment = seedFailedPayment("pay_demo_some_other_payment_link_candidate");
        RecoveryCase recoveryCase = recoveryCaseRepository.save(new RecoveryCase(payment));

        PolicyDecision decision = recoveryPolicyService.evaluate(recoveryCase.getId(), "PAYMENT_LINK");

        assertEquals(PolicyResult.BLOCKED, decision.result());
    }
}
