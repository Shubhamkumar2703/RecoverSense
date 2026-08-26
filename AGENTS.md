# RecoverSense — Coding Agent Guide

## Repository mission

Build RecoverSense as a safe, explainable, demo-ready adaptive revenue recovery system.

## Architecture invariant

Failure → Diagnosis → Strategy → Policy → Execution → Verification → Audit

## Agent rules

- Inspect before editing.
- Prefer the smallest safe change.
- Keep business rules deterministic.
- Never let AI bypass policy.
- Never treat API success as business verification.
- Never fabricate production metrics.
- Clearly label simulations.
- Do not introduce infrastructure without a concrete requirement.
- Do not change architecture without recording the decision.

## Financial action invariant

Any action that can change financial state must pass server-side policy.

The frontend is never the financial authorization layer.

## Testing

Every policy rule requires deterministic tests.
Every verification rule requires deterministic tests.
AI output must be schema-validated and tested with malformed/ambiguous cases.

## Completion

A task is not done until:
- implementation exists
- targeted tests pass
- behavior is verified
- documentation is updated when needed
- project state is updated when meaningful
