package com.recoversense.razorpay;

import com.recoversense.domain.ExecutionStatus;
import com.recoversense.domain.Payment;
import com.recoversense.domain.RecoveryAction;
import com.recoversense.service.RecoveryActionExecutor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
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
 * <p>
 * M1.19: reconciliation is a bounded read-only retry (RECONCILIATION_ATTEMPTS
 * lookups, RECONCILIATION_DELAY apart), not a single lookup - Razorpay's
 * reference_id index is not guaranteed to be immediately consistent with a
 * just-accepted create(), so a single miss right after an ambiguous response
 * isn't proof the resource doesn't exist. Only the read is retried; create()
 * still happens at most once per action, ever.
 */
public class RazorpayRecoveryActionExecutor implements RecoveryActionExecutor {

    static final String PAYMENT_LINK_ACTION_TYPE = "PAYMENT_LINK";
    static final int RECONCILIATION_ATTEMPTS = 3;
    static final Duration RECONCILIATION_DELAY = Duration.ofMillis(50);

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
        // M1.25: transient, request-scoped only (see RecoveryAction.providerUrl) -
        // never persisted, never reconstructed from the id later.
        action.setProviderUrl(link.shortUrl());
        return ExecutionStatus.EXECUTED;
    }

    /**
     * A miss on attempt 1 does not retry the lookup because Razorpay is
     * definitively unreachable - it retries because the resource may exist
     * server-side but not yet be visible via reference_id. If the lookup
     * itself throws (Razorpay is unavailable, not just "not found yet"),
     * that's a different, more certain failure mode and is not retried -
     * unchanged from pre-M1.19 behavior.
     */
    private RazorpayPaymentLink reconcile(String referenceId, ProviderUnavailableException originalFailure) {
        for (int attempt = 1; attempt <= RECONCILIATION_ATTEMPTS; attempt++) {
            Optional<RazorpayPaymentLink> found;
            try {
                found = client.findByReferenceId(referenceId);
            } catch (ProviderUnavailableException reconciliationAlsoUnavailable) {
                originalFailure.addSuppressed(reconciliationAlsoUnavailable);
                throw originalFailure;
            }
            if (found.isPresent()) {
                return found.get();
            }
            if (attempt < RECONCILIATION_ATTEMPTS) {
                sleepBetweenReconciliationAttempts(originalFailure);
            }
        }
        throw originalFailure;
    }

    /**
     * An interrupt during the reconciliation wait is treated the same as
     * exhausting all attempts - the action stays PENDING, restored via the
     * same original exception, rather than surfacing a new, unexpected
     * exception type mid-execution.
     */
    private void sleepBetweenReconciliationAttempts(ProviderUnavailableException originalFailure) {
        try {
            Thread.sleep(RECONCILIATION_DELAY.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw originalFailure;
        }
    }

    static String referenceIdFor(RecoveryAction action) {
        return "rs-action-" + action.getId();
    }

    private static long toSmallestUnit(BigDecimal amount) {
        return amount.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }
}
