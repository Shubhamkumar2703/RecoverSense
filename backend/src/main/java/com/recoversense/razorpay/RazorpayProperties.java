package com.recoversense.razorpay;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * razorpay.key-id / razorpay.key-secret are the server-side Basic Auth
 * credentials (RAZORPAY_KEY_ID / RAZORPAY_KEY_SECRET env vars, following this
 * project's existing ${ENV_VAR:default} convention for datasource config -
 * but deliberately with NO default value for key-secret, since unlike a
 * local dev DB password this is a real credential even in Razorpay test
 * mode). Never read by, or exposed to, frontend code - only
 * RazorpayAutoConfiguration touches these.
 */
@ConfigurationProperties(prefix = "razorpay")
public record RazorpayProperties(
        String keyId,
        String keySecret,
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout
) {

    public RazorpayProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.razorpay.com/v1";
        }
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(5);
        }
        if (readTimeout == null) {
            readTimeout = Duration.ofSeconds(10);
        }
    }
}
