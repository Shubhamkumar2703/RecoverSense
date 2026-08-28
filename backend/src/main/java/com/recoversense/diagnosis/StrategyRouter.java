package com.recoversense.diagnosis;

/**
 * Deterministic Diagnosis -> Strategy mapping (docs/STRATEGY_MATRIX.md /
 * docs/DECISION_LOGIC.md Stage 2). Pure and stateless: the same
 * diagnosisCategory always routes to the same strategy, regardless of which
 * {@link DiagnosisProvider} (Claude or simulated) produced it.
 * <p>
 * This is the one place strategy is chosen. A {@link DiagnosisProvider} -
 * Claude included - supplies only a classification; it never supplies an
 * authoritative strategy. Any failureType outside the closed taxonomy
 * (including a value a provider invented) falls closed to ESCALATE rather
 * than guessing.
 */
public final class StrategyRouter {

    public String route(String diagnosisCategory) {
        return switch (diagnosisCategory) {
            case "MANDATE_INVALID" -> "REACQUIRE_MANDATE";
            case "INSUFFICIENT_FUNDS" -> "WAIT_RETRY";
            case "REPEATED_FAILURE" -> "PAYMENT_LINK";
            case "TEMPORARY_FAILURE" -> "WAIT_RETRY";
            case "CUSTOMER_CANCELLED" -> "STOP";
            default -> "ESCALATE";
        };
    }
}
