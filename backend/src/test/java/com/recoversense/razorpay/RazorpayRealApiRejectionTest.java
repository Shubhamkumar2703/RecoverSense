package com.recoversense.razorpay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1.16 REAL_PROVIDER_SMOKE: proves ProviderRejectedException reflects a
 * genuine real Razorpay validation rejection (HTTP 400), not just the local
 * HttpServer stub behavior HttpRazorpayPaymentLinkClientTest already covers.
 * amount=0 is a real, documented Razorpay validation failure (Payment Links
 * require a positive amount) - not a fabricated/undocumented scenario.
 */
@EnabledIfEnvironmentVariable(named = "RAZORPAY_KEY_ID", matches = ".+")
class RazorpayRealApiRejectionTest {

    @Test
    void invalidAmount_isRejectedByTheRealApi() {
        HttpRazorpayPaymentLinkClient client = RealRazorpayTestSupport.realClient();
        // reference_id must stay <= 40 chars - Razorpay's real, documented
        // limit (discovered running this against Test Mode, see M1.16
        // report) - so this rejection is genuinely about amount=0, not an
        // unrelated reference_id-length rejection.
        CreatePaymentLinkRequest invalidRequest = new CreatePaymentLinkRequest(
                0, "INR", "rs-rej-" + UUID.randomUUID().toString().substring(0, 12), "M1.16 rejection smoke test", null);

        ProviderRejectedException thrown = assertThrows(ProviderRejectedException.class, () -> client.create(invalidRequest));
        assertTrue(thrown.getMessage().toLowerCase().contains("amount"),
                "rejection must genuinely be about the invalid amount, not an unrelated validation error: " + thrown.getMessage());
    }

    @Test
    void fetchingANonexistentPaymentLinkId_isRejectedByTheRealApi() {
        HttpRazorpayPaymentLinkClient client = RealRazorpayTestSupport.realClient();

        assertThrows(ProviderRejectedException.class, () -> client.fetchById("plink_does_not_exist_m116"));
    }
}
