# RecoverSense — Initial Data Model

## Core entities

### Customer
- id
- external_id
- status
- created_at
- updated_at

### Subscription
- id
- external_id
- customer_id
- status
- amount
- currency
- mandate/reference information
- created_at
- updated_at

### Payment
- id
- external_id
- subscription_id
- amount
- currency
- status
- failure_source
- failure_step
- failure_reason
- occurred_at

### RecoveryCase
- id
- payment_id
- diagnosis_id
- strategy
- policy_decision
- execution_status
- verification_status
- final_outcome
- created_at
- updated_at

### Diagnosis
- id
- recovery_case_id
- failure_type
- confidence
- reasoning
- evidence
- model
- created_at

### PolicyEvaluation
- id
- recovery_case_id
- decision
- evaluated_at

### PolicyCheck
- id
- policy_evaluation_id
- code
- result
- reason

### Execution
- id
- recovery_case_id
- action
- provider
- provider_reference
- status
- simulated
- executed_at

### Verification
- id
- recovery_case_id
- expected_state
- actual_state
- result
- mismatch
- verified_at

### AuditEvent
- id
- recovery_case_id
- event_type
- actor
- payload
- occurred_at
- correlation_id

## Relationship

```text
Customer
   |
Subscription
   |
Payment
   |
RecoveryCase
 |   |   |   |
Diagnosis Strategy Policy Execution
                |
           Verification
                |
              Audit
```
