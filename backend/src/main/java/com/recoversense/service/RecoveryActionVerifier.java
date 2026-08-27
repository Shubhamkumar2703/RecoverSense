package com.recoversense.service;

import com.recoversense.domain.RecoveryAction;
import com.recoversense.domain.VerificationStatus;

/**
 * Seam for a future real verification provider (re-fetching actual state
 * from Razorpay and diffing against expected state). Reuses the existing
 * {@link VerificationStatus} vocabulary.
 * <p>
 * An implementation must return only {@link VerificationStatus#VERIFIED} or
 * {@link VerificationStatus#FAILED} - never {@link VerificationStatus#UNVERIFIED}
 * - and must never infer success from local execution status alone. If
 * verification genuinely cannot be attempted, throw
 * {@link UnsupportedOperationException} - see
 * {@link NotImplementedRecoveryActionVerifier}.
 */
public interface RecoveryActionVerifier {

    VerificationStatus verify(RecoveryAction action);
}
