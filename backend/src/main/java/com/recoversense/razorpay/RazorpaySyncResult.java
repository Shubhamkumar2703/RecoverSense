package com.recoversense.razorpay;

/**
 * Outcome of one {@link RazorpayPaymentSyncService#syncFailedPayments} run.
 * {@code available=false} means Razorpay is not configured at all (see
 * {@link NotConfiguredRazorpayPaymentClient}) - distinct from a genuinely
 * empty/zero-import sync, which is {@code available=true} with
 * {@code imported=0}.
 */
public record RazorpaySyncResult(boolean available, int imported, int skipped) {

    static RazorpaySyncResult unavailable() {
        return new RazorpaySyncResult(false, 0, 0);
    }

    static RazorpaySyncResult completed(int imported, int skipped) {
        return new RazorpaySyncResult(true, imported, skipped);
    }
}
