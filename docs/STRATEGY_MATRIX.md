# RecoverSense — Strategy Matrix

| Diagnosis | Strategy | Rationale | Execution |
|---|---|---|---|
| MANDATE_INVALID | REACQUIRE_MANDATE | Retry cannot repair a revoked mandate | provider/simulator |
| INSUFFICIENT_FUNDS | WAIT_RETRY | Temporary balance issue may resolve | provider retry |
| REPEATED_FAILURE | PAYMENT_LINK | Alternative payment path | payment link |
| TEMPORARY_FAILURE | WAIT_RETRY | Controlled retry may resolve | provider retry |
| CUSTOMER_CANCELLED | STOP / ESCALATE | Do not continue blindly | terminal |

## Demo emphasis

The reference UI emphasizes:
- Wait / Retry
- Payment Link
- Mandate Recovery
- Escalated / Stopped

## Rule

Strategy routing is deterministic. The LLM does not choose a different action after policy evaluation.
