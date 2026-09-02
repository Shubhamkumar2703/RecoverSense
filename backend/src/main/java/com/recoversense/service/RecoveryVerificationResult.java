package com.recoversense.service;

import com.recoversense.domain.PolicyResult;
import com.recoversense.domain.RecoveryAction;
import com.recoversense.domain.RecoveryCase;
import com.recoversense.domain.RecoveryDecision;

/**
 * M1.25: result of {@link RecoveryOrchestrationService#verify}, the second
 * phase of a two-phase recovery (see {@link RecoveryOutcome#EXECUTED_AWAITING_VERIFICATION}).
 * Deliberately a separate type from {@link RecoveryOrchestrationResult}: verify()
 * acts on an already-created decision/action rather than producing a fresh
 * PolicyDecision, so policyResult here is the action's already-recorded
 * PolicyResult (set once, at creation) rather than a freshly evaluated
 * PolicyDecision with its individual checks.
 */
public record RecoveryVerificationResult(RecoveryCase recoveryCase, RecoveryDecision decision,
                                          PolicyResult policyResult, RecoveryAction action,
                                          RecoveryOutcome outcome) {
}
