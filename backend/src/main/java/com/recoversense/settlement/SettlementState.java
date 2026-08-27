package com.recoversense.settlement;

/**
 * Result of checking whether a payment has already settled through another
 * channel (POLICY_SPEC.md P06). UNKNOWN is first-class - it is not the same
 * as NOT_SETTLED and must never be collapsed into it. RecoveryPolicyService
 * maps this to the alreadySettledElsewhere fact PolicyEngine already fails
 * closed on when unknown.
 */
public enum SettlementState {
    SETTLED,
    NOT_SETTLED,
    UNKNOWN
}
