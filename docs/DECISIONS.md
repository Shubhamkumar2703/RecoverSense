# RecoverSense — Architecture Decision Records

## ADR-001 — AI is not the financial authorization layer
**Status:** Accepted

AI diagnoses/proposes. Deterministic policy controls execution.

## ADR-002 — Verification is mandatory before recovery is counted
**Status:** Accepted

A provider write response is not sufficient evidence of business recovery.

## ADR-003 — One modular Spring Boot application
**Status:** Accepted

The dataset is demo-scale. A modular monolith reduces integration and deployment risk.

## ADR-004 — No RAG/vector DB
**Status:** Accepted

Payment context is structured transactional data, not an unstructured retrieval problem.

## ADR-005 — No multi-agent orchestration
**Status:** Accepted

One agent/diagnosis component plus deterministic services is clearer and safer for the MVP.

## ADR-006 — Simulation only where test infrastructure lacks the needed state transition
**Status:** Accepted

Simulator must be deterministic and visibly labeled.

## ADR-007 — Current feature-complete target is September 4, 2026
**Status:** Accepted

This supersedes the historical September 2 date in the earlier pre-build specification.

## ADR-008 — Project renamed to RecoverSense
**Status:** Accepted

The previous project name was RecoverFlow. RecoverSense is the current permanent name.

## ADR-009 — PostgreSQL exposed on host port 5433
**Status:** Accepted

A native (non-Docker) PostgreSQL Windows service already binds host port 5432, which caused Spring Boot to connect to the wrong instance and fail authentication. We do not stop or modify the native service. `infra/docker-compose.yml` and `spring.datasource.url` use port 5433.

## ADR-010 — Application runs on port 8081
**Status:** Accepted

Host port 8080 is already occupied by a native `httpd.exe` service. We do not stop or modify that service. `server.port=8081`.

## ADR-011 — JVM timezone pinned to Asia/Kolkata
**Status:** Accepted

The local JVM resolved the default timezone as the deprecated alias `Asia/Calcutta`, which PostgreSQL 17 rejects during the JDBC session `SET TimeZone`, breaking Flyway migrations. Fixed via `-Duser.timezone=Asia/Kolkata` in `backend/pom.xml` for both `maven-surefire-plugin` and `spring-boot-maven-plugin`.

## ADR-012 — Payment.status remains FAILED after successful recovery
**Status:** Accepted

`Payment.status` describes the original payment attempt; `RecoveryCase.status` describes the independent RecoverSense recovery lifecycle. A successfully recovered payment can therefore legitimately show `Payment.status = FAILED` alongside `RecoveryCase.status = RECOVERED`, `RecoveryAction.executionStatus = EXECUTED`, and `RecoveryAction.verificationStatus = VERIFIED` - this is intentional, not a bug. `Payment.status` is never rewritten to `SUCCEEDED` or any other value by the recovery pipeline.

Consequently, `RecoveryCase.status = RECOVERED` is the sole authoritative signal that recovery has completed for a payment. `RecoveryLifecycleService.processFailedPayment` explicitly rejects (`PaymentAlreadyRecoveredException`, surfaced as HTTP 409) starting a new recovery for a payment that already has a RECOVERED case, checked before any new `RecoveryCase`/`RecoveryDecision`/`RecoveryAction` is created and before any provider call.

At-risk payment definition (M1.22, `GET /api/dashboard/payments/at-risk`): `Payment.status == FAILED` AND no `RecoveryCase` exists for that payment with status `OPEN` or `RECOVERED`. A payment already recovered is excluded from "at risk" by this case-status check, not by `Payment.status`, which never changes.

## ADR-013 — Recovery is two-phase when execution needs an out-of-band human step (M1.25)
**Status:** Accepted

`RecoveryOrchestrationService.recover()` no longer auto-verifies immediately after a successful execution. It stops at `RecoveryOutcome.EXECUTED_AWAITING_VERIFICATION`; a separate `POST /api/recovery/cases/{recoveryCaseId}/verify` (`RecoveryOrchestrationService.verify`) performs the independent re-fetch and, only if it confirms payment, transitions the case to `RECOVERED`.

Reason: `RecoveryActionVerificationService`'s verification is one-shot and terminal (an action's `verificationStatus` moves from `UNVERIFIED` to `VERIFIED`/`FAILED` exactly once, by design - see its javadoc). A real Razorpay Payment Link requires a human to pay it through the hosted checkout before verification can honestly succeed; auto-verifying immediately after `execute()` (the pre-M1.25 behavior) would call the one-shot verifier before that payment happens, permanently burning it to `FAILED` and making the link unverifiable even after a genuine later payment. Splitting execution and verification into two calls - reusing `RecoveryActionExecutionService`/`RecoveryActionVerificationService`/`RecoveryLifecycleService` unchanged - lets a human act in between without weakening the one-shot verification guarantee itself.

`verify()` is idempotent: once an action's `verificationStatus` is already terminal (`VERIFIED` or `FAILED`), a repeated call returns that existing state without calling the verifier or the executor again - so repeated "Verify payment" clicks can never create a second Payment Link and can never re-attempt a verification that has already run.

This does not change verification semantics, does not weaken PolicyEngine, and does not touch `RecoveryActionExecutionService`/`RecoveryActionVerificationService`'s own guards - it only changes *when* `RecoveryOrchestrationService` calls verification.

## ADR-014 — Demo settlement evidence is simulated for one explicit payment id only (M1.25)
**Status:** Accepted

`DemoSettlementVerifier` (`@Profile("demo")`, `@Primary` among `SettlementVerifier` beans in that profile only) answers `SettlementState.NOT_SETTLED` for exactly `pay_demo_payment_link` and `UNKNOWN` for every other payment id, including under the demo profile. `UnavailableSettlementVerifier` remains the only `SettlementVerifier` outside the demo profile, unmodified.

Reason: the real Razorpay Payment Link demo path (see docs/RAZORPAY_INTEGRATION.md's M1.25 section) needs policy check P06 to pass for one specific demo payment, without ever making `UnavailableSettlementVerifier` return anything other than the honest `UNKNOWN` it always has, and without making P06 pass for arbitrary payments. This preserves RecoverSense's core safety property - unknown settlement evidence fails closed - everywhere except this one explicitly labeled, non-production seam.

## ADR-015 — REPEATED_FAILURE can be reached from failure-reason text, not only retry_count (M1.25)
**Status:** Accepted

`DiagnosisEngine` now also classifies `REPEATED_FAILURE` when `failureReason` contains both "repeated" and "fail" (case-insensitive), independent of `retry_count`. This is the same reason-text-matching mechanism already used for `MANDATE_INVALID`/`INSUFFICIENT_FUNDS` - not a special case for any specific payment id.

## ADR-016 — verification FAILED is no longer terminal; only VERIFIED is (M1.26)
**Status:** Accepted

`RecoveryActionVerificationService.attemptVerification` previously rejected any re-attempt once `verificationStatus` left `UNVERIFIED` (i.e. once it became `VERIFIED` *or* `FAILED`). It now rejects only when already `VERIFIED`; a `FAILED` action may be re-verified any number of times. `RecoveryOrchestrationService.verify()` was updated to match: it only short-circuits (skips calling the verifier) when the action is already `VERIFIED`.

Reason: against a real Razorpay Payment Link, `FAILED` from an independent re-fetch most often means "not paid yet", not "can never be verified" - the M1.25 one-shot design meant an operator who clicked Verify before the customer actually paid would permanently lose the ability to verify that same action again, even after a genuine later payment. This never weakens verification itself: every attempt, first or Nth, is still a fresh independent provider re-fetch (`RazorpayRecoveryActionVerifier`/`RecoveryActionVerifier` unchanged), never a cached or assumed result, and the case is transitioned to `RECOVERED` only from an attempt that genuinely returns `VERIFIED`. `VERIFIED` itself remains a true one-way door - no code path re-verifies or un-verifies a `VERIFIED` action.

## ADR-017 — real Razorpay payment ingestion is read-only and insert-only (M1.26)
**Status:** Accepted

`RazorpayPaymentSyncService` (`POST /api/dashboard/payments/sync`) fetches a small bounded page of the operator's real Razorpay Test Mode payments (`GET /v1/payments`), and inserts a local `Payment` row for each `status=failed` record not already present locally (matched by Razorpay's own payment id). It never updates an existing local `Payment` row, never creates a `RecoveryCase`, and never calls into `RecoveryOrchestrationService`.

Reason: a synced `Payment` row may already be referenced by an in-progress or completed `RecoveryCase` by the time a later sync runs; this class has no reliable way to know whether any field it might "refresh" would corrupt that state, so it never tries - the safest interpretation of "idempotent upsert" here is "insert if absent, otherwise leave completely untouched". Importing a payment is a data-visibility operation only, never permission to recover it - a human still has to click Recover.

No real customer PII (email/contact) is imported; each synced payment gets a synthetic `Customer` row identified only by the Razorpay payment id.

Reason: the only prior path to `REPEATED_FAILURE` was `retry_count >= 3`, which requires seeding prior `RecoveryAction` history (and therefore a pre-existing `RecoveryCase`) before the payment is even at risk. M1.25's demo seeder must not seed a `RecoveryCase` for its at-risk payments (so they remain visible via `GET /api/dashboard/payments/at-risk`), so a second, evidence-based path was needed. A failure reason that itself states the payment failed repeatedly is legitimate independent evidence for the category, exactly like every other DiagnosisEngine rule.
