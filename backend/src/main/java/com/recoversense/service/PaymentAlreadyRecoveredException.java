package com.recoversense.service;

/**
 * Payment.status intentionally stays FAILED after a successful recovery (see
 * docs/DECISIONS.md ADR-012) - RecoveryCase.status is the authoritative
 * signal that recovery has completed. A RECOVERED RecoveryCase already
 * existing for this payment is therefore what re-recovery must be guarded
 * against, not Payment.status: nothing about the payment's own state
 * changes to make a second attempt look invalid on its own.
 */
public class PaymentAlreadyRecoveredException extends RuntimeException {

    public PaymentAlreadyRecoveredException(Long paymentId) {
        super("Payment " + paymentId + " already has a RECOVERED recovery case; re-recovery is not permitted");
    }
}
