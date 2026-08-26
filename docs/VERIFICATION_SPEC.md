# RecoverSense — Verification Specification

## Principle

**Execution success is not recovery success.**

## Four-step verification pattern

1. Define expected state.
2. Capture actual state after execution.
3. Diff expected vs actual.
4. Record VERIFIED or FAILED with field-level mismatch information.

## Example

Expected:
```json
{
  "subscription_status": "authenticated",
  "customer_id": "cust_demo",
  "amount": 2499
}
```

Actual:
Re-fetched from the relevant payment/subscription endpoint or deterministic simulator state.

Result:
```text
VERIFIED
```

or:

```text
FAILED
mismatch: subscription_status expected authenticated, got paused
```

## Verification rules

- Never trust only the write response.
- Re-fetch when the integration supports it.
- Do not count revenue as recovered until verification succeeds.
- If verification is unavailable, mark outcome UNKNOWN/UNVERIFIED rather than SUCCESS.
- Simulator verification must use deterministic state transitions.

## Required tests

- successful verification
- execution succeeds but state mismatch
- execution fails
- stale state
- already settled state
- duplicate action
- simulator state mismatch
