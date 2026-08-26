# RecoverSense — Security & Financial Safety

## Threat model

Primary risk: unintended, duplicate or stale-state financial action.

## Controls

- server-side policy gate
- action allowlist
- idempotency
- duplicate recovery protection
- amount limits
- state re-validation
- webhook signature verification
- post-action verification
- append-only audit behavior
- secret isolation

## AI threats

- incorrect diagnosis
- hallucinated state
- malformed output
- overconfident recommendation
- prompt manipulation

## Mitigations

- minimal structured context
- strict JSON schema
- enum validation
- confidence handling
- deterministic strategy mapping
- deterministic policy
- state re-fetch
- action allowlist
- verification
- audit

## Frontend security rule

The frontend may display policy state but may never authorize a financial action by itself.

## Secrets

Never commit:
- Razorpay secret
- Claude API key
- webhook secret
- DB password
- tokens
