package com.recoversense.razorpay;

/**
 * HTTP-facing summary of one sync run - no raw Razorpay payload, no
 * credentials, just counts.
 */
record RazorpaySyncResponse(boolean available, int imported, int skipped, String message) {

    static RazorpaySyncResponse from(RazorpaySyncResult result) {
        if (!result.available()) {
            return new RazorpaySyncResponse(false, 0, 0,
                    "Razorpay is not configured on this server (razorpay.key-id is unset).");
        }
        return new RazorpaySyncResponse(true, result.imported(), result.skipped(), null);
    }
}
