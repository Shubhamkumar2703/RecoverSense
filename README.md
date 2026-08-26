# RecoverSense

**Adaptive revenue recovery for recurring payments.**

RecoverSense diagnoses why a recurring payment failed, selects an appropriate recovery strategy, evaluates that action against deterministic merchant policy, executes safely, verifies the resulting business state, and records an auditable decision trail.

## Core flow

**Failure → Diagnosis → Strategy → Policy → Execution → Verification → Audit**

## Core thesis

> Diagnosis before action.
> Policy before execution.
> Verification before counting recovery.

## Build target

Razorpay AI Buildathon 2026 — AI Revenue Recovery

**Current feature-complete target: September 4, 2026**

## Current status

**Pre-code foundation**

The repository is intentionally prepared before application code is written.

## Read first

1. `CLAUDE.md`
2. `docs/PROJECT_CONTEXT.md`
3. `docs/PRODUCT_SPEC.md`
4. `docs/ARCHITECTURE.md`
5. `docs/POLICY_SPEC.md`
6. `docs/VERIFICATION_SPEC.md`
7. `docs/SCOPE.md`
8. `docs/PROJECT_STATE.md`

## Repository

```text
recoversense/
├── backend/
├── frontend/
├── docs/
├── infra/
├── scripts/
├── tests/
└── reference/
```

## Safety rule

AI diagnoses/proposes.
Deterministic policy controls financial execution.
Verification proves the resulting business state.
