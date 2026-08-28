package com.recoversense.claude;

import com.recoversense.diagnosis.DiagnosisContext;

import java.util.List;
import java.util.Map;

/**
 * The one place the diagnosis prompt and response schema live - see
 * AI_DIAGNOSIS.md / docs/DECISION_LOGIC.md Stage 1. Only facts DiagnosisService
 * already gathers (see DiagnosisContext) are sent; no provider-specific
 * fields (source/step) are invented since the current domain model doesn't
 * carry them, and no secrets are ever included.
 */
final class ClaudeDiagnosisPrompt {

    // Keep in sync with docs/FAILURE_TAXONOMY.md and StrategyRouter's cases.
    static final List<String> SUPPORTED_FAILURE_TYPES = List.of(
            "MANDATE_INVALID", "INSUFFICIENT_FUNDS", "REPEATED_FAILURE", "TEMPORARY_FAILURE",
            "CUSTOMER_CANCELLED", "UNKNOWN");

    static final String SYSTEM_PROMPT = """
            You are diagnosing a failed recurring payment for RecoverSense.
            Classify the failure into exactly one of these failure types: \
            MANDATE_INVALID, INSUFFICIENT_FUNDS, REPEATED_FAILURE, TEMPORARY_FAILURE, CUSTOMER_CANCELLED, UNKNOWN.
            Use UNKNOWN only when the supplied facts are insufficient to confidently choose one of the other five types.
            Return only the required structured diagnosis.
            Do not recommend or execute financial actions.
            Do not invent facts beyond what is supplied.
            """;

    static final Map<String, Object> RESPONSE_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "failureType", Map.of("type", "string", "enum", SUPPORTED_FAILURE_TYPES),
                    "confidence", Map.of("type", "number"),
                    "reasoning", Map.of("type", "string")),
            "required", List.of("failureType", "confidence", "reasoning"),
            "additionalProperties", false);

    static String userMessageFor(DiagnosisContext context) {
        return """
                failure_reason: %s
                subscription_status: %s
                customer_status: %s
                retry_count: %d
                """.formatted(
                orNone(context.failureReason()),
                orNone(context.subscriptionStatus()),
                context.customerStatus(),
                context.retryCount());
    }

    private static String orNone(String value) {
        return value == null || value.isBlank() ? "(none)" : value;
    }

    private ClaudeDiagnosisPrompt() {
    }
}
