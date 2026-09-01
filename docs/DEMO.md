# RecoverSense — Demo Script

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

Run the backend with `--spring.profiles.active=demo` (see README §15) to seed one deterministic `FAILED` payment. No Razorpay or Claude credentials are required for steps 1-4 above.

**Known limitation (M1.23):** the "already settled elsewhere" policy check has no real settlement source wired in the running application, so it always evaluates to UNKNOWN and `PolicyEngine` fails closed — every recovery attempt today blocks at the policy stage. Frame this honestly as the safety property it demonstrates ("RecoverSense won't act on unverifiable state") rather than a bug. Reaching `EXECUTED`/`VERIFIED`/`RECOVERED` currently requires the Razorpay Test Mode positive-path tests (`backend/src/test/java/com/recoversense/razorpay/`), which wire a simulated settlement state directly — not the running app.
