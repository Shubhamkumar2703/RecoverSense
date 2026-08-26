# RecoverSense — Deterministic Policy Specification

## Purpose

The policy engine is the financial safety boundary.

## Seven MVP checks

### P01 — Retry limit
Reject if the allowed retry/recovery attempt count has been exceeded.

### P02 — Subscription state
Reject if the subscription is not in a state that permits the proposed recovery action.

### P03 — Customer state
Reject when customer status makes the action invalid.

### P04 — Duplicate recovery protection
Reject when an equivalent recovery operation is already pending/in flight.

### P05 — Amount limit
Reject when amount exceeds the configured merchant action limit.

Example source-policy value: ₹50,000.

This is a demo policy value, not a universal merchant rule.

### P06 — Already settled elsewhere
Reject if the payment/subscription state indicates that payment has already settled through another channel.

### P07 — Webhook/state freshness
Reject or hold if the system is inside a configured delay window where a delayed webhook could change the current picture.

## Evaluation contract

```json
{
  "decision": "ALLOW",
  "checks": [
    {
      "code": "P01",
      "name": "RETRY_LIMIT",
      "result": "PASS",
      "reason": "retry_count=1, limit=3"
    }
  ],
  "blockedReason": null
}
```

## Rules

- All required checks must pass.
- A failed check blocks execution.
- The frontend cannot override policy.
- The LLM cannot override policy.
- Every check is audited.
- Policy must be unit-testable without external services.

## Why these checks matter

The first checks constrain whether an action is allowed.
The last two address stale-state risk around money movement.
