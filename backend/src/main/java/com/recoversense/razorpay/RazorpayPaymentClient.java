package com.recoversense.razorpay;

import java.util.List;

/**
 * Provider-boundary port for read-only Razorpay payment ingestion (GET
 * /v1/payments) - deliberately separate from {@link RazorpayPaymentLinkClient},
 * which owns the money-moving Payment Link endpoints. Nothing outside {@link
 * HttpRazorpayPaymentClient} knows /v1/payments exists.
 * <p>
 * Read-only: this port has no create/update method at all, so nothing that
 * depends only on this interface can ever mutate provider state - ingestion
 * (see RazorpayPaymentSyncService) can only ever read and locally record
 * what Razorpay already reports.
 */
public interface RazorpayPaymentClient {

    /**
     * Fetches the most recent {@code count} payments, newest first (Razorpay's
     * own default ordering) - a small, explicitly bounded page, never a full
     * account history. Throws {@link ProviderUnavailableException} for any
     * network/timeout/malformed-response condition, exactly like {@link
     * RazorpayPaymentLinkClient}.
     */
    List<RazorpayPayment> listRecent(int count);
}
