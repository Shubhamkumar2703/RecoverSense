# RecoverSense — API Contract Baseline

This is a contract baseline, not permission to implement every endpoint.

## Recovery

`GET /api/recovery/cases`

List recovery cases.

`GET /api/recovery/cases/{id}`

Return case, diagnosis, strategy, policy, execution, verification and key audit information.

`POST /api/recovery/cases/{id}/run`

Run the recovery lifecycle for a specific case.

## Audit

`GET /api/recovery/cases/{id}/audit`

Return chronological audit events.

`GET /api/audit/export`

Export audit records.

## Metrics

`GET /api/metrics/overview`

Return:
- revenue at risk
- verified recovered revenue
- recovery rate
- diagnosis accuracy
- strategy accuracy
- avoided unnecessary attempts
- policy violations blocked
- verification success rate

## Webhooks

`POST /api/webhooks/razorpay`

Receive provider events.

Signature validation must be implemented before trusting production-like webhook data.

## API principles

- server-side authorization
- input validation
- idempotency for financial actions where applicable
- no frontend bypass of policy
- structured error responses
