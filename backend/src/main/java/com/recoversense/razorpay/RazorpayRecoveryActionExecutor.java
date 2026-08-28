package com.recoversense.razorpay;

import com.recoversense.domain.ExecutionStatus;
import com.recoversense.domain.Payment;
import com.recoversense.domain.RecoveryAction;
import com.recoversense.service.RecoveryActionExecutor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Razorpay-backed RecoveryActionExecutor. Only PAYMENT_LINK is implemented -
 * RETRY_PAYMENT and REACQUIRE_MANDATE remain UnsupportedOperationException
 * (same honest "not implemented yet" contract NotImplementedRecoveryActionExecutor
 * already uses) because M1.13 research found neither maps to a single
 * server-side Razorpay call: RETRY_PAYMENT has no general retry API (a
 * customer must re-attempt via Checkout, or - for subscriptions specifically -
 * a manual charge against an already-issued invoice, a different shape of
 * action RecoverSense's domain doesn't yet model), and REACQUIRE_MANDATE
 * fundamentally requires customer authentication, not a server-only call.
 * <p>
 * Never converts an ambiguous provider outcome (timeout, 5xx, or a
 * duplicate-reference_id rejection that may mean an earlier attempt already
 * succeeded) into FAILED - see ProviderUnavailableException. On ambiguity,
 * reconciles via the deterministic reference_id before ever considering the
 * action unresolved; never issues a second create() call itself.
 */
public class RazorpayRecoveryActionExecutor implements RecoveryActionExecutor {

    static final String PAYMENT_LINK_ACTION_TYPE = "PAYMENT_LINK";

    private final RazorpayPaymentLinkClient client;

    public RazorpayRecoveryActionExecutor(RazorpayPaymentLinkClient client) {
        this.client = client;
    }

    @Override
    public ExecutionStatus execute(RecoveryAction action) {
        if (!PAYMENT_LINK_ACTION_TYPE.equals(action.getActionType())) {
            throw new UnsupportedOperationException(
                    "Razorpay execution for actionType " + action.getActionType() + " is not implemented");
        }

        Payment payment = action.getRecoveryDecision().getRecoveryCase().getPayment();
        String referenceId = referenceIdFor(action);
        CreatePaymentLinkRequest request = new CreatePaymentLinkRequest(
                toSmallestUnit(payment.getAmount()),
                payment.getCurrency(),
                referenceId,
                "RecoverSense recovery for payment " + payment.getExternalPaymentId(),
                payment.getCustomer() == null ? null : payment.getCustomer().getEmail());

        RazorpayPaymentLink link;
        try {
            link = client.create(request);
        } catch (ProviderRejectedException rejected) {
            return ExecutionStatus.FAILED;
        } catch (ProviderUnavailableException ambiguous) {
            link = reconcile(referenceId, ambiguous);
        }

        action.setExternalReference(link.id());
        return ExecutionStatus.EXECUTED;
    }

    private RazorpayPaymentLink reconcile(String referenceId, ProviderUnavailableException originalFailure) {
        Optional<RazorpayPaymentLink> found;
        try {
            found = client.findByReferenceId(referenceId);
        } catch (ProviderUnavailableException reconciliationAlsoUnavailable) {
            originalFailure.addSuppressed(reconciliationAlsoUnavailable);
            throw originalFailure;
        }
        return found.orElseThrow(() -> originalFailure);
    }

    static String referenceIdFor(RecoveryAction action) {
        return "rs-action-" + action.getId();
    }

    private static long toSmallestUnit(BigDecimal amount) {
        return amount.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }
}
