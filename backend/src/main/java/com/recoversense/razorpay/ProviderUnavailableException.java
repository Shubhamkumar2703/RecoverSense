package com.recoversense.razorpay;

/**
 * Razorpay's response (or its absence) does not tell RecoverSense whether
 * the requested mutation actually happened: network failure, connect/read
 * timeout, an HTTP 5xx, an unparseable/unexpected response body, or a
 * create() rejected specifically because its deterministic reference_id was
 * already used (which itself may mean an earlier ambiguous attempt actually
 * succeeded server-side).
 * <p>
 * Deliberately extends {@link UnsupportedOperationException} so it is caught
 * by the exact same, already-proven RecoveryActionExecutionService /
 * RecoveryActionVerificationService catch/audit/rethrow path used for
 * "provider not implemented yet" (see M1.11 follow-up: noRollbackFor keeps
 * that audit event durable). The action is left PENDING (or UNVERIFIED), the
 * ambiguity is audited, and no outcome is fabricated - per M1.13 research,
 * Razorpay documents no generic idempotency mechanism for Payment Link
 * creation, so an ambiguous mutation must be reconciled via a fresh read
 * (reference_id lookup), never retried blindly.
 */
public class ProviderUnavailableException extends UnsupportedOperationException {

    public ProviderUnavailableException(String message) {
        super(message);
    }

    public ProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
