# RecoverSense — Demo Scenarios

## Scenario 1 — Hero
Payment: ₹2,499
Failure: mandate revoked
Diagnosis: MANDATE_INVALID
Strategy: REACQUIRE_MANDATE
Policy: ALLOW
Execution: provider or clearly labeled simulator
Verification: PASS
Outcome: VERIFIED

## Scenario 2 — Insufficient funds
Payment: ₹1,499
Failure: insufficient funds
Strategy: WAIT_RETRY
Outcome: PENDING or VERIFIED depending on test result.

## Scenario 3 — Repeated failure
Payment: ₹5,000
Failure: repeated failure
Strategy: PAYMENT_LINK
Policy: ALLOW
Verification: PASS if provider/test state confirms.

## Scenario 4 — High amount / policy block
Payment: ₹75,000
Policy amount limit: ₹50,000
Decision: BLOCK
No financial execution.
Audit must show the failed amount-limit check.

## Scenario 5 — Customer cancelled
Strategy: STOP
No financial action.
Audit records why execution did not occur.
