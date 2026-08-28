package com.recoversense.razorpay;

/**
 * Request to create a Razorpay Payment Link. referenceId must be deterministic
 * and known to the caller before the request is sent - it is what makes
 * post-timeout reconciliation possible (see ProviderUnavailableException).
 */
public record CreatePaymentLinkRequest(
        long amountInSmallestUnit,
        String currency,
        String referenceId,
        String description,
        String customerEmail
) {
}
