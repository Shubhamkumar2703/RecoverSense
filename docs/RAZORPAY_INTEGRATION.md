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

## Important

Exact endpoint names, SDK behavior, authentication requirements and test-mode capabilities must be verified against current official Razorpay documentation before implementation.
