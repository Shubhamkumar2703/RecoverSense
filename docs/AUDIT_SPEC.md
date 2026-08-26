# RecoverSense — Audit Specification

## Purpose

The audit trail is a first-class product artifact.

It should answer:
1. Why did RecoverSense choose this action?
2. Was the action allowed?
3. What happened during execution?
4. Did the business state actually change?
5. Was recovery verified?

## Event sequence

Example:

```text
FAILURE_DETECTED
AI_DIAGNOSIS_COMPLETE
STRATEGY_SELECTED
POLICY_CHECK
POLICY_DECISION
ACTION_EXECUTED
BUSINESS_STATE_VERIFIED
RECOVERY_COMPLETED
```

## Event fields

Minimum:
- event_id
- occurred_at
- recovery_case_id
- payment_id/subscription_id when available
- event_type
- actor (`SYSTEM`, `AI`, `POLICY`, `SIMULATOR`, etc.)
- status
- structured metadata
- correlation_id

## Audit principles

- append-only behavior
- timestamped
- explainable
- exportable
- every policy check recorded
- simulation explicitly labeled
- no fabricated success events
