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

`GET /api/dashboard/payments/at-risk` returns every `FAILED` payment that has no `OPEN` or `RECOVERED` recovery case — i.e. payments RecoverSense hasn't already started or finished recovering. The frontend's At-Risk Payments screen lists these with a **Recover** action that calls `POST /api/recovery/payments/{paymentId}/recover`, which runs diagnosis → strategy → policy → execution synchronously and returns the outcome.

**Two-phase for actions that need a human step (M1.25):** when execution requires something outside RecoverSense — a human paying a hosted Razorpay Payment Link — `recover()` stops at `EXECUTED_AWAITING_VERIFICATION` rather than auto-verifying. A separate `POST /api/recovery/cases/{recoveryCaseId}/verify` independently re-fetches provider state and only then transitions the case to `RECOVERED`. This never re-executes and never creates a second action — see `docs/DECISIONS.md` ADR-013.

**Verification can be retried until paid (M1.26):** an unpaid Payment Link genuinely means "not yet", not "never" — `POST /cases/{id}/verify` may be called again after a `FAILED` result, and each call is still an independent re-fetch, never cached or assumed. Only a genuinely `VERIFIED` result is terminal — see `docs/DECISIONS.md` ADR-016.

**Real Razorpay payment ingestion (M1.26):** `POST /api/dashboard/payments/sync` pulls the operator's real Razorpay Test Mode failed payments (`GET /v1/payments`, a small bounded page) into RecoverSense, insert-only and idempotent by Razorpay's own payment id — never updates an existing row, never creates a `RecoveryCase`, never triggers recovery. The frontend never calls Razorpay directly. See `docs/DECISIONS.md` ADR-017.

## 7. Re-recovery protection

If a payment already has a `RECOVERED` `RecoveryCase`, `POST /api/recovery/payments/{paymentId}/recover` returns **409 Conflict** before touching the database further or calling any provider. `Payment.status` intentionally never changes away from `FAILED` after recovery (see `docs/DECISIONS.md` ADR-012) — `RecoveryCase.status = RECOVERED` is the sole authoritative recovery signal, and it's what both the at-risk query and this guard check against.

## 8. Razorpay integration

`razorpay.key-id`/`razorpay.key-secret` (unset by default) gate a real `RestClient`-backed `RecoveryActionExecutor`/`RecoveryActionVerifier` pair that creates and checks Razorpay Payment Links in Test Mode (`RazorpayAutoConfiguration`). Without credentials configured, every strategy's execution falls through to `NotImplementedRecoveryActionExecutor`, which honestly reports `EXECUTION_UNAVAILABLE` rather than fabricating a result.

**Independent of Razorpay credentials:** the "payment already settled elsewhere" policy check (`not_already_settled_elsewhere`) is backed only by `UnavailableSettlementVerifier` outside the `demo` profile, which always answers UNKNOWN — no real settlement source is wired in production. Because `PolicyEngine` fails closed on unknown evidence, **every recovery attempt outside the demo profile evaluates to `BLOCKED`** at the policy stage, regardless of Razorpay credentials. This is intentional, documented fail-closed behavior, not a bug.

**Demo profile only (M1.25/M1.26):** `DemoSettlementVerifier` supplies one explicit, clearly-labeled SIMULATED `NOT_SETTLED` answer for the seeded demo payment id (`pay_demo_payment_link`), so that payment can legitimately reach `ALLOWED` and exercise a real Razorpay Test Mode Payment Link end to end. An operator can additionally designate one of their own real synced payments the same way via `demo.settlement.extra-not-settled-payment-ids` (still demo-profile only, still explicit per id). Every other payment, including under the demo profile, is still governed by the same fail-closed rule as production — see `docs/RAZORPAY_INTEGRATION.md` and [Known limitations](#17-known-limitations).

**A real synced payment typically still blocks** (M1.26): a plain `GET /v1/payments` record carries no subscription-state evidence, so `Payment.subscriptionStatus` is left `null` rather than guessed — `subscription_state_valid` legitimately fails, on top of P06 above. This is not a defect: it's the same fail-closed principle demonstrated with real provider data, and it's why the demo keeps one deliberately-seeded payment as the guaranteed successful path rather than depending on an arbitrary real payment reaching `ALLOWED`.

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

See [`docs/DEMO.md`](docs/DEMO.md) for the full judge-facing script, including the real-Razorpay-batch path. Scenarios:

**No credentials needed** — `pay_demo_mandate_revoked`: Recover → diagnosis `MANDATE_INVALID` → strategy `REACQUIRE_MANDATE` → policy `BLOCKED` (no settlement source wired for this payment) → result card and audit trail show exactly which check failed and why. This demonstrates RecoverSense refusing to act on payment state it cannot verify, rather than guessing.

**Real Razorpay Test Mode needed** — `pay_demo_payment_link`: Recover → diagnosis `REPEATED_FAILURE` (real Claude, if `claude.api-key` is configured) → strategy `PAYMENT_LINK` → policy `ALLOWED` (this one payment's settlement evidence is simulated, see §8) → a **real** Payment Link is created (`EXECUTED_AWAITING_VERIFICATION`, never "Recovered" yet) → open the link, pay it in Test Mode → click **Verify payment** (retry as needed until it's actually paid) → independent re-fetch confirms it → `VERIFIED` / case `RECOVERED`. Clicking Recover again on either payment (once recovered) demonstrates the M1.22 409 re-recovery guard with no duplicate case/action/Payment Link created.

**Real batch (M1.26)** — click **Sync Razorpay Test Mode** on the At-Risk Payments screen to pull the operator's own real Razorpay Test Mode failed payments alongside the two seeded ones. Real synced payments are labeled `REAL` (vs `DEMO`) and typically block at policy honestly (see §8) unless explicitly opted in via `demo.settlement.extra-not-settled-payment-ids`.

### Reproducible demo data

`DemoDataSeeder` (`backend/src/main/java/com/recoversense/demo/DemoDataSeeder.java`) inserts the two deterministic `FAILED` payments above, each with no `RecoveryCase`, so both appear in At-Risk Payments. It only runs under the `demo` Spring profile and is idempotent:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=demo"
```

It never runs under the default profile, never touches an existing payment, and creates nothing beyond those two seed rows.

## 16. Real vs simulated

| Capability | Status |
|---|---|
| Diagnosis engine (deterministic classifier) | ✅ Real |
| Diagnosis via Claude | ✅ Real, requires `claude.api-key`; structured (json_schema) response, validated, fails honestly (never a fabricated fallback) if malformed/unavailable |
| Strategy routing | ✅ Real, deterministic — never chosen by Claude |
| Policy engine (7 checks) | ✅ Real |
| Recovery lifecycle / case state machine | ✅ Real |
| Re-recovery duplicate guard (409) | ✅ Real |
| Audit trail | ✅ Real |
| Razorpay payment ingestion (`GET /v1/payments`) | ✅ Real Test Mode integration (M1.26), requires `razorpay.key-id`/`key-secret`; read-only, insert-only, bounded page |
| Razorpay Payment Link creation | ✅ Real Test Mode integration, requires `razorpay.key-id`/`key-secret` |
| Razorpay Payment Link verification | ✅ Real Test Mode integration, independent re-fetch, retriable until paid (M1.26) |
| Two-phase execution → human payment → verification | ✅ Real (M1.25/M1.26): `POST /recover` executes, `POST /cases/{id}/verify` independently confirms, retriable |
| Settlement/"already settled elsewhere" check | ⚠️ Always UNKNOWN in production; fails closed by design. Demo profile: SIMULATED `NOT_SETTLED` for explicitly configured payment id(s) only, UNKNOWN for every other |
| Real synced payment reaching policy `ALLOWED` | ⚠️ Not automatic — no subscription-state evidence in a plain payment record; requires explicit demo-profile opt-in (§8) |
| `WAIT_RETRY` execution | ❌ Not implemented (reports `EXECUTION_UNAVAILABLE` honestly) |
| `REACQUIRE_MANDATE` execution | ❌ Not implemented (reports `EXECUTION_UNAVAILABLE` honestly) |
| Dashboard metrics (including batch counts) | ✅ Real, derived from persisted data — never hardcoded |
| Production payment processing | ❌ Out of scope |

## 17. Known limitations

- **Every recovery attempt outside the demo profile evaluates to `BLOCKED`** at the policy stage, because the settlement check has no real backing source in production and fails closed on UNKNOWN. Reaching `EXECUTED`/`VERIFIED`/`RECOVERED` against a real provider requires either the demo profile's explicitly-simulated settlement input (§8) or a test that wires `SimulatedSettlementVerifier` directly. This is intentional: closing it in production needs a real settlement source, out of scope for this milestone.
- Only `PAYMENT_LINK` (via `REPEATED_FAILURE` diagnosis) has a real execution path at all, and only with Razorpay credentials configured; `WAIT_RETRY` and `REACQUIRE_MANDATE` always report execution unavailable.
- A real synced payment has no subscription-state evidence, so it legitimately fails `subscription_state_valid` and blocks by default (§8) — the demo's guaranteed successful path stays the deliberately-seeded payment (or an operator-designated real one) rather than an arbitrary synced payment.
- The real Payment Link URL is only ever available in the same response that created it (never persisted — see `RecoveryAction.providerUrl`); if the browser is reloaded between execution and verification, the operator loses the ability to re-open the link, though verification itself is unaffected (it's keyed by the case, not the URL, and can still be retried).
- Razorpay payment sync fetches a small bounded page (`count=20`) of the most recent payments, not the full account history — a failed payment older than the most recent 20 payments won't be found by sync.
- No scheduler/webhook listener — recovery (and sync) is triggered manually (via the API/UI), not automatically on payment failure.
- Frontend has five views (Overview, At-Risk Payments, Recovery Cases, Audit Trail, Integrations); Policy Rules and Settings were deliberately not built (no real backend behind them — see M1.24).

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
