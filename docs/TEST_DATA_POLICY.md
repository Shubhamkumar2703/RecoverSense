# RecoverSense — Test Data Policy

## Data categories

### REAL TEST-MODE DATA
Created through actual provider test-mode operations.

### SYNTHETIC DATA
Artificial records used to test diagnosis/strategy/metrics.

### SIMULATED STATE
Deterministic state created by RecoverSense because the provider test environment cannot expose a required state transition.

## Required UI labeling

- Razorpay Test Mode
- Synthetic
- Simulated

## Metrics

Synthetic data can be used for:
- diagnosis accuracy
- strategy accuracy
- controlled edge cases

Do not present those measurements as production merchant performance.
