package com.recoversense.claude;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Guards ClaudeAutoConfiguration on a genuinely non-blank claude.api-key -
 * unlike plain {@code @ConditionalOnProperty(name = "claude.api-key")}, which
 * only checks the property is present, not non-empty.
 * <p>
 * Discovered via M1.16 real-provider testing: an env var declared but left
 * empty (e.g. {@code CLAUDE_API_KEY=} in a sourced .env, or an unset
 * container env var placeholder) still counts as "present" to
 * ConditionalOnProperty, which activated ClaudeAutoConfiguration with an
 * empty key - the real Claude API then genuinely rejects the request
 * (401 authentication_error), so no financial action could follow either
 * way, but the wrong provider (a broken real one) was selected instead of
 * the intended SimulatedDiagnosisProvider fallback. This condition makes
 * "no usable key" and "no key at all" behave identically.
 */
class ClaudeApiKeyConfiguredCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String apiKey = context.getEnvironment().getProperty("claude.api-key");
        return apiKey != null && !apiKey.isBlank();
    }
}
