# RecoverSense — Architecture

## High-level

```text
                  PAYMENT FAILURE / WEBHOOK
                           |
                           v
                    +-------------+
                    |  Diagnosis  |
                    |  Claude API  |
                    +------+------+
                           |
                           v
                    +-------------+
                    |  Strategy   |
                    | deterministic|
                    +------+------+
                           |
                           v
                    +-------------+
                    |   Policy    |
                    | deterministic|
                    +------+------+
                           |
                    +------+------+
                    |             |
                  BLOCK         ALLOW
                    |             |
                    v             v
                  Audit       Execution
                                  |
                                  v
                            Verification
                                  |
                                  v
                                Audit
```

## Application shape

One Spring Boot application.

The orchestration path is synchronous because the demo dataset is small and predictable.

## Package/module boundaries

```text
recovery/
diagnosis/
strategy/
policy/
execution/
verification/
audit/
metrics/
integration/
common/
```

## Key components

### RecoveryService
Orchestrates the fixed lifecycle.

### DiagnosisService
Builds structured AI diagnosis from failure payload + relevant payment history.

### StrategyRouter
Maps diagnosis + state to deterministic strategy.

### PolicyEngine
Evaluates all required safety checks.

### ExecutionService
Executes only policy-approved actions through adapters.

### VerificationService
Re-fetches state and compares expected vs actual.

### AuditService
Persists timestamped decision events.

### MetricsService
Computes metrics from audit/recovery records.

## Integration boundary

All Razorpay HTTP calls should live behind one integration client/adapter boundary. Do not scatter raw payment HTTP calls through business services.

## Deliberately not using

- microservices
- Kafka
- Redis
- Kubernetes
- LangChain/LangGraph
- n8n
- vector DB/RAG
- reactive WebClient for the core workflow

Reason: no concrete requirement at demo scale; complexity would increase failure surface.

## Architecture invariant

Anything that touches money is deterministic, bounded and auditable.
