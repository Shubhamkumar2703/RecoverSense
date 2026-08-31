package com.recoversense.razorpay;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Builds a real HttpRazorpayPaymentLinkClient against the real Razorpay Test
 * Mode API, reading credentials only from the JVM environment (RAZORPAY_KEY_ID
 * / RAZORPAY_KEY_SECRET) - never from a hardcoded value, never logged. Test
 * classes that use this must be annotated
 * {@code @EnabledIfEnvironmentVariable(named = "RAZORPAY_KEY_ID", matches = ".+")}
 * so they auto-skip (not fail) when credentials are absent - see M1.16
 * REAL_PROVIDER_SMOKE classification.
 * <p>
 * Wiring mirrors RazorpayAutoConfiguration exactly (same base URL, same Basic
 * Auth mechanism) - this is not a second, divergent client implementation,
 * just the same construction done directly instead of through Spring for
 * test-only real-provider classes that don't otherwise need the full
 * application context configured with real credentials.
 */
final class RealRazorpayTestSupport {

    static HttpRazorpayPaymentLinkClient realClient() {
        String keyId = requireEnv("RAZORPAY_KEY_ID");
        String keySecret = requireEnv("RAZORPAY_KEY_SECRET");

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(15));

        RestClient restClient = RestClient.builder()
                .baseUrl("https://api.razorpay.com/v1")
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBasicAuth(keyId, keySecret);
                    return execution.execute(request, body);
                })
                .build();
        return new HttpRazorpayPaymentLinkClient(restClient);
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set to run a REAL_PROVIDER_SMOKE/E2E test");
        }
        return value;
    }

    private RealRazorpayTestSupport() {
    }
}
