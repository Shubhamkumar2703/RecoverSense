package com.recoversense.service;

/**
 * Application-level result of one {@link RecoveryOrchestrationService#recover}
 * run. Not a persisted/domain state - it summarizes which existing domain
 * states (RecoveryCaseStatus, ExecutionStatus, VerificationStatus) the case
 * and action ended up in, for callers that want a single outcome value
 * instead of re-deriving it from the individual results.
 */
public enum RecoveryOutcome {
    /** Policy did not allow an action: no execution was attempted. */
    BLOCKED,
    /** No execution provider is wired up yet (NotImplementedRecoveryActionExecutor). */
    EXECUTION_UNAVAILABLE,
    /** Execution was attempted through a real provider and failed. */
    EXECUTION_FAILED,
    /**
     * M1.25: execution succeeded (e.g. a real Payment Link was created) but
     * verification has not been attempted yet - some actions require an
     * out-of-band step (a human paying a hosted link) before the result can
     * honestly be checked. Verification must be triggered separately via
     * {@link RecoveryOrchestrationService#verify}; recover() never
     * auto-verifies, because a single-shot verification attempted before
     * that out-of-band step completes would fail closed permanently (see
     * RecoveryActionVerificationService's one-shot terminal semantics).
     */
    EXECUTED_AWAITING_VERIFICATION,
    /** No verification provider is wired up yet (NotImplementedRecoveryActionVerifier). */
    VERIFICATION_UNAVAILABLE,
    /** Execution succeeded but re-fetched state did not confirm recovery. */
    VERIFICATION_FAILED,
    /** Execution succeeded and verification confirmed it: case is RECOVERED. */
    RECOVERED
}
