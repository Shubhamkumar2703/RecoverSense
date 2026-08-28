package com.recoversense.razorpay;

import java.util.Optional;

/**
 * Provider-boundary port for Razorpay Payment Links. The rest of RecoverSense
 * (RazorpayRecoveryActionExecutor/Verifier) depends only on this interface,
 * never on HTTP status codes, endpoint paths, or Razorpay's request/response
 * shape - those belong entirely to {@link HttpRazorpayPaymentLinkClient}.
 * <p>
 * Every method throws {@link ProviderRejectedException} for a genuine
 * provider-asserted rejection, or {@link ProviderUnavailableException} when
 * the outcome is ambiguous (network/timeout/malformed response) - never
 * silently swallowed, never guessed.
 */
public interface RazorpayPaymentLinkClient {

    RazorpayPaymentLink create(CreatePaymentLinkRequest request);

    RazorpayPaymentLink fetchById(String paymentLinkId);

    /**
     * Fetches all Payment Links registered against the given reference_id -
     * the documented mechanism (GET /v1/payment_links/?reference_id=...)
     * this adapter uses to reconcile an ambiguous create() outcome without
     * ever needing the plink_... id a timeout may have prevented it from
     * receiving. Empty means genuinely not found (a fresh, successful GET
     * with zero matches) - not the same as "unknown", which is always a
     * thrown ProviderUnavailableException instead.
     */
    Optional<RazorpayPaymentLink> findByReferenceId(String referenceId);
}
