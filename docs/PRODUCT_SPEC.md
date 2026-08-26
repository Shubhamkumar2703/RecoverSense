# RecoverSense — Product Specification

## Problem

Recurring payment failures have different causes. A generic retry loop can repeatedly attempt actions that do not address the underlying failure.

RecoverSense asks:

> Given the failure and current business state, what recovery action should happen next?

## Target user

Merchant/revenue operations team responsible for recurring payment recovery.

## Product promise

RecoverSense:
1. understands the failure
2. selects a recovery strategy
3. enforces merchant policy
4. executes safely
5. verifies the outcome
6. explains the decision

## Core recovery strategies

The source specification defines five strategy types:
- WAIT_RETRY
- PAYMENT_LINK
- REACQUIRE_MANDATE
- ESCALATE
- STOP

The working demo may emphasize three executable strategies while retaining terminal ESCALATE/STOP paths.

## Failure taxonomy

The source specification defines five failure types for the MVP:
- MANDATE_INVALID
- INSUFFICIENT_FUNDS
- REPEATED_FAILURE
- TEMPORARY_FAILURE
- CUSTOMER_CANCELLED

Exact mapping from Razorpay payload to taxonomy must be validated against actual test payloads.

## Hero scenario

```text
Mandate revoked
      ↓
AI diagnosis: MANDATE_INVALID
      ↓
Strategy: REACQUIRE_MANDATE
      ↓
Policy checks
      ↓
ALLOW / BLOCK
      ↓
Execution or clearly-labelled simulator
      ↓
Re-fetch business state
      ↓
VERIFIED / FAILED
      ↓
Audit
```

## Product non-goals

- production UPI/eNACH authentication
- WhatsApp/voice recovery
- custom trained ML model
- multi-agent orchestration
- causal uplift measurement
- second payment gateway integration before deadline

## Important honesty rule

A simulated mandate state change must be clearly labeled as simulated. A test-mode Razorpay action must be labeled as test mode.
