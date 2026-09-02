package com.recoversense.diagnosis;

import com.recoversense.domain.CustomerStatus;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Deterministic, dependency-free failure diagnosis. No LLM, no HTTP, no
 * provider calls - classifies using only the facts already present in the
 * current domain (customer status, payment failure reason, subscription
 * status, retry count).
 * <p>
 * Categories are exactly the ones defined in docs/FAILURE_TAXONOMY.md.
 * "UNKNOWN" is the sole explicit fallback for insufficient evidence - never
 * a guess dressed up as one of the five taxonomy categories.
 * <p>
 * Diagnosis is advisory only: it selects a category, nothing more. Strategy
 * selection lives in {@link StrategyRouter}, and neither this class nor its
 * output ever authorizes execution - PolicyEngine remains the sole authority
 * on whether a proposed action is permitted.
 * <p>
 * This is the deterministic classifier wrapped by {@link
 * SimulatedDiagnosisProvider} - the local/test stand-in for the real
 * {@code ClaudeDiagnosisProvider} (see the {@code com.recoversense.claude}
 * package). It is not used in the default Claude-backed path.
 */
public final class DiagnosisEngine {

    // Fixed, deterministic confidence levels - never a computed/arbitrary
    // value, always within the 0..1 range AI_DIAGNOSIS.md's validation rule
    // requires (and well within the diagnosis_confidence NUMERIC(5,4) column).
    static final BigDecimal HIGH_CONFIDENCE = new BigDecimal("0.9000");
    static final BigDecimal LOW_CONFIDENCE = new BigDecimal("0.4000");
    static final BigDecimal UNKNOWN_CONFIDENCE = new BigDecimal("0.0000");

    // POLICY_SPEC.md P01 defines the retry ceiling as retry_count < 3. Once a
    // payment has already reached that many executed attempts, another blind
    // retry is unlikely to help - FAILURE_TAXONOMY.md's REPEATED_FAILURE.
    // This reuses the same spec-derived number as the policy check; it does
    // not re-implement or call the policy check itself.
    static final int REPEATED_FAILURE_RETRY_THRESHOLD = 3;

    public DiagnosisResult diagnose(DiagnosisContext context) {
        if (context.customerStatus() == CustomerStatus.INACTIVE) {
            // FAILURE_TAXONOMY.md: "STOP or ESCALATE depending on explicit
            // business state." CustomerStatus.INACTIVE is the only explicit
            // state currently available to distinguish this case; STOP is
            // the chosen default terminal outcome (implementation decision).
            return new DiagnosisResult("CUSTOMER_CANCELLED", HIGH_CONFIDENCE, "customer status is INACTIVE");
        }

        String reason = context.failureReason();
        String normalizedReason = reason == null ? null : reason.toLowerCase(Locale.ROOT);

        if (normalizedReason != null && normalizedReason.contains("mandate")
                && (normalizedReason.contains("revoked") || normalizedReason.contains("invalid"))) {
            return new DiagnosisResult("MANDATE_INVALID", HIGH_CONFIDENCE,
                    "failure reason indicates the mandate is revoked/invalid: reason=" + reason
                            + ", subscription_status=" + context.subscriptionStatus());
        }

        if (normalizedReason != null && normalizedReason.contains("insufficient") && normalizedReason.contains("fund")) {
            return new DiagnosisResult("INSUFFICIENT_FUNDS", HIGH_CONFIDENCE,
                    "failure reason indicates insufficient funds: reason=" + reason);
        }

        // M1.25: a second, independent evidence source for REPEATED_FAILURE,
        // alongside retry_count below - the failure reason text itself can
        // already assert repeated failure (e.g. a gateway/webhook payload
        // that says so directly), exactly like every other category here is
        // driven by matching the reason text against known keywords. This is
        // not specific to any one payment: any failureReason containing both
        // words reaches REPEATED_FAILURE, the same way "mandate"+"revoked"
        // reaches MANDATE_INVALID above - see DiagnosisEngineTest for the
        // regression coverage.
        if (normalizedReason != null && normalizedReason.contains("repeated") && normalizedReason.contains("fail")) {
            return new DiagnosisResult("REPEATED_FAILURE", HIGH_CONFIDENCE,
                    "failure reason indicates repeated failure: reason=" + reason);
        }

        if (context.retryCount() >= REPEATED_FAILURE_RETRY_THRESHOLD) {
            return new DiagnosisResult("REPEATED_FAILURE", HIGH_CONFIDENCE,
                    "retry_count=" + context.retryCount() + " has reached the repeated-failure threshold of "
                            + REPEATED_FAILURE_RETRY_THRESHOLD);
        }

        if (reason != null && !reason.isBlank()) {
            // A reason exists but matched none of the known keyword patterns
            // above - treat as transient rather than guess at a more
            // specific category we have no evidence for.
            return new DiagnosisResult("TEMPORARY_FAILURE", LOW_CONFIDENCE,
                    "failure reason present but not confidently classified: reason=" + reason);
        }

        // No failure reason, no repeat-failure signal, customer not flagged
        // inactive: insufficient evidence. AI_DIAGNOSIS.md's fallback rule -
        // do not execute automatically, use a safe hold path - maps to
        // ESCALATE, never one of the executable strategies.
        return new DiagnosisResult("UNKNOWN", UNKNOWN_CONFIDENCE, "insufficient evidence to diagnose the failure");
    }
}
