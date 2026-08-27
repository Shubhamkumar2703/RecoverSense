package com.recoversense.service;

import com.recoversense.domain.ExecutionStatus;
import com.recoversense.domain.RecoveryAction;
import org.springframework.stereotype.Component;

/**
 * The only execution provider currently wired up. No Razorpay/HTTP client
 * exists yet, so this honestly reports "not implemented" instead of
 * pretending a provider call succeeded or failed.
 */
@Component
class NotImplementedRecoveryActionExecutor implements RecoveryActionExecutor {

    @Override
    public ExecutionStatus execute(RecoveryAction action) {
        throw new UnsupportedOperationException(
                "Execution is not implemented yet; RecoveryAction " + action.getId() + " remains PENDING");
    }
}
