package com.recoversense.razorpay;

/**
 * RecoverSense-side representation of a Razorpay Payment Link, translated
 * from the provider's raw JSON by {@link HttpRazorpayPaymentLinkClient}.
 * Amounts are in the smallest currency unit (paise for INR), matching how
 * Razorpay itself represents them - conversion to/from RecoverSense's
 * BigDecimal rupee amounts happens at the executor/verifier boundary, not
 * here.
 */
public record RazorpayPaymentLink(
        String id,
        String referenceId,
        RazorpayPaymentLinkStatus status,
        long amountInSmallestUnit,
        long amountPaidInSmallestUnit,
        String shortUrl
) {
}
