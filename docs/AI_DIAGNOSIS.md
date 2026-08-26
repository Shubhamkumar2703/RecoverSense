# RecoverSense — AI Diagnosis Contract

## Responsibility

The LLM diagnoses the failure. It does not authorize execution.

## Input

Provide only relevant context:
- provider failure payload
- source
- step
- reason
- payment status
- subscription status
- relevant history
- customer state needed for diagnosis

## Output

The output should be structured JSON.

Example:

```json
{
  "failureType": "MANDATE_INVALID",
  "confidence": 0.94,
  "reasoning": "The recurring mandate is revoked while the subscription remains active.",
  "evidence": [
    "reason=mandate_revoked",
    "subscription_status=active"
  ]
}
```

## Validation

Reject/handle:
- malformed JSON
- missing failure type
- invalid enum
- confidence outside 0..1
- unsupported claims
- excessive reasoning text
- provider response that contradicts known state

## Fallback

If AI is unavailable or output is invalid:
- do not execute a financial action automatically
- use a safe fallback/hold path
- record the AI failure in audit

## Accuracy

Diagnosis accuracy is measured against labeled synthetic test data.

Do not call synthetic accuracy a production metric.
