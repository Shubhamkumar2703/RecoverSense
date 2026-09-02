# RecoverSense — Demo Script

## M1.27 — active diagnosis provider for this demo

**Deterministic Demo** diagnosis is the active provider for the final demo (no `CLAUDE_API_KEY` configured). Claude is not called, not required, and not removed - `ClaudeDiagnosisProvider`/`ClaudeAutoConfiguration` remain intact and activate automatically if a real key is ever configured (see README §9). The UI labels every diagnosis's provider truthfully ("Deterministic Demo" or "Claude") from `diagnosisSource`, sourced from what `DiagnosisService` actually recorded - never hardcoded, never claimed without having actually happened.

## 2-minute story

### 1. Start with the failure
A recurring payment failed.

### 2. Diagnose
RecoverSense classifies the failure and shows confidence/evidence.

### 3. Decide
The deterministic router chooses the appropriate strategy.

### 4. Prove safety
Show every policy check.

### 5. Execute
Execute the supported test-mode action or clearly labeled simulator.

### 6. Verify
Re-fetch the business state and show expected vs actual.

### 7. Audit
Show the complete timeline.

### 8. Metrics
Show metrics derived from the run.

## Hero wording

> The payment didn't just fail. RecoverSense understood why, selected the right recovery path, checked whether it was safe, executed it, and proved whether it worked.

## Demo failure fallback

If a live provider call fails:
- show the failure
- show the deterministic error handling
- use the simulator only where the spec explicitly permits it
- never fake a provider success

## Reproducing this demo

Run the backend with `--spring.profiles.active=demo` (see README §15) to seed two deterministic `FAILED` payments. No Razorpay or Claude credentials are required for the mandate-revoked scenario below.

**Scenario A — mandate revoked (`pay_demo_mandate_revoked`), no credentials needed:** the "already settled elsewhere" policy check has no real settlement source wired for this payment, so it evaluates to UNKNOWN and `PolicyEngine` fails closed to `BLOCKED`. Frame this honestly as the safety property it demonstrates ("RecoverSense won't act on unverifiable state") rather than a bug.

**Scenario B — real Razorpay Payment Link (`pay_demo_payment_link`), see below:** with Razorpay Test Mode (and optionally Claude) credentials configured, this second seeded payment reaches a genuinely `RECOVERED` outcome through a real financial action.

**Scenario C — real synced batch (M1.26):** click **Sync Razorpay Test Mode** to pull the operator's own real Razorpay Test Mode failed payments alongside the two seeded ones - see below.

## Final demo path — real Razorpay Test Mode Payment Link, real Claude, real batch (M1.25/M1.26)

This is the primary judge-facing walkthrough: a real financial action, not just a policy decision.

1. Start PostgreSQL (`docker start recoversense-postgres`).
2. Start the backend with the demo profile **and** real credentials: `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=demo"` with `RAZORPAY_KEY_ID`/`RAZORPAY_KEY_SECRET` (and, optionally, `CLAUDE_API_KEY` for real diagnosis) set in the environment (never in source - see `.env.example`).
3. Start the frontend (`npm run dev`).
4. Open **At-Risk Payments** - `pay_demo_payment_link` (₹1,000, "Repeated payment failure...") is listed alongside the mandate-revoked payment, each tagged `DEMO`.
5. Optional: click **Sync Razorpay Test Mode** to also pull in the operator's own real Razorpay Test Mode failed payments, tagged `REAL`. These typically show `BLOCKED` when recovered (no subscription-state evidence in a plain payment record - see `docs/RAZORPAY_INTEGRATION.md`'s M1.26 section) - a legitimate part of the demo, not a bug.
6. Click **Recover** on `pay_demo_payment_link`. The pipeline runs: diagnosis (`REPEATED_FAILURE` - real Claude if `claude.api-key` is set, otherwise the deterministic classifier reaching the same category from the failure-reason text, see `docs/DECISIONS.md` ADR-015), strategy (`PAYMENT_LINK`), policy (`ALLOWED` - this one payment's settlement evidence is explicitly SIMULATED, see `docs/RAZORPAY_INTEGRATION.md`), execution (a **real** Razorpay Test Mode Payment Link is created). The result card shows `EXECUTED_AWAITING_VERIFICATION` and an **Open payment link** button - never "Recovered".
7. Click **Open payment link** to open the real hosted Razorpay Test Mode checkout in a new tab.
8. Complete the payment using a Razorpay Test Mode success method.
9. Back in RecoverSense, click **Verify payment**. This independently re-fetches the real Payment Link from Razorpay - it does not trust the execution step's own response. If clicked before the payment actually completed, it honestly reports not-yet-verified and can simply be clicked again (M1.26 - verification is retriable, not one-shot).
10. The result updates to `verificationStatus: VERIFIED`, `outcome: RECOVERED`, and the case status becomes `RECOVERED`.
11. Open **Audit Trail** (or the **Recovery Cases** view, select the case) to show the complete lifecycle: `RECOVERY_CASE_OPENED`, `RECOVERY_DECISION_RECORDED`, `POLICY_EVALUATED`, `ACTION_CREATED`, `ACTION_EXECUTION_ATTEMPTED`, `ACTION_VERIFICATION_ATTEMPTED`, `CASE_STATUS_CHANGED`.
12. Return to **Overview** - "Recovered", "Recovery rate", "Verified actions" and the new "Failed payments" / "Awaiting verification" / "Execution issues" tiles all reflect this real outcome, computed from persisted data.
13. Click **Recover** again on the same (now recovered) payment to show the M1.22 409 re-recovery guard, and confirm in Razorpay Test Mode that only one Payment Link exists for it.

**What's real here:** the Razorpay payment ingestion, the diagnosis (when Claude is configured), the Payment Link creation, the hosted checkout, the actual Test Mode payment, the independent re-fetch (retriable), the `VERIFIED` result, the `RECOVERED` case status, and every dashboard metric. **What's simulated:** only the settlement-evidence input for the explicitly configured demo payment id(s) (`DemoSettlementVerifier`) - never the financial action itself, and never applied to an arbitrary real synced payment.
