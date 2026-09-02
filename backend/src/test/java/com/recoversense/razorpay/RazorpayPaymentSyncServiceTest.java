package com.recoversense.razorpay;

import com.recoversense.domain.Payment;
import com.recoversense.domain.PaymentStatus;
import com.recoversense.repository.CustomerRepository;
import com.recoversense.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1.26 Phase 1/9: proves the ingestion boundary's safety properties -
 * filtering, idempotency, no recovery-domain writes, no PII import - against
 * a stub RazorpayPaymentClient (unit-level; the real HTTP mapping is proven
 * separately by HttpRazorpayPaymentClientTest).
 */
@SpringBootTest
@Transactional
class RazorpayPaymentSyncServiceTest {

    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private CustomerRepository customerRepository;

    private RazorpayPaymentSyncService serviceWith(RazorpayPaymentClient client) {
        return new RazorpayPaymentSyncService(client, paymentRepository, customerRepository);
    }

    private RazorpayPayment failedPayment(String id, long amountInSmallestUnit) {
        return new RazorpayPayment(id, "failed", amountInSmallestUnit, "INR", "card declined", Instant.now(), null);
    }

    @Test
    void syncFailedPayments_importsOnlyFailedStatus() {
        RazorpayPaymentSyncService service = serviceWith(count -> List.of(
                failedPayment("pay_sync_failed_1", 100000),
                new RazorpayPayment("pay_sync_captured_1", "captured", 100000, "INR", null, Instant.now(), null)));

        RazorpaySyncResult result = service.syncFailedPayments();

        assertTrue(result.available());
        assertEquals(1, result.imported());
        assertTrue(paymentRepository.findByExternalPaymentId("pay_sync_failed_1").isPresent());
        assertFalse(paymentRepository.findByExternalPaymentId("pay_sync_captured_1").isPresent());
    }

    @Test
    void syncFailedPayments_convertsSmallestUnitToRupees() {
        RazorpayPaymentSyncService service = serviceWith(count -> List.of(failedPayment("pay_sync_amount", 250000)));

        service.syncFailedPayments();

        Payment payment = paymentRepository.findByExternalPaymentId("pay_sync_amount").orElseThrow();
        assertEquals(0, new BigDecimal("2500.00").compareTo(payment.getAmount()));
        assertEquals(PaymentStatus.FAILED, payment.getStatus());
    }

    @Test
    void syncFailedPayments_isIdempotent_doesNotDuplicateOnRepeatedSync() {
        RazorpayPaymentSyncService service = serviceWith(count -> List.of(failedPayment("pay_sync_repeat", 100000)));

        RazorpaySyncResult first = service.syncFailedPayments();
        RazorpaySyncResult second = service.syncFailedPayments();

        assertEquals(1, first.imported());
        assertEquals(0, second.imported());
        assertEquals(1, second.skipped());
        assertEquals(1, paymentRepository.findAll().stream()
                .filter(p -> "pay_sync_repeat".equals(p.getExternalPaymentId())).count());
    }

    @Test
    void syncFailedPayments_neverImportsRealCustomerEmail() {
        RazorpayPaymentSyncService service = serviceWith(count -> List.of(failedPayment("pay_sync_pii", 100000)));

        service.syncFailedPayments();

        Payment payment = paymentRepository.findByExternalPaymentId("pay_sync_pii").orElseThrow();
        assertNull(payment.getCustomer().getEmail());
    }

    @Test
    void syncFailedPayments_leavesSubscriptionStatusNull_neverGuessesActive() {
        RazorpayPaymentSyncService service = serviceWith(count -> List.of(failedPayment("pay_sync_no_subscription", 100000)));

        service.syncFailedPayments();

        Payment payment = paymentRepository.findByExternalPaymentId("pay_sync_no_subscription").orElseThrow();
        assertNull(payment.getSubscriptionStatus());
    }

    @Test
    void syncFailedPayments_razorpayNotConfigured_reportsUnavailableRatherThanEmpty() {
        RazorpayPaymentSyncService service = serviceWith(new NotConfiguredRazorpayPaymentClient());

        RazorpaySyncResult result = service.syncFailedPayments();

        assertFalse(result.available());
        assertEquals(0, result.imported());
    }
}
