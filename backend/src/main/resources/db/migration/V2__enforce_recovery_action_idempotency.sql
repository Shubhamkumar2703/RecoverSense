-- Enforces the MVP invariant: a RecoveryDecision must not have more than one
-- RecoveryAction of the same action_type. This is the final, DB-backed
-- protection against a race in RecoveryActionService.createIfAllowed's
-- find-before-create check (see M1.6 report).

CREATE UNIQUE INDEX uq_recovery_actions_decision_action_type
    ON recovery_actions (recovery_decision_id, action_type);
