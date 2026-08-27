package com.recoversense.service;

import com.recoversense.domain.RecoveryAction;
import com.recoversense.domain.VerificationStatus;
import org.springframework.stereotype.Component;

/**
 * The only verification provider currently wired up. No Razorpay/HTTP client
 * exists yet, so this honestly reports "not implemented" instead of
 * inferring verification success from local execution status.
 */
@Component
class NotImplementedRecoveryActionVerifier implements RecoveryActionVerifier {

    @Override
    public VerificationStatus verify(RecoveryAction action) {
        throw new UnsupportedOperationException(
                "Verification is not implemented yet; RecoveryAction " + action.getId() + " remains " + action.getVerificationStatus());
    }
}
