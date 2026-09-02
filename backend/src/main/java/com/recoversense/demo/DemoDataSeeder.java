package com.recoversense.demo;

import com.recoversense.domain.Customer;
import com.recoversense.domain.Payment;
import com.recoversense.domain.PaymentStatus;
import com.recoversense.repository.CustomerRepository;
import com.recoversense.repository.PaymentRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Seeds two deterministic FAILED payments so the At-Risk Payments screen has
 * something to demo without hand-inserting rows:
 * <ol>
 *   <li>CLAUDE.md's hero scenario - mandate revoked while the subscription
 *   remains active - which reaches policy BLOCKED (see
 *   {@link com.recoversense.demo.DemoSettlementVerifier})</li>
 *   <li>M1.25's real Razorpay Payment Link scenario ({@link
 *   DemoSettlementVerifier#DEMO_PAYMENT_LINK_EXTERNAL_ID}) - a repeated
 *   failure that reaches policy ALLOWED via the demo settlement evidence,
 *   so it can be executed against a real Razorpay Test Mode Payment Link
 *   when razorpay.key-id/key-secret are configured</li>
 * </ol>
 * <p>
 * Only runs under {@code --spring.profiles.active=demo} / {@code
 * SPRING_PROFILES_ACTIVE=demo} - never in a default/production boot - and is
 * idempotent (checked by externalPaymentId) so rerunning it is safe. It only
 * ever creates a FAILED Payment with no RecoveryCase; the real
 * diagnosis/policy/execution/verification pipeline runs unmodified from
 * there when Recover is clicked.
 */
@Component
@Profile("demo")
public class DemoDataSeeder implements CommandLineRunner {

    private static final String MANDATE_REVOKED_PAYMENT_ID = "pay_demo_mandate_revoked";

    private final CustomerRepository customerRepository;
    private final PaymentRepository paymentRepository;

    public DemoDataSeeder(CustomerRepository customerRepository, PaymentRepository paymentRepository) {
        this.customerRepository = customerRepository;
        this.paymentRepository = paymentRepository;
    }

    @Override
    public void run(String... args) {
        seedIfAbsent(MANDATE_REVOKED_PAYMENT_ID, "cust_demo_mandate_revoked", "sub_demo_mandate_revoked",
                new BigDecimal("2499.00"), "Mandate revoked by customer bank");

        // M1.25: failure reason deliberately contains "repeated"+"fail" so
        // DiagnosisEngine's evidence-based rule reaches REPEATED_FAILURE ->
        // PAYMENT_LINK naturally (no retry_count history needed, and none is
        // seeded here - see DiagnosisEngine's M1.25 rule and
        // docs/DEMO.md's real-Payment-Link walkthrough).
        seedIfAbsent(DemoSettlementVerifier.DEMO_PAYMENT_LINK_EXTERNAL_ID, "cust_demo_payment_link", "sub_demo_payment_link",
                new BigDecimal("1000.00"), "Repeated payment failure - card declined multiple times");
    }

    private void seedIfAbsent(String externalPaymentId, String externalCustomerId, String subscriptionId,
                               BigDecimal amount, String failureReason) {
        if (paymentRepository.findByExternalPaymentId(externalPaymentId).isPresent()) {
            return;
        }

        Customer customer = customerRepository.save(new Customer(externalCustomerId, externalCustomerId + "@example.com"));

        Payment payment = new Payment(externalPaymentId, customer, amount, "INR", PaymentStatus.FAILED);
        payment.setSubscriptionId(subscriptionId);
        payment.setSubscriptionStatus("ACTIVE");
        payment.setFailureReason(failureReason);
        payment.setFailedAt(Instant.now().minus(30, ChronoUnit.MINUTES));
        paymentRepository.save(payment);
    }
}
