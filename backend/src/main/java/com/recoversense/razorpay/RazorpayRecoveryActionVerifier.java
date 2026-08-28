package com.recoversense.razorpay;

import com.recoversense.domain.Payment;
import com.recoversense.domain.RecoveryAction;
import com.recoversense.domain.VerificationStatus;
import com.recoversense.service.RecoveryActionVerifier;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Razorpay-backed RecoveryActionVerifier, PAYMENT_LINK only (see
 * RazorpayRecoveryActionExecutor for why RETRY_PAYMENT/REACQUIRE_MANDATE
 * remain unimplemented). Always independently re-fetches the Payment Link by
 * its stored externalReference - never infers VERIFIED from execution having
 * succeeded. Only status=paid with amount_paid covering the expected amount
 * is VERIFIED; created/partially_paid/expired/cancelled/unrecognized all
 * fail closed to FAILED, since none of them is proof of full collection.
 */
public class RazorpayRecoveryActionVerifier implements RecoveryActionVerifier {

    static final String PAYMENT_LINK_ACTION_TYPE = "PAYMENT_LINK";

    private final RazorpayPaymentLinkClient client;

    public RazorpayRecoveryActionVerifier(RazorpayPaymentLinkClient client) {
        this.client = client;
    }

    @Override
    public VerificationStatus verify(RecoveryAction action) {
        if (!PAYMENT_LINK_ACTION_TYPE.equals(action.getActionType())) {
            throw new UnsupportedOperationException(
                    "Razorpay verification for actionType " + action.getActionType() + " is not implemented");
        }

        RazorpayPaymentLink link;
        try {
            link = client.fetchById(action.getExternalReference());
        } catch (ProviderRejectedException notFoundOrRejected) {
            return VerificationStatus.FAILED;
        }
        // ProviderUnavailableException (extends UnsupportedOperationException) is
        // intentionally left uncaught here: RecoveryActionVerificationService
        // already leaves the action UNVERIFIED and audits it, identically to the
        // "not implemented yet" path.

        Payment payment = action.getRecoveryDecision().getRecoveryCase().getPayment();
        long expectedAmount = toSmallestUnit(payment.getAmount());

        boolean fullyPaid = link.status() == RazorpayPaymentLinkStatus.PAID
                && link.amountPaidInSmallestUnit() >= expectedAmount;
        return fullyPaid ? VerificationStatus.VERIFIED : VerificationStatus.FAILED;
    }

    private static long toSmallestUnit(BigDecimal amount) {
        return amount.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }
}
