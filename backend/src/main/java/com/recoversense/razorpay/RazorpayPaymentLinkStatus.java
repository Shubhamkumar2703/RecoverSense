package com.recoversense.razorpay;

/**
 * Razorpay Payment Link statuses, per the officially documented set:
 * created, partially_paid, expired, cancelled, paid (see M1.13 research -
 * Create/Fetch Payment Link API docs). UNKNOWN is this adapter's own
 * fail-safe fallback for any value Razorpay might add later that this
 * mapping doesn't yet recognize - never guessed as PAID.
 */
public enum RazorpayPaymentLinkStatus {
    CREATED,
    PARTIALLY_PAID,
    PAID,
    EXPIRED,
    CANCELLED,
    UNKNOWN;

    public static RazorpayPaymentLinkStatus fromRaw(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        return switch (raw) {
            case "created" -> CREATED;
            case "partially_paid" -> PARTIALLY_PAID;
            case "paid" -> PAID;
            case "expired" -> EXPIRED;
            case "cancelled" -> CANCELLED;
            default -> UNKNOWN;
        };
    }
}
