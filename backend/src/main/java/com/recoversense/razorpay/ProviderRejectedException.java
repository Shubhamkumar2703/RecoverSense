package com.recoversense.razorpay;

/**
 * Razorpay explicitly rejected the request (a genuine, provider-asserted
 * business/validation error - e.g. HTTP 4xx with a parseable Razorpay error
 * body that is NOT the "reference_id already exists" case, which is handled
 * as {@link ProviderUnavailableException} instead - see M1.13 research: a
 * duplicate-reference_id rejection on create() can mean an earlier ambiguous
 * attempt actually succeeded, so it must be reconciled, not treated as a
 * fresh failure).
 * <p>
 * This is a real, known outcome, not an ambiguity - callers may safely treat
 * it as {@code ExecutionStatus.FAILED} / {@code VerificationStatus.FAILED}.
 */
public class ProviderRejectedException extends RuntimeException {

    private final String razorpayErrorCode;

    public ProviderRejectedException(String razorpayErrorCode, String message, Throwable cause) {
        super(message, cause);
        this.razorpayErrorCode = razorpayErrorCode;
    }

    public String getRazorpayErrorCode() {
        return razorpayErrorCode;
    }
}
