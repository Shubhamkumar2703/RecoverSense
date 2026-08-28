package com.recoversense.razorpay;

import com.recoversense.domain.Customer;
import com.recoversense.domain.CustomerStatus;
import com.recoversense.domain.ExecutionStatus;
import com.recoversense.domain.Payment;
import com.recoversense.domain.PaymentStatus;
import com.recoversense.domain.PolicyResult;
import com.recoversense.domain.RecoveryAction;
import com.recoversense.domain.RecoveryCase;
import com.recoversense.domain.RecoveryDecision;
import com.recoversense.domain.VerificationStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RazorpayRecoveryActionVerifierTest {

    private RecoveryAction seedExecutedPaymentLinkAction(String suffix, String plinkId) {
        Customer customer = new Customer("cust_verify_" + suffix, "user+verify" + suffix + "@example.com");
        customer.setStatus(CustomerStatus.ACTIVE);
        Payment payment = new Payment("pay_verify_" + suffix, customer, new BigDecimal("1000.00"), "INR", PaymentStatus.FAILED);
        RecoveryCase recoveryCase = new RecoveryCase(payment);
        RecoveryDecision decision = new RecoveryDecision(recoveryCase, "MANDATE_INVALID", new BigDecimal("0.9000"), "raw", "PAYMENT_LINK");
        RecoveryAction action = new RecoveryAction(decision, "PAYMENT_LINK", PolicyResult.ALLOWED);
        action.setExecutionStatus(ExecutionStatus.EXECUTED);
        action.setExecutedAt(Instant.now());
        action.setExternalReference(plinkId);
        return action;
    }

    private static final class FakeClient implements RazorpayPaymentLinkClient {
        RazorpayPaymentLink fetchReturns;
        RuntimeException fetchThrows;

        @Override
        public RazorpayPaymentLink create(CreatePaymentLinkRequest request) {
            throw new UnsupportedOperationException("not used by verifier");
        }

        @Override
        public RazorpayPaymentLink fetchById(String paymentLinkId) {
            if (fetchThrows != null) {
                throw fetchThrows;
            }
            return fetchReturns;
        }

        @Override
        public java.util.Optional<RazorpayPaymentLink> findByReferenceId(String referenceId) {
            throw new UnsupportedOperationException("not used by verifier");
        }
    }

    @Test
    void paidWithSufficientAmount_returnsVerified() {
        RecoveryAction action = seedExecutedPaymentLinkAction("1", "plink_1");
        FakeClient client = new FakeClient();
        client.fetchReturns = new RazorpayPaymentLink("plink_1", "rs-action-1", RazorpayPaymentLinkStatus.PAID, 100000, 100000, "url");
        RazorpayRecoveryActionVerifier verifier = new RazorpayRecoveryActionVerifier(client);

        assertEquals(VerificationStatus.VERIFIED, verifier.verify(action));
    }

    @Test
    void paidWithInsufficientAmount_returnsFailed() {
        RecoveryAction action = seedExecutedPaymentLinkAction("2", "plink_2");
        FakeClient client = new FakeClient();
        client.fetchReturns = new RazorpayPaymentLink("plink_2", "rs-action-2", RazorpayPaymentLinkStatus.PAID, 100000, 50000, "url");
        RazorpayRecoveryActionVerifier verifier = new RazorpayRecoveryActionVerifier(client);

        assertEquals(VerificationStatus.FAILED, verifier.verify(action));
    }

    @Test
    void unpaidCreated_returnsFailed() {
        RecoveryAction action = seedExecutedPaymentLinkAction("3", "plink_3");
        FakeClient client = new FakeClient();
        client.fetchReturns = new RazorpayPaymentLink("plink_3", "rs-action-3", RazorpayPaymentLinkStatus.CREATED, 100000, 0, "url");
        RazorpayRecoveryActionVerifier verifier = new RazorpayRecoveryActionVerifier(client);

        assertEquals(VerificationStatus.FAILED, verifier.verify(action));
    }

    @Test
    void partiallyPaid_returnsFailed() {
        RecoveryAction action = seedExecutedPaymentLinkAction("4", "plink_4");
        FakeClient client = new FakeClient();
        client.fetchReturns = new RazorpayPaymentLink("plink_4", "rs-action-4", RazorpayPaymentLinkStatus.PARTIALLY_PAID, 100000, 40000, "url");
        RazorpayRecoveryActionVerifier verifier = new RazorpayRecoveryActionVerifier(client);

        assertEquals(VerificationStatus.FAILED, verifier.verify(action));
    }

    @Test
    void expired_returnsFailed() {
        RecoveryAction action = seedExecutedPaymentLinkAction("5", "plink_5");
        FakeClient client = new FakeClient();
        client.fetchReturns = new RazorpayPaymentLink("plink_5", "rs-action-5", RazorpayPaymentLinkStatus.EXPIRED, 100000, 0, "url");
        RazorpayRecoveryActionVerifier verifier = new RazorpayRecoveryActionVerifier(client);

        assertEquals(VerificationStatus.FAILED, verifier.verify(action));
    }

    @Test
    void cancelled_returnsFailed() {
        RecoveryAction action = seedExecutedPaymentLinkAction("6", "plink_6");
        FakeClient client = new FakeClient();
        client.fetchReturns = new RazorpayPaymentLink("plink_6", "rs-action-6", RazorpayPaymentLinkStatus.CANCELLED, 100000, 0, "url");
        RazorpayRecoveryActionVerifier verifier = new RazorpayRecoveryActionVerifier(client);

        assertEquals(VerificationStatus.FAILED, verifier.verify(action));
    }

    @Test
    void providerUnavailableDuringFetch_propagatesException_notFabricatedFailed() {
        RecoveryAction action = seedExecutedPaymentLinkAction("7", "plink_7");
        FakeClient client = new FakeClient();
        client.fetchThrows = new ProviderUnavailableException("timeout");
        RazorpayRecoveryActionVerifier verifier = new RazorpayRecoveryActionVerifier(client);

        assertThrows(ProviderUnavailableException.class, () -> verifier.verify(action));
    }

    @Test
    void nonPaymentLinkActionType_throwsUnsupportedOperationException() {
        Customer customer = new Customer("cust_verify_8", "user+verify8@example.com");
        Payment payment = new Payment("pay_verify_8", customer, new BigDecimal("1000.00"), "INR", PaymentStatus.FAILED);
        RecoveryCase recoveryCase = new RecoveryCase(payment);
        RecoveryDecision decision = new RecoveryDecision(recoveryCase, "MANDATE_INVALID", new BigDecimal("0.9000"), "raw", "REACQUIRE_MANDATE");
        RecoveryAction action = new RecoveryAction(decision, "REACQUIRE_MANDATE", PolicyResult.ALLOWED);
        action.setExecutionStatus(ExecutionStatus.EXECUTED);
        RazorpayRecoveryActionVerifier verifier = new RazorpayRecoveryActionVerifier(new FakeClient());

        assertThrows(UnsupportedOperationException.class, () -> verifier.verify(action));
    }
}
