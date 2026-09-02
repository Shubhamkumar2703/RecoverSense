package com.recoversense.razorpay;

import java.time.Instant;

/**
 * RecoverSense-side representation of one Razorpay payment record (GET
 * /v1/payments), translated from the provider's raw JSON by {@link
 * HttpRazorpayPaymentClient}. Amount is in the smallest currency unit
 * (paise for INR), matching {@link RazorpayPaymentLink} - conversion to
 * RecoverSense's BigDecimal rupee amounts happens at the ingestion boundary
 * (see RazorpayPaymentSyncService), not here.
 */
public record RazorpayPayment(
        String id,
        String status,
        long amountInSmallestUnit,
        String currency,
        String errorDescription,
        Instant createdAt,
        String orderId
) {
}
