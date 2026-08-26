# RecoverSense — Decision Logic

## Stage 1 — Diagnosis

Input:
- payment failure payload
- source/step/reason where available
- payment/subscription context
- relevant history

Output:
- failure_type
- confidence
- reasoning
- normalized evidence

LLM output must be structured and schema-validated.

## Stage 2 — Strategy

Deterministic mapping.

Example baseline:

| Failure | Strategy |
|---|---|
| MANDATE_INVALID | REACQUIRE_MANDATE |
| INSUFFICIENT_FUNDS | WAIT_RETRY |
| REPEATED_FAILURE | PAYMENT_LINK |
| TEMPORARY_FAILURE | WAIT_RETRY |
| CUSTOMER_CANCELLED | STOP or ESCALATE depending on explicit business state |

The exact final mapping must be recorded as an implementation decision after test-data validation.

## Stage 3 — Policy

The selected strategy becomes a proposed action.

Policy does not ask the LLM whether the action is safe.

Policy evaluates deterministic state.

## Stage 4 — Execution

Only ALLOW reaches execution.

## Stage 5 — Verification

Execution outcome is not the final outcome.

Re-fetch and compare business state.

## Stage 6 — Audit

Record every major transition.
