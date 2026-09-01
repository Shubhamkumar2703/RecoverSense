# RecoverSense

**Adaptive revenue recovery for recurring payments.**

Built for the Razorpay AI Buildathon 2026 — AI Revenue Recovery track.

## 1. What is RecoverSense?

RecoverSense is a decision engine for failed recurring payments. When a payment fails, it doesn't just retry — it diagnoses *why* the payment failed, selects a recovery strategy for that specific failure, checks the action against deterministic merchant policy, executes it, and independently re-fetches state to verify the money actually came back before counting it as recovered.

## 2. Problem being solved

Most dunning/retry systems treat every failure the same way: wait, then retry. A mandate revocation is not the same problem as insufficient funds, and blind retries waste attempts, annoy customers, and don't distinguish "will probably recover on its own" from "needs the merchant to act." RecoverSense treats recovery as a diagnosis-and-decision problem, not a timing problem.

## 3. Core pipeline

```
Diagnosis → Strategy → Policy → Action → Execution → Verification → Case Lifecycle → Audit
```

- **Diagnosis** — classify why the payment failed (Claude when configured, a deterministic keyword classifier otherwise)
- **Strategy** — a fixed, deterministic diagnosis → strategy mapping (never chosen by the AI)
- **Policy** — 7 deterministic checks; any failed required check blocks execution
- **Action** — a `RecoveryAction` row is created only if policy allows
- **Execution** — attempt the action through a provider adapter
- **Verification** — re-fetch actual state and compare to expected, independent of the execution call's own success signal
- **Case Lifecycle** — the `RecoveryCase` only reaches `RECOVERED` after verification passes
- **Audit** — every step writes an immutable `AuditEvent`

## 4. Architecture

One Spring Boot 4 application (Java 21), PostgreSQL via Flyway-managed schema, a Vite/React frontend. No microservices, queue, cache, or scheduler — see [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and [`docs/DECISIONS.md`](docs/DECISIONS.md) for the reasoning.

Backend packages: `diagnosis`, `policy`, `service` (lifecycle/orchestration), `razorpay`, `claude`, `settlement`, `dashboard`, `recovery` (HTTP), `domain`, `repository`.

## 5. Safety principles

- **AI diagnoses, it does not authorize.** The LLM (or its deterministic stand-in) only classifies a failure. Strategy is derived by a fixed, non-AI mapping (`StrategyRouter`); policy is evaluated by a separate deterministic engine (`PolicyEngine`) that never takes AI output as an input.
- **Policy fails closed.** Any check with unknown/missing evidence is treated as FAIL, not skipped or assumed-pass.
- **Execution is not recovery.** A provider call returning success only moves an action to `EXECUTED`. The case only reaches `RECOVERED` after a separate, independent verification step confirms it.
- **No silent duplicate recovery.** A payment that already has a `RECOVERED` case is rejected (HTTP 409) before any new case, decision, or action — and therefore before any provider call — is created (M1.22).

## 6. At-Risk Payments flow

`GET /api/dashboard/payments/at-risk` returns every `FAILED` payment that has no `OPEN` or `RECOVERED` recovery case — i.e. payments RecoverSense hasn't already started or finished recovering. The frontend's At-Risk Payments screen lists these with a **Recover** action that calls `POST /api/recovery/payments/{paymentId}/recover`, which runs the full pipeline synchronously and returns the outcome.

## 7. Re-recovery protection

If a payment already has a `RECOVERED` `RecoveryCase`, `POST /api/recovery/payments/{paymentId}/recover` returns **409 Conflict** before touching the database further or calling any provider. `Payment.status` intentionally never changes away from `FAILED` after recovery (see `docs/DECISIONS.md` ADR-012) — `RecoveryCase.status = RECOVERED` is the sole authoritative recovery signal, and it's what both the at-risk query and this guard check against.

## 8. Razorpay integration

`razorpay.key-id`/`razorpay.key-secret` (unset by default) gate a real `RestClient`-backed `RecoveryActionExecutor`/`RecoveryActionVerifier` pair that creates and checks Razorpay Payment Links in Test Mode (`RazorpayAutoConfiguration`). Without credentials configured, every strategy's execution falls through to `NotImplementedRecoveryActionExecutor`, which honestly reports `EXECUTION_UNAVAILABLE` rather than fabricating a result.

**Independent of Razorpay credentials:** the "payment already settled elsewhere" policy check (`not_already_settled_elsewhere`) is backed only by `UnavailableSettlementVerifier`, which always answers UNKNOWN — no real settlement source is wired yet. Because `PolicyEngine` fails closed on unknown evidence, this means **every recovery attempt against the running application today evaluates to `BLOCKED`** at the policy stage, regardless of Razorpay credentials. This is intentional, documented fail-closed behavior, not a bug — see [Known limitations](#15-known-limitations).

## 9. Claude integration

`claude.api-key` (unset by default) gates a real Claude-backed `DiagnosisProvider` (`ClaudeAutoConfiguration`). Without it, `SimulatedDiagnosisProvider` — a deterministic keyword classifier wrapping the same taxonomy — is always registered instead, tagged `DiagnosisSource.SIMULATED` so a reader can never mistake it for a real Claude result. The rest of the pipeline (strategy, policy, execution, verification) behaves identically either way; only the diagnosis source changes.

## 10. Dashboard

`GET /api/dashboard/metrics` and `GET /api/dashboard/cases/{id}/audit` back an Overview screen (revenue at risk, recovered revenue, recovery rate, verified actions, policy blocks, recent cases, strategy mix, audit trail) and an At-Risk Payments screen. All figures come from persisted `RecoveryCase`/`RecoveryDecision`/`RecoveryAction`/`AuditEvent` rows — never hardcoded or synthetic.

## 11. Local setup

Prerequisites: Java 21, Maven Wrapper (bundled), Docker (for PostgreSQL), Node.js for the frontend.

```powershell
# start PostgreSQL 17 (host port 5433 — see docs/DECISIONS.md ADR-009)
docker start recoversense-postgres
# or, first time: docker compose -f infra/docker-compose.yml up -d
```

## 12. Running backend

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

Serves on `http://localhost:8081`. Health check: `/actuator/health`.

## 13. Running frontend

```powershell
cd frontend
npm install
npm run dev
```

Serves on `http://localhost:5173` by default; talks to the backend at `http://localhost:8081` (override with `VITE_API_BASE_URL`).

## 14. Running tests

```powershell
cd backend
.\mvnw.cmd test
```

Requires the PostgreSQL container running (Flyway migrations run against it). Real-Razorpay/Claude-credentialed tests are skipped automatically when the relevant credentials are not configured — see [`docs/TEST_PLAN.md`](docs/TEST_PLAN.md).

```powershell
cd frontend
npm run build
npm run lint
```

## 15. Demo walkthrough

See [`docs/DEMO.md`](docs/DEMO.md) for the judge-facing script. Short version, reproducible without any credentials:

1. Start Postgres, backend (with `--spring.profiles.active=demo`, see below), and frontend.
2. Open **At-Risk Payments** — a seeded `FAILED` payment (mandate revoked, subscription still active) is listed.
3. Click **Recover**. The pipeline runs synchronously: diagnosis (`MANDATE_INVALID`), strategy (`REACQUIRE_MANDATE`), policy evaluation, execution attempt, verification attempt.
4. With no settlement source wired (the default), the policy stage blocks the action — the result card and audit trail show exactly which check failed and why, which is itself the safety property worth demonstrating: RecoverSense refuses to act on payment state it cannot verify, rather than guessing.
5. Click **Recover** again on an already-recovered payment (from a Razorpay Test Mode run) to see the 409 re-recovery guard, then check the audit trail and at-risk list to confirm no duplicate case/action/provider call occurred.

### Reproducible demo data

`DemoDataSeeder` (`backend/src/main/java/com/recoversense/demo/DemoDataSeeder.java`) inserts exactly one deterministic `FAILED` payment (`pay_demo_mandate_revoked`) with no `RecoveryCase`, so it appears in At-Risk Payments. It only runs under the `demo` Spring profile and is idempotent:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=demo"
```

It never runs under the default profile, never touches an existing payment, and creates nothing beyond the one seed row.

## 16. Real vs simulated

| Capability | Status |
|---|---|
| Diagnosis engine (deterministic classifier) | ✅ Real |
| Diagnosis via Claude | ✅ Integrated, requires `claude.api-key` |
| Strategy routing | ✅ Real |
| Policy engine (7 checks) | ✅ Real |
| Recovery lifecycle / case state machine | ✅ Real |
| Re-recovery duplicate guard (409) | ✅ Real |
| Audit trail | ✅ Real |
| Razorpay Payment Link creation | ✅ Real Test Mode integration, requires `razorpay.key-id`/`key-secret` |
| Razorpay Payment Link verification | ✅ Real Test Mode integration |
| Settlement/"already settled elsewhere" check | ⚠️ Always UNKNOWN — no real source wired; fails closed by design |
| `WAIT_RETRY` execution | ❌ Not implemented (reports `EXECUTION_UNAVAILABLE` honestly) |
| `REACQUIRE_MANDATE` execution | ❌ Not implemented (reports `EXECUTION_UNAVAILABLE` honestly) |
| Dashboard metrics | ✅ Real, derived from persisted data |
| Production payment processing | ❌ Out of scope |

## 17. Known limitations

- **Every recovery attempt against a normally-running instance evaluates to `BLOCKED`** at the policy stage, because the settlement check has no real backing source and fails closed on UNKNOWN. The full `EXECUTED` → `VERIFIED` → `RECOVERED` path is only exercised in tests, which wire a `SimulatedSettlementVerifier` directly — never through the actual running application. This is a real, current gap, not a demo artifact: closing it needs a real settlement source (or an explicit, documented simulation seam), which is out of scope for this milestone.
- Only `PAYMENT_LINK` (via `REPEATED_FAILURE` diagnosis) has a real execution path at all, and only with Razorpay credentials configured; `WAIT_RETRY` and `REACQUIRE_MANDATE` always report execution unavailable.
- No scheduler/webhook listener — recovery is triggered manually (via the API/UI), not automatically on payment failure.
- Frontend has two views (Overview, At-Risk Payments); the sidebar's other links (Recovery cases, Audit trail, Metrics, Policy rules, Integrations, Settings) are placeholders, not implemented screens.

## 18. Final project status

Core pipeline (diagnosis → strategy → policy → action → execution → verification → lifecycle → audit), the dashboard, At-Risk Payments, and the re-recovery guard are implemented and covered by the backend test suite (`.\mvnw.cmd test` in `backend/`). See [`docs/PROJECT_STATE.md`](docs/PROJECT_STATE.md) for the current milestone log.

## Repository

```text
recoversense/
├── backend/    # Spring Boot application
├── frontend/   # Vite/React dashboard
├── docs/       # specs, decisions, project state
├── infra/      # docker-compose for local PostgreSQL
├── scripts/    # (reserved; demo data is seeded in-process — see §15)
└── tests/
```

## Read first

1. `CLAUDE.md`
2. `docs/PRODUCT_SPEC.md`
3. `docs/ARCHITECTURE.md`
4. `docs/POLICY_SPEC.md`
5. `docs/VERIFICATION_SPEC.md`
6. `docs/DECISIONS.md`
7. `docs/PROJECT_STATE.md`
