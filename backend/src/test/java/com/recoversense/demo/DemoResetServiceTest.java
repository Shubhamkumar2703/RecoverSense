package com.recoversense.demo;

import com.recoversense.domain.AuditEvent;
import com.recoversense.domain.Customer;
import com.recoversense.domain.Payment;
import com.recoversense.domain.PaymentStatus;
import com.recoversense.domain.PolicyResult;
import com.recoversense.domain.RecoveryAction;
import com.recoversense.domain.RecoveryCase;
import com.recoversense.domain.RecoveryDecision;
import com.recoversense.repository.AuditEventRepository;
import com.recoversense.repository.CustomerRepository;
import com.recoversense.repository.PaymentRepository;
import com.recoversense.repository.RecoveryActionRepository;
import com.recoversense.repository.RecoveryCaseRepository;
import com.recoversense.repository.RecoveryDecisionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves DemoResetService reproduces scripts/reset-demo-payment-link.sql's
 * exact semantics over HTTP-reachable Java: only the hero payment's own
 * recovery pipeline rows are removed, every other seeded row is untouched,
 * the payment lands back on FAILED, and running it twice is safe.
 * {@code @Transactional}: the test's own writes roll back automatically, so
 * no manual cleanup is needed (same convention as DemoRealPaymentLinkPathTest).
 */
@SpringBootTest
@ActiveProfiles("demo")
@Transactional
class DemoResetServiceTest {

    @Autowired
    private DemoResetService demoResetService;
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

    private Payment heroPayment() {
        return paymentRepository.findByExternalPaymentId(DemoSettlementVerifier.DEMO_PAYMENT_LINK_EXTERNAL_ID)
                .orElseGet(() -> {
                    Customer customer = new Customer("cust_demo_payment_link", "cust_demo_payment_link@example.com");
                    customerRepository.save(customer);
                    Payment payment = new Payment(DemoSettlementVerifier.DEMO_PAYMENT_LINK_EXTERNAL_ID, customer,
                            new BigDecimal("1000.00"), "INR", PaymentStatus.FAILED);
                    return paymentRepository.save(payment);
                });
    }

    /** Attaches one full case/decision/action/audit chain, as a real recover() would leave behind. */
    private RecoveryCase attachRecoveryHistory(Payment payment) {
        RecoveryCase recoveryCase = recoveryCaseRepository.save(new RecoveryCase(payment));
        RecoveryDecision decision = recoveryDecisionRepository.save(new RecoveryDecision(
                recoveryCase, "REPEATED_FAILURE", new BigDecimal("0.9000"), "raw", "PAYMENT_LINK"));
        RecoveryAction action = new RecoveryAction(decision, "PAYMENT_LINK", PolicyResult.ALLOWED);
        recoveryActionRepository.save(action);
        auditEventRepository.save(new AuditEvent(recoveryCase, "POLICY_EVALUATED", "{}"));
        return recoveryCase;
    }

    @Test
    void reset_removesHeroPaymentsRecoveryHistoryAndRestoresFailedState() {
        Payment payment = heroPayment();
        RecoveryCase recoveryCase = attachRecoveryHistory(payment);

        DemoResetResponse response = demoResetService.resetHeroPaymentLink();

        assertTrue(response.success());
        assertEquals(DemoSettlementVerifier.DEMO_PAYMENT_LINK_EXTERNAL_ID, response.paymentId());
        assertEquals("FAILED", response.status());

        assertTrue(recoveryCaseRepository.findById(recoveryCase.getId()).isEmpty(), "hero recovery case must be gone");
        assertTrue(recoveryCaseRepository.findByPayment(payment).isEmpty(), "hero payment must have no recovery cases left");

        Payment reloaded = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatus.FAILED, reloaded.getStatus());
    }

    @Test
    void reset_doesNotTouchOtherSeededPaymentsOrCustomers() {
        Payment hero = heroPayment();
        attachRecoveryHistory(hero);

        Customer otherCustomer = customerRepository.save(new Customer("cust_untouched", "cust_untouched@example.com"));
        Payment otherPayment = paymentRepository.save(
                new Payment("pay_untouched", otherCustomer, new BigDecimal("500.00"), "INR", PaymentStatus.FAILED));
        RecoveryCase otherCase = attachRecoveryHistory(otherPayment);

        demoResetService.resetHeroPaymentLink();

        assertTrue(paymentRepository.findById(otherPayment.getId()).isPresent(), "other payment must be untouched");
        assertTrue(customerRepository.findById(otherCustomer.getId()).isPresent(), "other customer must be untouched");
        assertTrue(recoveryCaseRepository.findById(otherCase.getId()).isPresent(), "other payment's recovery case must be untouched");
        assertEquals(1, auditEventRepository.findByRecoveryCase_IdOrderByCreatedAtAsc(otherCase.getId()).size());
    }

    @Test
    void reset_isIdempotent_whenCalledTwiceInARow() {
        Payment payment = heroPayment();
        attachRecoveryHistory(payment);

        demoResetService.resetHeroPaymentLink();
        DemoResetResponse second = demoResetService.resetHeroPaymentLink();

        assertTrue(second.success());
        assertEquals("FAILED", second.status());
        assertTrue(recoveryCaseRepository.findByPayment(payment).isEmpty());
    }

    @Test
    void reset_neverResetsFailedAtToTheFuture() {
        Payment payment = heroPayment();

        demoResetService.resetHeroPaymentLink();

        Payment reloaded = paymentRepository.findById(payment.getId()).orElseThrow();
        assertTrue(reloaded.getFailedAt().isBefore(Instant.now()));
    }
}
