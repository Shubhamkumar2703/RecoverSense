package com.recoversense.claude;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * claude.api-key is the server-side Anthropic API key (CLAUDE_API_KEY env
 * var, following this project's RazorpayProperties precedent: a real
 * credential, so deliberately no default value - its absence is exactly what
 * keeps ClaudeAutoConfiguration inactive, see that class). Never read by, or
 * exposed to, frontend code - only ClaudeAutoConfiguration touches this.
 * <p>
 * claude.model defaults to the current default model rather than requiring
 * every environment to set it explicitly.
 */
@ConfigurationProperties(prefix = "claude")
public record ClaudeProperties(
        String apiKey,
        String model,
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout
) {

    public ClaudeProperties {
        if (model == null || model.isBlank()) {
            model = "claude-sonnet-5";
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.anthropic.com/v1";
        }
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(5);
        }
        if (readTimeout == null) {
            // Diagnosis is a synchronous call in the recovery lifecycle
            // (see RecoveryOrchestrationService) - generous but bounded, in
            // line with RazorpayProperties' own read timeout.
            readTimeout = Duration.ofSeconds(20);
        }
    }
}
