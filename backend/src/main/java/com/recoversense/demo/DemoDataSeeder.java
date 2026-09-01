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
 * Seeds exactly one deterministic FAILED payment (CLAUDE.md's hero scenario:
 * mandate revoked while the subscription remains active) so the At-Risk
 * Payments screen has something to demo without hand-inserting rows.
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

    private static final String DEMO_PAYMENT_ID = "pay_demo_mandate_revoked";

    private final CustomerRepository customerRepository;
    private final PaymentRepository paymentRepository;

    public DemoDataSeeder(CustomerRepository customerRepository, PaymentRepository paymentRepository) {
        this.customerRepository = customerRepository;
        this.paymentRepository = paymentRepository;
    }

    @Override
    public void run(String... args) {
        if (paymentRepository.findByExternalPaymentId(DEMO_PAYMENT_ID).isPresent()) {
            return;
        }

        Customer customer = customerRepository.save(new Customer("cust_demo_mandate_revoked", "demo.customer@example.com"));

        Payment payment = new Payment(DEMO_PAYMENT_ID, customer, new BigDecimal("2499.00"), "INR", PaymentStatus.FAILED);
        payment.setSubscriptionId("sub_demo_mandate_revoked");
        payment.setSubscriptionStatus("ACTIVE");
        payment.setFailureReason("Mandate revoked by customer bank");
        payment.setFailedAt(Instant.now().minus(30, ChronoUnit.MINUTES));
        paymentRepository.save(payment);
    }
}
