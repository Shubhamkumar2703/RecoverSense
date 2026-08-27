-- Closes the TOCTOU gap documented in the M1.11 follow-up review: two
-- concurrent recover() calls for the same payment can each independently
-- observe "no pending action" (RecoveryPolicyService's no_pending_reacquisition
-- check) before either has committed, then both create a PENDING
-- RecoveryAction of the same action_type - because V2's uniqueness
-- constraint is scoped to (recovery_decision_id, action_type), and every
-- processFailedPayment call mints a brand-new RecoveryDecision, so it never
-- actually spans two calls for the same payment.
--
-- recovery_actions has no payment/case identifier of its own today - only
-- recovery_decision_id, which is two joins away from recovery_cases. A
-- partial unique index cannot span a join, so recovery_case_id is
-- denormalized onto recovery_actions here, backfilled from the existing
-- recovery_decision -> recovery_case relationship before being made
-- NOT NULL, then used as the actual invariant key.

ALTER TABLE recovery_actions
    ADD COLUMN recovery_case_id BIGINT REFERENCES recovery_cases (id);

UPDATE recovery_actions ra
    SET recovery_case_id = rd.recovery_case_id
    FROM recovery_decisions rd
    WHERE rd.id = ra.recovery_decision_id;

ALTER TABLE recovery_actions
    ALTER COLUMN recovery_case_id SET NOT NULL;

CREATE INDEX idx_recovery_actions_case_id ON recovery_actions (recovery_case_id);

-- Duplicate-in-flight-action protection: at most one PENDING action of a
-- given action_type per recovery case. Partial on execution_status =
-- 'PENDING' so a resolved action (EXECUTED or FAILED) frees the slot for a
-- future, independent recovery attempt of the same action_type - this does
-- not ban repeating a strategy, only concurrent/in-flight duplication
-- (POLICY_SPEC.md P04). Different action_types remain independently
-- allowed, matching existing, intentional behavior
-- (RecoveryActionServiceTest.differentActionTypes_sameDecision_bothAllowed).
CREATE UNIQUE INDEX uq_recovery_actions_pending_case_action_type
    ON recovery_actions (recovery_case_id, action_type)
    WHERE execution_status = 'PENDING';
