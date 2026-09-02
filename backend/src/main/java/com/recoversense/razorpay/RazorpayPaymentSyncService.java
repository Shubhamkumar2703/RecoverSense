package com.recoversense.razorpay;

import com.recoversense.domain.Customer;
import com.recoversense.domain.Payment;
import com.recoversense.domain.PaymentStatus;
import com.recoversense.repository.CustomerRepository;
import com.recoversense.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * M1.26 Phase 1: read-only ingestion of real Razorpay Test Mode failed
 * payments into RecoverSense's own {@link Payment} table - never anything
 * more. This is a data-import boundary, not a recovery boundary:
 * <ul>
 *   <li>fetches a small, explicitly bounded page (see {@link #SYNC_COUNT}),
 *   never a full account history</li>
 *   <li>imports only {@code status="failed"} records - Razorpay's other
 *   payment states (created/authorized/captured/refunded) are not
 *   RecoverSense's concern</li>
 *   <li>never creates a RecoveryCase/RecoveryDecision/RecoveryAction and
 *   never calls RecoveryOrchestrationService - importing a payment is not
 *   permission to recover it (see docs/DECISIONS.md)</li>
 *   <li>idempotent by Razorpay's own payment id (Payment.externalPaymentId,
 *   already unique-constrained): a payment already present locally is left
 *   completely untouched, never updated - this is the safest interpretation
 *   of "upsert" here, since a synced Payment row may already be mid-recovery
 *   (a RecoveryCase referencing it) and this class has no way to know
 *   whether overwriting any field would corrupt that state, so it never
 *   tries</li>
 *   <li>imports no customer PII beyond a synthetic identifier derived from
 *   the Razorpay payment id itself - RecoverSense's Customer.email is never
 *   populated from Razorpay's real payer contact/email fields</li>
 * </ul>
 */
@Service
public class RazorpayPaymentSyncService {

    private static final int SYNC_COUNT = 20;
    private static final String FAILED_STATUS = "failed";

    private final RazorpayPaymentClient razorpayPaymentClient;
    private final PaymentRepository paymentRepository;
    private final CustomerRepository customerRepository;

    public RazorpayPaymentSyncService(RazorpayPaymentClient razorpayPaymentClient, PaymentRepository paymentRepository,
                                       CustomerRepository customerRepository) {
        this.razorpayPaymentClient = razorpayPaymentClient;
        this.paymentRepository = paymentRepository;
        this.customerRepository = customerRepository;
    }

    @Transactional
    public RazorpaySyncResult syncFailedPayments() {
        List<RazorpayPayment> recent;
        try {
            recent = razorpayPaymentClient.listRecent(SYNC_COUNT);
        } catch (UnsupportedOperationException notConfigured) {
            return RazorpaySyncResult.unavailable();
        }

        int imported = 0;
        int skipped = 0;
        for (RazorpayPayment razorpayPayment : recent) {
            if (!FAILED_STATUS.equalsIgnoreCase(razorpayPayment.status())) {
                continue;
            }
            if (paymentRepository.findByExternalPaymentId(razorpayPayment.id()).isPresent()) {
                skipped++;
                continue;
            }
            insertFailedPayment(razorpayPayment);
            imported++;
        }
        return RazorpaySyncResult.completed(imported, skipped);
    }

    private void insertFailedPayment(RazorpayPayment razorpayPayment) {
        // Synthetic identifier only - never the real payer's email/contact
        // Razorpay's payment object may carry.
        Customer customer = customerRepository.save(new Customer("cust_rzp_" + razorpayPayment.id(), null));

        Payment payment = new Payment(razorpayPayment.id(), customer, toRupees(razorpayPayment.amountInSmallestUnit()),
                orDefaultCurrency(razorpayPayment.currency()), PaymentStatus.FAILED);
        payment.setSubscriptionId(razorpayPayment.orderId());
        payment.setFailureReason(razorpayPayment.errorDescription());
        payment.setFailedAt(razorpayPayment.createdAt() == null ? Instant.now() : razorpayPayment.createdAt());
        // subscriptionStatus deliberately left null: a plain GET /v1/payments
        // record carries no subscription-state evidence, and PolicyEngine's
        // subscription_state_valid check must never be guessed at "active" -
        // see docs/RAZORPAY_INTEGRATION.md's M1.26 section.
        paymentRepository.save(payment);
    }

    private BigDecimal toRupees(long amountInSmallestUnit) {
        return BigDecimal.valueOf(amountInSmallestUnit).movePointLeft(2);
    }

    private String orDefaultCurrency(String currency) {
        return currency == null || currency.isBlank() ? "INR" : currency;
    }
}
