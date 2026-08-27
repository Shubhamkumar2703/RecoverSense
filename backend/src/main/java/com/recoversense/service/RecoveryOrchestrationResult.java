package com.recoversense.service;

import com.recoversense.domain.RecoveryAction;
import com.recoversense.domain.RecoveryCase;
import com.recoversense.domain.RecoveryDecision;
import com.recoversense.policy.PolicyDecision;

import java.util.Optional;

public record RecoveryOrchestrationResult(RecoveryCase recoveryCase, RecoveryDecision decision,
                                           PolicyDecision policyDecision, Optional<RecoveryAction> action,
                                           RecoveryOutcome outcome) {
}
