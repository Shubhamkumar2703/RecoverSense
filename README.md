# RecoverSense

### Adaptive Revenue Recovery for Recurring Payments

**Built for the Razorpay AI Buildathon 2026 — AI Revenue Recovery Track**

> **RecoverSense is a sixth sense for failed payments — it understands why a payment failed, chooses the right recovery path, checks whether that action is allowed, executes it safely, and verifies that the money was actually recovered.**

---

## 🚀 The Problem

Most recurring-payment recovery systems treat every failure the same way:

```text
Payment Failed
      ↓
Wait
      ↓
Retry
      ↓
Retry Again
````

But every payment failure is not the same.

A **revoked mandate**, **insufficient funds**, **temporary failure**, **card issue**, **repeated failure**, or **cancelled subscription** can require completely different recovery actions.

Blind retries can:

* Waste retry attempts
* Annoy customers
* Repeat actions that cannot succeed
* Miss better recovery opportunities
* Create duplicate recovery attempts
* Incorrectly count an attempted payment as recovered

### RecoverSense changes the question from:

> "When should we retry?"

to:

> **"Why did this payment fail, what should we do, are we allowed to do it, and did it actually work?"**

---

# 🧠 What is RecoverSense?

RecoverSense is an **adaptive revenue recovery decision engine for recurring payments**.

When a payment fails, RecoverSense follows a controlled decision pipeline:

```text
FAILED PAYMENT
      │
      ▼
┌─────────────┐
│  DIAGNOSIS  │  ← Why did it fail?
└──────┬──────┘
       ▼
┌─────────────┐
│  STRATEGY   │  ← What should we try?
└──────┬──────┘
       ▼
┌─────────────┐
│   POLICY    │  ← Are we allowed to?
└──────┬──────┘
       ▼
┌─────────────┐
│   ACTION    │
└──────┬──────┘
       ▼
┌─────────────┐
│  EXECUTION  │  ← Perform the action
└──────┬──────┘
       ▼
┌─────────────┐
│ VERIFICATION│  ← Did recovery actually happen?
└──────┬──────┘
       ▼
┌─────────────┐
│ CASE STATUS │
└──────┬──────┘
       ▼
┌─────────────┐
│    AUDIT    │
└─────────────┘
```

The key idea:

> **Execution is not recovery. Verification is what makes recovery real.**

---

# 🤖 AI-Powered Diagnosis

RecoverSense uses **Claude** specifically for failure diagnosis.

The AI receives relevant payment failure context such as:

```json
{
  "source": "subscription",
  "step": "payment_authorization",
  "reason": "mandate_revoked"
}
```

Claude returns a structured diagnosis:

```json
{
  "category": "MANDATE_INVALID",
  "confidence": 0.94,
  "reasoning": "The recurring mandate is no longer valid."
}
```

The diagnosis then enters the deterministic RecoverSense pipeline.

```text
Payment Failure
      │
      ▼
┌─────────────────┐
│     Claude      │
│    Diagnosis    │
└────────┬────────┘
         │
         ▼
   DiagnosisResult
         │
         ▼
┌─────────────────┐
│ StrategyRouter  │
│  Deterministic  │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│   PolicyEngine  │
│  Deterministic  │
└────────┬────────┘
         │
         ▼
      Execution
         │
         ▼
     Verification
```

## Why doesn't AI control execution?

Because financial recovery requires deterministic authorization and predictable behavior.

Claude does **not**:

* Authorize payments
* Choose merchant policy
* Bypass policy checks
* Execute financial actions
* Decide whether recovery is successful

### Core principle

> **AI diagnoses. Deterministic systems authorize. External systems execute. RecoverSense verifies.**

---

# 🔀 Adaptive Recovery Strategies

Different failure diagnoses lead to different recovery strategies.

| Failure Diagnosis             | Recovery Strategy  |
| ----------------------------- | ------------------ |
| `INSUFFICIENT_FUNDS`          | Wait + Retry       |
| `TEMPORARY_FAILURE`           | Wait + Retry       |
| `MANDATE_INVALID`             | Re-acquire Mandate |
| `CARD_ISSUE`                  | Payment Link       |
| `REPEATED_FAILURE`            | Payment Link       |
| `HIGH_RISK / COMPLEX_FAILURE` | Escalate           |
| `SUBSCRIPTION_CANCELLED`      | Stop               |

The mapping is deterministic.

Claude provides the diagnosis.

`StrategyRouter` determines the corresponding strategy.

---

# 🛡️ Policy Firewall

A recovery recommendation is **not automatically permission to execute**.

Before any recovery action is created, RecoverSense evaluates deterministic merchant policies.

## Seven Policy Checks

```text
1. Retry limit
2. Subscription state
3. Customer active status
4. Duplicate recovery protection
5. Amount limit
6. Already settled elsewhere
7. State / evidence freshness
```

Every required check must have valid evidence.

## Fail-Closed Behavior

```text
Evidence available + valid
          │
          ▼
         PASS


Evidence unavailable / unknown
          │
          ▼
         FAIL
          │
          ▼
        BLOCK
```

RecoverSense never assumes that an unknown condition is safe.

### Example

```text
Amount: ₹75,000
Policy Limit: ₹50,000

        ↓

Amount Limit Check: FAIL

        ↓

POLICY BLOCKED

        ↓

No Action Created
No Provider Call
```

---

# 💳 Execution

Only after policy approval does RecoverSense create a `RecoveryAction`.

The action is executed through a provider adapter.

Currently, the real Razorpay Test Mode execution path supports:

```text
PAYMENT_LINK
```

The flow is:

```text
Policy Allowed
      ↓
Create RecoveryAction
      ↓
Create Razorpay Payment Link
      ↓
EXECUTED_AWAITING_VERIFICATION
      ↓
Customer Pays
      ↓
Independent Verification
```

Other strategies such as:

```text
WAIT_RETRY
REACQUIRE_MANDATE
```

currently report:

```text
EXECUTION_UNAVAILABLE
```

rather than pretending that an action happened.

> **RecoverSense never fabricates a successful recovery.**

---

# 🔍 Independent Verification

One of the most important design principles of RecoverSense is:

> **A successful provider API call does not automatically mean money was recovered.**

Execution and recovery are separate states.

```text
ACTION CREATED
      ↓
POLICY ALLOWED
      ↓
ACTION EXECUTED
      ↓
AWAITING VERIFICATION
      ↓
Re-fetch provider state
      ↓
Compare actual state
with expected state
      ↓
   ┌───────┴───────┐
   │               │
   ▼               ▼
 VERIFIED        FAILED
   │
   ▼
RECOVERED
```

For a Payment Link:

```text
Create Payment Link
        ↓
EXECUTED_AWAITING_VERIFICATION
        ↓
Customer Pays
        ↓
Verify Payment
        ↓
Fresh Razorpay Re-fetch
        ↓
Payment Confirmed
        ↓
VERIFIED
        ↓
RecoveryCase = RECOVERED
```

Verification can be retried if the customer has not paid yet.

Every verification performs a fresh provider re-fetch.

---

# 🔒 Duplicate Recovery Protection

RecoverSense prevents duplicate recovery attempts.

If a payment already has:

```text
RecoveryCase.status = RECOVERED
```

another recovery attempt returns:

```text
HTTP 409 CONFLICT
```

before creating:

* Another recovery case
* Another decision
* Another action
* Another provider call

The authoritative recovery signal is:

```text
RecoveryCase = RECOVERED
```

`Payment.status` intentionally remains `FAILED`.

---

# 🧾 Complete Audit Trail

Every important recovery transition creates an immutable `AuditEvent`.

### Successful Flow

```text
FAILURE_DETECTED
        ↓
AI_DIAGNOSIS_COMPLETE
        ↓
STRATEGY_SELECTED
        ↓
POLICY_CHECK
        ↓
POLICY_ALLOWED
        ↓
ACTION_EXECUTED
        ↓
BUSINESS_STATE_VERIFIED
        ↓
RECOVERY_COMPLETED
```

### Blocked Flow

```text
FAILURE_DETECTED
        ↓
AI_DIAGNOSIS_COMPLETE
        ↓
STRATEGY_SELECTED
        ↓
POLICY_CHECK
        ↓
POLICY_BLOCKED
```

This makes every recovery decision explainable and auditable.

---

# 🏗️ System Architecture

```text
                         ┌─────────────────────┐
                         │      RAZORPAY       │
                         │    Test Mode APIs   │
                         └──────────┬──────────┘
                                    │
                         Failed Payments / State
                                    │
                                    ▼
┌──────────────────────────────────────────────────────────┐
│                     RECOVERSENSE                         │
│                                                          │
│  ┌──────────────────────┐                                │
│  │ Recovery Orchestrator│                                │
│  └──────────┬───────────┘                                │
│             │                                            │
│             ▼                                            │
│  ┌──────────────────────┐                                │
│  │ Diagnosis Provider   │◄────────── Claude API          │
│  └──────────┬───────────┘                                │
│             │                                            │
│             ▼                                            │
│  ┌──────────────────────┐                                │
│  │    StrategyRouter    │                                │
│  │     Deterministic    │                                │
│  └──────────┬───────────┘                                │
│             │                                            │
│             ▼                                            │
│  ┌──────────────────────┐                                │
│  │     PolicyEngine     │                                │
│  │    7 Safety Checks   │                                │
│  └──────────┬───────────┘                                │
│             │                                            │
│        ┌────┴─────┐                                      │
│        │          │                                      │
│      BLOCK      ALLOW                                    │
│        │          │                                      │
│        │          ▼                                      │
│        │   ┌──────────────┐                              │
│        │   │ Recovery     │                              │
│        │   │ Action       │                              │
│        │   └──────┬───────┘                              │
│        │          │                                      │
│        │          ▼                                      │
│        │   ┌──────────────┐                              │
│        │   │ Razorpay     │                              │
│        │   │ Executor     │                              │
│        │   └──────┬───────┘                              │
│        │          │                                      │
│        │          ▼                                      │
│        │   ┌──────────────┐                              │
│        │   │ Verification │                              │
│        │   │   Re-fetch    │                             │
│        │   └──────┬───────┘                              │
│        │          │                                      │
│        │          ▼                                      │
│        │      VERIFIED                                    │
│        │          │                                      │
│        └──────────┴───────────────┐                       │
│                                   ▼                       │
│                           ┌──────────────┐                │
│                           │ AuditService │                │
│                           └──────┬───────┘                │
│                                  │                        │
└──────────────────────────────────┼────────────────────────┘
                                   │
                                   ▼
                          ┌─────────────────┐
                          │   PostgreSQL    │
                          │                 │
                          │ Payments        │
                          │ RecoveryCases   │
                          │ Decisions       │
                          │ Actions         │
                          │ AuditEvents     │
                          └────────┬────────┘
                                   │
                                   ▼
                          ┌─────────────────┐
                          │ React Dashboard │
                          │                 │
                          │ Overview        │
                          │ At-Risk         │
                          │ Cases           │
                          │ Audit Trail     │
                          │ Metrics         │
                          │ Integrations    │
                          └─────────────────┘
```

---

# 🧩 Backend Architecture

RecoverSense is implemented as one modular Spring Boot application.

```text
backend/src/main/java/com/recoversense/

├── diagnosis/
│   ├── DiagnosisProvider
│   ├── ClaudeDiagnosisProvider
│   └── SimulatedDiagnosisProvider
│
├── policy/
│   └── PolicyEngine
│
├── service/
│   ├── RecoveryOrchestrationService
│   ├── StrategyRouter
│   ├── VerificationService
│   └── AuditService
│
├── razorpay/
│   ├── RazorpayClient
│   ├── RecoveryActionExecutor
│   └── RecoveryActionVerifier
│
├── claude/
│   └── Claude integration
│
├── settlement/
│   └── Settlement verification
│
├── recovery/
│   └── REST APIs
│
├── dashboard/
│   └── Dashboard APIs
│
├── domain/
│   ├── Payment
│   ├── RecoveryCase
│   ├── RecoveryDecision
│   ├── RecoveryAction
│   └── AuditEvent
│
└── repository/
```

---

# 🖥️ Dashboard

RecoverSense provides a React + TypeScript dashboard for operators.

## Overview

Displays persisted recovery information such as:

* Revenue at risk
* Recovered revenue
* Recovery rate
* Verified actions
* Policy blocks
* Recent recovery cases
* Strategy distribution

## At-Risk Payments

Shows failed payments that have no active or recovered recovery case.

Operators can click:

```text
Recover
```

to trigger the recovery pipeline.

## Recovery Cases

Shows recovery lifecycle and outcomes.

## Audit Trail

Shows the complete decision history for a recovery case.

## Integrations

Shows provider integration state.

All dashboard metrics are derived from persisted database records.

**No hardcoded recovery results are used.**

---

# 🔄 At-Risk Payment Flow

```text
GET /api/dashboard/payments/at-risk
                │
                ▼
       Failed Payments
                │
                ▼
       No OPEN/RECOVERED Case
                │
                ▼
        Operator clicks
            "Recover"
                │
                ▼
POST /api/recovery/payments/{paymentId}/recover
                │
                ▼
            Diagnosis
                ↓
             Strategy
                ↓
              Policy
                ↓
             Action
                ↓
            Execution
                ↓
           Verification
                ↓
          Case Lifecycle
                ↓
              Audit
```

---

# 💰 Razorpay Integration

RecoverSense integrates with Razorpay Test Mode.

## Payment Ingestion

```text
POST /api/dashboard/payments/sync
                ↓
GET /v1/payments
                ↓
Recent failed payments
                ↓
RecoverSense Database
```

The sync is:

* Read-only against Razorpay
* Insert-only
* Idempotent by Razorpay payment ID
* Limited to a bounded recent page
* Never automatically starts recovery

The frontend never calls Razorpay directly.

---

# 🔐 Real vs Simulated

RecoverSense explicitly distinguishes real integrations from simulated components.

| Capability                               | Status                         |
| ---------------------------------------- | ------------------------------ |
| Deterministic diagnosis                  | ✅ Real                         |
| Claude diagnosis                         | ✅ Real when API key configured |
| Strategy routing                         | ✅ Real                         |
| 7-check policy engine                    | ✅ Real                         |
| Recovery lifecycle                       | ✅ Real                         |
| Duplicate recovery protection            | ✅ Real                         |
| Audit trail                              | ✅ Real                         |
| Razorpay Test Mode payment sync          | ✅ Real                         |
| Razorpay Test Mode Payment Link creation | ✅ Real                         |
| Razorpay Payment Link verification       | ✅ Real                         |
| Two-phase execution + verification       | ✅ Real                         |
| WAIT + RETRY execution                   | ⚠️ Not implemented             |
| REACQUIRE MANDATE execution              | ⚠️ Not implemented             |
| Production settlement source             | ⚠️ Not wired                   |
| Production payment processing            | ❌ Out of scope                 |

## Claude Fallback

When `claude.api-key` is not configured, RecoverSense uses a deterministic keyword classifier as the diagnosis provider.

It is explicitly tagged as:

```text
DiagnosisSource.SIMULATED
```

The downstream pipeline remains identical:

```text
              Diagnosis Provider
                      │
              ┌───────┴───────┐
              │               │
              ▼               ▼
         Claude API      Deterministic
         configured         fallback
              │               │
              └───────┬───────┘
                      ▼
               DiagnosisResult
                      │
                      ▼
               StrategyRouter
                      │
                      ▼
                PolicyEngine
                      │
                      ▼
                  Execute
                      │
                      ▼
                 Verify
```

---

# 🧪 Demo Scenarios

RecoverSense provides two complementary demo scenarios.

## Scenario 1 — Safe Policy Rejection

Payment:

```text
pay_demo_mandate_revoked
```

Flow:

```text
Payment Failed
      ↓
Diagnosis:
MANDATE_INVALID
      ↓
Strategy:
REACQUIRE_MANDATE
      ↓
Policy Evaluation
      ↓
BLOCKED
      ↓
Settlement evidence unavailable
      ↓
No Action Executed
```

### What this demonstrates

RecoverSense refuses to act when it cannot verify that the action is safe.

This demonstrates the **fail-closed policy design**.

---

# 💳 Scenario 2 — Real End-to-End Recovery

Payment:

```text
pay_demo_payment_link
```

Flow:

```text
Payment Failed
      ↓
Diagnosis:
REPEATED_FAILURE
      ↓
Strategy:
PAYMENT_LINK
      ↓
Policy:
ALLOWED
      ↓
Create Real Razorpay Test Mode
Payment Link
      ↓
EXECUTED_AWAITING_VERIFICATION
      ↓
Customer Pays
      ↓
Verify Payment
      ↓
Fresh Razorpay Re-fetch
      ↓
VERIFIED
      ↓
RecoveryCase:
RECOVERED
```

Then clicking Recover again demonstrates:

```text
HTTP 409 CONFLICT
```

with no duplicate recovery action or Payment Link.

---

# 📊 Real Batch Demo

RecoverSense can also pull the operator's own Razorpay Test Mode payments.

```text
At-Risk Payments
       ↓
Sync Razorpay Test Mode
       ↓
GET /v1/payments
       ↓
Recent failed payments
       ↓
Persist in RecoverSense
       ↓
Display REAL payments
```

Real synced payments are labelled:

```text
REAL
```

while seeded demo payments are labelled:

```text
DEMO
```

This keeps real provider data clearly separated from deterministic demo data.

---

# 🗄️ Data Model

The core domain is relational:

```text
Payment
   │
   └── RecoveryCase
           │
           ├── RecoveryDecision
           │
           ├── RecoveryAction
           │
           └── AuditEvent
```

PostgreSQL acts as the persistent source of truth for:

* Payment state
* Recovery state
* Decisions
* Actions
* Verification
* Audit history
* Dashboard metrics

---

# 🔌 API Surface

### Sync Razorpay payments

```http
POST /api/dashboard/payments/sync
```

### Get at-risk payments

```http
GET /api/dashboard/payments/at-risk
```

### Start recovery

```http
POST /api/recovery/payments/{paymentId}/recover
```

### Verify recovery

```http
POST /api/recovery/cases/{recoveryCaseId}/verify
```

### Dashboard metrics

```http
GET /api/dashboard/metrics
```

### Case audit

```http
GET /api/dashboard/cases/{id}/audit
```

---

# 🛠️ Technology Stack

| Layer              | Technology         |
| ------------------ | ------------------ |
| Backend            | Java 21            |
| Framework          | Spring Boot 4      |
| Database           | PostgreSQL         |
| Database Migration | Flyway             |
| ORM                | Spring Data JPA    |
| AI                 | Claude API         |
| Payment Provider   | Razorpay Test Mode |
| Frontend           | React + TypeScript |
| Build Tool         | Vite               |
| Styling            | Tailwind CSS       |
| Charts             | Recharts           |
| Infrastructure     | Docker             |

---

# 🏛️ Why a Modular Monolith?

RecoverSense intentionally uses a single Spring Boot application instead of microservices.

The buildathon problem does not require:

* Kafka
* Redis
* Kubernetes
* Multiple microservices
* Distributed orchestration

Adding those technologies would increase operational complexity without solving a core problem.

Instead, RecoverSense focuses complexity where it matters:

```text
Diagnosis
   ↓
Decision
   ↓
Policy
   ↓
Execution
   ↓
Verification
```

---

# ▶️ Running RecoverSense Locally

## Prerequisites

* Java 21
* Node.js
* Docker
* Maven Wrapper

## 1. Start PostgreSQL

```powershell
docker start recoversense-postgres
```

Or first time:

```powershell
docker compose -f infra/docker-compose.yml up -d
```

---

## 2. Start Backend

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

Backend:

```text
http://localhost:8081
```

Health check:

```text
http://localhost:8081/actuator/health
```

---

## 3. Start Frontend

```powershell
cd frontend
npm install
npm run dev
```

Frontend:

```text
http://localhost:5173
```

---

## 4. Run Demo Profile

```powershell
cd backend
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=demo"
```

This seeds the deterministic demo payments.

---

## 5. Run Tests

### Backend

```powershell
cd backend
.\mvnw.cmd test
```

### Frontend

```powershell
cd frontend
npm run build
npm run lint
```

---

# 📁 Repository Structure

```text
recoversense/
│
├── backend/
│   └── Spring Boot application
│
├── frontend/
│   └── React + TypeScript dashboard
│
├── docs/
│   ├── PRODUCT_SPEC.md
│   ├── ARCHITECTURE.md
│   ├── POLICY_SPEC.md
│   ├── VERIFICATION_SPEC.md
│   ├── DECISIONS.md
│   ├── PROJECT_STATE.md
│   └── DEMO.md
│
├── infra/
│   └── docker-compose.yml
│
└── tests/
```

---

# 🚦 Project Status

## Implemented

* ✅ Adaptive diagnosis pipeline
* ✅ Claude diagnosis integration
* ✅ Deterministic diagnosis fallback
* ✅ Deterministic strategy routing
* ✅ Seven-check policy engine
* ✅ Fail-closed policy behavior
* ✅ Recovery case lifecycle
* ✅ Recovery action lifecycle
* ✅ Duplicate recovery protection
* ✅ Immutable audit trail
* ✅ Dashboard metrics
* ✅ At-Risk Payments
* ✅ Razorpay Test Mode payment ingestion
* ✅ Razorpay Payment Link creation
* ✅ Independent Payment Link verification
* ✅ Human-in-the-loop verification

## Current Limitations

* ⚠️ WAIT + RETRY execution is not implemented
* ⚠️ REACQUIRE MANDATE execution is not implemented
* ⚠️ Production settlement verification is not wired
* ⚠️ Recovery is manually triggered
* ⚠️ No scheduler/webhook listener
* ⚠️ Razorpay sync currently reads a bounded recent page
* ⚠️ Production payment processing is outside the buildathon scope

These limitations are intentionally surfaced rather than hidden.

---

# 🎯 The RecoverSense Decision Model

The entire system can be reduced to five questions:

```text
┌─────────────────────────────┐
│ WHY did the payment fail?   │
│           ↓                 │
│ Diagnosis / Claude          │
├─────────────────────────────┤
│ WHAT should we do?          │
│           ↓                 │
│ Deterministic Strategy      │
├─────────────────────────────┤
│ MAY we do it?               │
│           ↓                 │
│ Policy Firewall             │
├─────────────────────────────┤
│ DID we execute it?          │
│           ↓                 │
│ Provider Execution          │
├─────────────────────────────┤
│ DID it actually work?       │
│           ↓                 │
│ Independent Verification    │
└─────────────────────────────┘
```

---

# 🏆 Why RecoverSense?

Traditional recovery:

```text
Payment Failed
      ↓
Retry
      ↓
Retry Again
```

RecoverSense:

```text
Payment Failed
      ↓
Understand WHY
      ↓
Choose the RIGHT strategy
      ↓
Check whether it is ALLOWED
      ↓
Execute safely
      ↓
Verify the REAL outcome
      ↓
Only then mark it RECOVERED
```

The key difference is:

> **RecoverSense treats payment recovery as a diagnosis-and-decision problem, not simply a retry-timing problem.**

---

# 💡 One-Minute Judge Explanation

> **RecoverSense is an adaptive revenue recovery engine for failed recurring payments. Instead of blindly retrying every failure, it first diagnoses why the payment failed using Claude, maps that diagnosis to a deterministic recovery strategy, and then runs that strategy through a seven-check policy firewall. Only approved actions can execute. After execution, RecoverSense independently re-fetches the payment state and only marks the case as recovered when the real business outcome is verified. Every step is recorded in an audit trail.**
>
> **Our core design principle is simple: AI diagnoses, deterministic systems authorize, Razorpay executes, and RecoverSense verifies.**

---

# 🔥 RecoverSense in One Diagram

```text
                    FAILED PAYMENT
                           │
                           ▼
                    ┌────────────┐
                    │  DIAGNOSE  │
                    │ Claude AI  │
                    └──────┬─────┘
                           │
                           ▼
                    ┌────────────┐
                    │  STRATEGY  │
                    │Deterministic
                    └──────┬─────┘
                           │
                           ▼
                    ┌────────────┐
                    │   POLICY   │
                    │ 7 Checks   │
                    └──────┬─────┘
                           │
                     ┌─────┴─────┐
                     │           │
                   BLOCK       ALLOW
                     │           │
                     │           ▼
                     │      ┌──────────┐
                     │      │ EXECUTE  │
                     │      └────┬─────┘
                     │           │
                     │           ▼
                     │      ┌──────────┐
                     │      │ VERIFY   │
                     │      └────┬─────┘
                     │           │
                     │           ▼
                     │      ┌──────────┐
                     │      │RECOVERED │
                     │      └────┬─────┘
                     │           │
                     └─────┬─────┘
                           ▼
                     ┌────────────┐
                     │   AUDIT    │
                     └──────┬─────┘
                            ▼
                     ┌────────────┐
                     │ DASHBOARD  │
                     └────────────┘
```

---

# 📚 Documentation

For deeper technical details, see:

1. `CLAUDE.md`
2. `docs/PRODUCT_SPEC.md`
3. `docs/ARCHITECTURE.md`
4. `docs/POLICY_SPEC.md`
5. `docs/VERIFICATION_SPEC.md`
6. `docs/DECISIONS.md`
7. `docs/PROJECT_STATE.md`
8. `docs/DEMO.md`

---

## RecoverSense

**Adaptive revenue recovery for recurring payments.**

> **A sixth sense for revenue recovery.**

*Built by Shubham Kumar· Razorpay AI Buildathon 2026*

*Email: shubham27034@gmail.com*
