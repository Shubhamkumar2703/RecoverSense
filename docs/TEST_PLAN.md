# RecoverSense — Test Plan

## Unit tests

### Diagnosis
- valid structured response
- malformed response
- unsupported failure type
- invalid confidence
- AI unavailable

### Strategy
- every failure type
- unknown diagnosis
- deterministic mapping

### Policy
Each seven checks:
- pass
- fail
- boundary values
- combined failures

### Verification
- expected equals actual
- field mismatch
- execution failure
- stale state
- already-settled state
- simulator mismatch

## Integration tests

- PostgreSQL persistence
- provider adapter
- AI adapter
- webhook validation
- end-to-end recovery case

## End-to-end

```text
Failure
→ Diagnosis
→ Strategy
→ Policy
→ Execution
→ Verification
→ Audit
→ Metrics
```

## High-value edge cases

- payment already recovered
- duplicate recovery
- retry limit exceeded
- amount above limit
- inactive subscription
- inactive customer
- delayed webhook
- provider timeout
- provider returns success but state is wrong
- AI response malformed
- simulator path used

## Rule

Financial safety paths must be deterministic and independently testable.
