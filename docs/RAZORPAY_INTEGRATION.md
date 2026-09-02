# RecoverSense — Razorpay Integration Boundary

## Purpose

Razorpay is the reference payment provider for the MVP.

## Integration rule

Keep provider HTTP/API calls behind a single adapter/client boundary.

Business services should not scatter provider-specific HTTP calls.

## Expected integration areas from the source specification

- subscriptions
- payment links
- tokens/payment instruments where applicable
- payment/subscription state retrieval
- webhook intake

## Test mode

Use Razorpay test mode for actual supported operations.

The source specification explicitly allows a small deterministic simulator only where test infrastructure cannot expose an arbitrary state transition, such as arbitrary mandate-state injection.

## Simulation rule

Every simulator action must be visibly and technically labeled as:

`SIMULATED`

It must not be represented as a real Razorpay operation.

## Verification

After provider execution, retrieve state again where supported.

## M1.25 — demo settlement evidence is SIMULATED, the Payment Link itself is REAL

Policy check P06 ("has this payment already settled elsewhere?") cannot be answered from the Payment Link API alone - Razorpay has no general "settlement status" endpoint this MVP integrates with. The production `SettlementVerifier` (`UnavailableSettlementVerifier`) honestly answers UNKNOWN, and the policy engine fails closed to BLOCKED, for every payment.

For the demo profile only, `DemoSettlementVerifier` (`SIMULATED`, per the rule above) answers `NOT_SETTLED` for one or more explicit demo payment ids, so that payment's policy evaluation can legitimately reach `ALLOWED`. Everything downstream of that policy decision is real: a real Razorpay Test Mode Payment Link is created via `RazorpayRecoveryActionExecutor`/`HttpRazorpayPaymentLinkClient`, a human pays it through the real hosted checkout, and `RazorpayRecoveryActionVerifier` independently re-fetches the real Payment Link state before the case is ever marked `RECOVERED`. Only the P06 settlement input is simulated - never the execution, never the verification, never the outcome.

## M1.26 — real Razorpay payment ingestion (GET /v1/payments)

`RazorpayPaymentSyncService`/`HttpRazorpayPaymentClient` read the operator's real Razorpay Test Mode failed payments (a small, explicitly bounded page - `count=20`, never a full account history) and insert any not already known locally, keyed by Razorpay's own payment id. This is read-only against Razorpay: it never creates, updates, or cancels anything provider-side, never creates a `RecoveryCase`, and never triggers recovery automatically - see `RazorpayPaymentSyncService`'s javadoc for the full list of what it deliberately does not do.

A real synced payment carries no subscription-state evidence (a plain payment record has none) - `Payment.subscriptionStatus` is left `null` rather than guessed, so PolicyEngine's `subscription_state_valid` check legitimately fails for real synced payments by default. Combined with P06 above, this means a freshly-synced real payment will typically `BLOCK` at policy - which is the correct, honest, fail-closed behavior, not a bug, and is itself a legitimate part of the demo (a real payment, real diagnosis, real policy evaluation, honest block). An operator who wants a real synced payment to reach `ALLOWED` can explicitly add its id to `demo.settlement.extra-not-settled-payment-ids` (still demo-profile only, still one explicit id at a time, never automatic).

`demo.settlement.extra-not-settled-payment-ids` never applies outside the `demo` profile, and `DemoSettlementVerifier` is never `@Primary` there - `UnavailableSettlementVerifier` remains the only `SettlementVerifier` in every non-demo boot, so real credentials being present never silently activates simulation.

## Important

Exact endpoint names, SDK behavior, authentication requirements and test-mode capabilities must be verified against current official Razorpay documentation before implementation.
