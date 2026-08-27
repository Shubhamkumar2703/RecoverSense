package com.recoversense.service;

import com.recoversense.domain.RecoveryCase;
import com.recoversense.domain.RecoveryDecision;
import com.recoversense.policy.PolicyDecision;

public record RecoveryLifecycleResult(RecoveryCase recoveryCase, RecoveryDecision decision, PolicyDecision policyDecision) {
}
