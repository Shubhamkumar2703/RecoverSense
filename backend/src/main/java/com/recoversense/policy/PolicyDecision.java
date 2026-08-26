package com.recoversense.policy;

import com.recoversense.domain.PolicyResult;

import java.util.List;

public record PolicyDecision(PolicyResult result, List<PolicyCheckResult> checks) {

    public List<String> failedReasons() {
        return checks.stream()
                .filter(check -> !check.passed())
                .map(PolicyCheckResult::reason)
                .toList();
    }
}
