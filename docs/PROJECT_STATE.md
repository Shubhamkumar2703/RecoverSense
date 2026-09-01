# RecoverSense — Project State

> Last updated: 2026-09-01 (M1.23)

## Current phase
FINALIZATION / DEMO-READINESS (M1.23) — feature pipeline complete, hardening for submission

## DONE
- Full recovery pipeline implemented end to end: Diagnosis → Strategy → Policy → Action → Execution → Verification → Case Lifecycle → Audit
- Deterministic diagnosis engine (`DiagnosisEngine`/`SimulatedDiagnosisProvider`) and Claude-backed `DiagnosisProvider`, gated by `claude.api-key`
- Deterministic 7-check policy engine (`PolicyEngine`), fail-closed on unknown evidence
- Recovery lifecycle/orchestration (`RecoveryLifecycleService`, `RecoveryOrchestrationService`), execution and verification boundaries with honest "unavailable" reporting when no real provider is wired
- Razorpay Test Mode integration for Payment Link creation/verification, gated by `razorpay.key-id`/`key-secret` (`RazorpayAutoConfiguration`)
- `RecoveryController` (`POST /api/recovery/payments/{id}/recover`) and `DashboardController` (`GET /api/dashboard/metrics`, `/cases/{id}/audit`, `/payments/at-risk`)
- Re-recovery duplicate guard: a payment with an existing `RECOVERED` case is rejected with 409 before any new case/action/provider call (M1.22)
- Frontend dashboard (Overview + At-Risk Payments views, recovery result card, audit trail, policy/execution/verification status pills)
- Deterministic demo data seeder (`DemoDataSeeder`, `demo` profile only) — M1.23
- README rewritten to describe the actual current implementation, including an honest "real vs simulated" table — M1.23

## IN PROGRESS
- None — M1.23 finalization checkpoint

## NEXT
1. Final regression pass (backend `mvnw test`, frontend `npm run build`/`npm run lint`)
2. Demo rehearsal against the seeded scenario
3. Submission

## BLOCKED
None currently.

## PARKED
- microservices
- Kafka
- Redis
- Kubernetes
- LangChain/LangGraph
- n8n
- vector DB/RAG
- second gateway
- production UPI/eNACH authentication
- scheduler / webhook-triggered recovery (recovery is API/UI-triggered only)
- a real settlement source for the `not_already_settled_elsewhere` policy check

## RISKS
- **The settlement check always evaluates to UNKNOWN in the running application** (`UnavailableSettlementVerifier` is the only wired `SettlementVerifier`), so `PolicyEngine` blocks every real recovery attempt at the policy stage. The `EXECUTED`/`VERIFIED`/`RECOVERED` path is currently only exercised by tests that construct their own `SimulatedSettlementVerifier` directly, not through the running app. Demo scripts must account for this — see `docs/DEMO.md`.
- demo stability (single manual rehearsal pass, no automated UI test)
- AI diagnosis reliability when Claude is enabled (untested against live API in this milestone)

## OPEN QUESTIONS
- Whether a real settlement source (or an explicitly-labeled simulation seam) should be added post-submission to let the full pipeline reach `RECOVERED` outside of tests
- Final deployment target for submission (if any beyond local demo)

## Environment checkpoint

| Component | Value |
|---|---|
| Java | 21 (Temurin) |
| Maven | Wrapper 3.9.16 |
| Spring Boot | 4.1.1 |
| PostgreSQL | 17, container `recoversense-postgres`, host port `5433` |
| Application port | `8081` |
| JVM timezone | `Asia/Kolkata` |
| Flyway | enabled, connection verified |
| Actuator | `/actuator/health`, `/actuator/info` |

Rationale for the non-default ports/timezone is recorded in [DECISIONS.md](DECISIONS.md) (ADR-009, ADR-010, ADR-011).

## Rule

M1.23 is the final engineering milestone. No new architecture, providers, or abstractions — see `CLAUDE.md` and the M1.23 scope. Remaining work is documentation, demo reproducibility, and verification.
