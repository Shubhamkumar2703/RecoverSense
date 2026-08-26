# RecoverSense — Project Context

## Identity

**Name:** RecoverSense

Historical name: RecoverFlow. Do not use the historical name for new artifacts.

## Competition

Razorpay AI Buildathon 2026 — AI Revenue Recovery track.

## Developer constraint

Solo developer working alongside a full-time office job.

## Current target

Feature complete: **September 4, 2026**.

A historical source specification listed September 2. The latest project decision is September 4 and therefore supersedes that historical date.

## Product

Adaptive recovery decision engine for recurring payments.

## Product positioning

Existing recovery systems can optimize individual mechanisms such as retry timing, dunning and payment-update prompts. RecoverSense focuses on an explicit decision layer that evaluates failure state and chooses among recovery actions under merchant-defined financial policy, then verifies that recovery actually worked.

## Differentiation

1. Diagnosis before action.
2. Policy before execution.
3. Verification before counting recovery.
4. Explainable timestamped audit trail.

## Core architecture

Failure → Diagnosis → Strategy → Policy → Execution → Verification → Audit

## AI boundary

LLM is used for diagnosis/structured reasoning.
Deterministic services control financial actions.

## Primary hero case

Mandate revoked while subscription remains active.

## Demo-scale principle

One Spring Boot application, synchronous orchestration, PostgreSQL and a React dashboard.

## Source classification

Project documents can contain:
- verified facts
- design decisions
- assumptions
- synthetic data
- simulated behavior

Always label these correctly.
