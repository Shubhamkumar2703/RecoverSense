package com.recoversense.batch;

/**
 * Structural safety invariants of the batch evaluator itself, not
 * hand-picked to look good. Every one of these is expected to always be
 * zero because BatchEvaluationService's own control flow makes the
 * corresponding violation impossible - see BatchEvaluationService's
 * javadoc. Computed rather than hardcoded so a future change to that
 * control flow that actually broke one of these guarantees would show up
 * here instead of silently passing.
 */
public record BatchSafetySummary(
        int unauthorizedActions,
        int policyViolations,
        int duplicatePendingActions,
        int unverifiedRecoveries
) {
}
