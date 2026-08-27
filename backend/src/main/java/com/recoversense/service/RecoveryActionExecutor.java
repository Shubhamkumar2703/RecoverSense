package com.recoversense.service;

import com.recoversense.domain.ExecutionStatus;
import com.recoversense.domain.RecoveryAction;

/**
 * Seam for a future real execution provider (e.g. Razorpay). Reuses the
 * existing {@link ExecutionStatus} vocabulary rather than inventing a
 * parallel result type.
 * <p>
 * An implementation must return only {@link ExecutionStatus#EXECUTED} or
 * {@link ExecutionStatus#FAILED} - never {@link ExecutionStatus#PENDING},
 * and it must never mutate the given action itself; the calling service
 * applies the transition. If execution genuinely cannot be attempted (no
 * provider wired up), throw {@link UnsupportedOperationException} rather
 * than fabricating a result - see {@link NotImplementedRecoveryActionExecutor}.
 */
public interface RecoveryActionExecutor {

    ExecutionStatus execute(RecoveryAction action);
}
