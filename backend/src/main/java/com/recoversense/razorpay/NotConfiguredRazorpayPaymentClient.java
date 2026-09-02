package com.recoversense.razorpay;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The only {@link RazorpayPaymentClient} bean when Razorpay credentials are
 * not configured (mirrors {@code NotImplementedRecoveryActionExecutor}).
 * Honestly reports "not configured" via {@link ProviderUnavailableException}
 * instead of returning an empty list, which would be indistinguishable from
 * "Razorpay is configured and genuinely has zero recent payments" - callers
 * (RazorpayPaymentSyncService) must be able to tell those two cases apart.
 */
@Component
class NotConfiguredRazorpayPaymentClient implements RazorpayPaymentClient {

    @Override
    public List<RazorpayPayment> listRecent(int count) {
        throw new ProviderUnavailableException("Razorpay is not configured (razorpay.key-id is unset)");
    }
}
