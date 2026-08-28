package com.recoversense.diagnosis;

import java.math.BigDecimal;

/**
 * Validated diagnosis-provider output - classification only, never a
 * strategy. This is the boundary type every {@link DiagnosisProvider}
 * (Claude or simulated) must produce; a provider implementation is
 * responsible for validating raw/untrusted output into this shape before
 * returning, never after.
 */
public record RecoveryDiagnosis(
        String failureType,
        BigDecimal confidence,
        String reasoning,
        DiagnosisSource source
) {
}
