# RecoverSense — Scope Lock

## In scope

- five failure types
- five strategy outcomes
- structured LLM diagnosis
- deterministic strategy routing
- seven policy checks
- supported Razorpay test-mode execution
- deterministic simulator only where required by test limitations
- post-action verification
- audit trail
- metrics from audit/system data
- React dashboard

## Explicitly out of scope

- production UPI/eNACH authentication
- WhatsApp/voice channels
- custom trained ML model
- multi-agent orchestration
- causal/counterfactual uplift measurement
- second payment gateway before deadline
- unnecessary distributed infrastructure

## Demo scope

The working demo should focus on no more than three executable recovery paths while still demonstrating STOP/ESCALATE terminal outcomes.

## Scope rule

If a new feature is proposed:
- assess BUILD/PARK/DROP
- do not change scope silently
- record accepted scope changes in `DECISIONS.md`
