# RecoverSense — Failure Taxonomy

## MANDATE_INVALID
Underlying recurring mandate/instrument is invalid or revoked.

Typical direction:
REACQUIRE_MANDATE

## INSUFFICIENT_FUNDS
Payment failed because available funds were insufficient.

Typical direction:
WAIT_RETRY

## REPEATED_FAILURE
Repeated attempts have failed and another blind retry is unlikely to be the best next action.

Typical direction:
PAYMENT_LINK

## TEMPORARY_FAILURE
Failure appears transient and may be appropriate for a controlled retry.

Typical direction:
WAIT_RETRY

## CUSTOMER_CANCELLED
Customer/business state indicates the recovery should not continue automatically.

Typical direction:
STOP or ESCALATE depending on explicit state.

## Important

The diagnosis model must not invent a category outside the supported taxonomy without an explicit fallback.
