package com.recoversense.diagnosis;

/**
 * Which kind of provider actually produced a {@link RecoveryDiagnosis}.
 * Required so a demo/dashboard can never present a deterministic fallback as
 * if it were real Claude output (see AI_DIAGNOSIS.md's fallback rule).
 */
public enum DiagnosisSource {
    CLAUDE,
    SIMULATED
}
