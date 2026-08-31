package com.recoversense.razorpay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1.16 REAL_PROVIDER_SMOKE: the smallest safe proof that RecoverSense can
 * authenticate against the real Razorpay Test Mode API. Uses the List
 * Payment Links read endpoint (GET /v1/payment_links?reference_id=...) with
 * a reference_id guaranteed not to exist yet, rather than creating or
 * fetching-by-id a fabricated resource - a genuinely empty match set proves
 * authentication and connectivity without inventing a fake Razorpay ID.
 * <p>
 * Skipped (not failed) when RAZORPAY_KEY_ID is absent from the environment,
 * so the normal deterministic suite is never made flaky by missing external
 * credentials - see RealRazorpayTestSupport. Never calls anything but this
 * one real API; never creates a financial object.
 */
@EnabledIfEnvironmentVariable(named = "RAZORPAY_KEY_ID", matches = ".+")
class RazorpayRealApiSmokeTest {

    @Test
    void authenticatedReadRequest_succeedsAgainstRealTestModeApi() {
        HttpRazorpayPaymentLinkClient client = RealRazorpayTestSupport.realClient();
        // Razorpay's real API enforces reference_id/receipt <= 40 chars -
        // discovered by actually running this against Test Mode (see M1.16
        // report). Production's own reference_id ("rs-action-" + id) is far
        // shorter and unaffected; only this test's id needed shortening.
        String neverUsedReferenceId = "rs-smk-" + UUID.randomUUID().toString().substring(0, 12);

        // A successful, authenticated GET against a reference_id that has
        // never been used returns an empty match set (200 with []) - not a
        // 404. An auth failure (401) or malformed request (400) would throw
        // instead, which is exactly what this test is proving does NOT happen.
        Optional<RazorpayPaymentLink> found = client.findByReferenceId(neverUsedReferenceId);

        assertTrue(found.isEmpty(), "a fresh, never-used reference_id must have no matches");
    }
}
