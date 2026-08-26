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
