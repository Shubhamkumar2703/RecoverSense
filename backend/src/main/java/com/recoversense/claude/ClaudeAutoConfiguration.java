package com.recoversense.claude;

import com.recoversense.diagnosis.DiagnosisProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wires the real Claude-backed DiagnosisProvider ONLY when claude.api-key is
 * configured. Absent (the default for every existing test and any
 * environment without Claude set up), this whole class is skipped and
 * SimulatedDiagnosisProvider remains the only DiagnosisProvider bean -
 * unmodified, untouched, zero risk to existing behavior. Mirrors
 * RazorpayAutoConfiguration exactly, down to the @Primary-over-coexistence
 * choice: SimulatedDiagnosisProvider still exists (harmlessly unused) rather
 * than being conditioned out.
 */
@Configuration
@ConditionalOnProperty(prefix = "claude", name = "api-key")
@EnableConfigurationProperties(ClaudeProperties.class)
public class ClaudeAutoConfiguration {

    @Bean
    RestClient claudeRestClient(ClaudeProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().set("x-api-key", properties.apiKey());
                    request.getHeaders().set("anthropic-version", "2023-06-01");
                    return execution.execute(request, body);
                })
                .build();
    }

    @Bean
    ClaudeHttpClient claudeHttpClient(RestClient claudeRestClient, ClaudeProperties properties) {
        return new ClaudeHttpClient(claudeRestClient, properties.model());
    }

    @Bean
    @Primary
    DiagnosisProvider claudeDiagnosisProvider(ClaudeHttpClient claudeHttpClient) {
        return new ClaudeDiagnosisProvider(claudeHttpClient);
    }
}
