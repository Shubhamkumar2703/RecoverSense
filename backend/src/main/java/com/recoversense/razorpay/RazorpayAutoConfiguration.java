package com.recoversense.razorpay;

import com.recoversense.service.RecoveryActionExecutor;
import com.recoversense.service.RecoveryActionVerifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wires the real Razorpay-backed RecoveryActionExecutor/Verifier ONLY when
 * razorpay.key-id is configured. Absent (the default for every existing
 * test and any environment without Razorpay set up), this whole class is
 * skipped and NotImplementedRecoveryActionExecutor/Verifier remain the only
 * beans - unmodified, untouched, zero risk to existing behavior.
 * <p>
 * When active, the Razorpay beans are marked @Primary rather than
 * conditioning NotImplementedRecoveryActionExecutor/Verifier out - both
 * beans exist together (the unused one harmlessly so), avoiding any change
 * to M1.6/M1.7's files.
 */
@Configuration
@ConditionalOnProperty(prefix = "razorpay", name = "key-id")
@EnableConfigurationProperties(RazorpayProperties.class)
public class RazorpayAutoConfiguration {

    @Bean
    RestClient razorpayRestClient(RazorpayProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBasicAuth(properties.keyId(), properties.keySecret());
                    return execution.execute(request, body);
                })
                .build();
    }

    @Bean
    RazorpayPaymentLinkClient razorpayPaymentLinkClient(RestClient razorpayRestClient) {
        return new HttpRazorpayPaymentLinkClient(razorpayRestClient);
    }

    /**
     * M1.26: read-only Razorpay payment ingestion (see RazorpayPaymentSyncService).
     * Same @Primary-over-coexistence pattern as every other bean here -
     * NotConfiguredRazorpayPaymentClient stays registered (harmlessly unused)
     * rather than being conditioned out.
     */
    @Bean
    @Primary
    RazorpayPaymentClient razorpayPaymentClient(RestClient razorpayRestClient) {
        return new HttpRazorpayPaymentClient(razorpayRestClient);
    }

    @Bean
    @Primary
    RecoveryActionExecutor razorpayRecoveryActionExecutor(RazorpayPaymentLinkClient razorpayPaymentLinkClient) {
        return new RazorpayRecoveryActionExecutor(razorpayPaymentLinkClient);
    }

    @Bean
    @Primary
    RecoveryActionVerifier razorpayRecoveryActionVerifier(RazorpayPaymentLinkClient razorpayPaymentLinkClient) {
        return new RazorpayRecoveryActionVerifier(razorpayPaymentLinkClient);
    }
}
