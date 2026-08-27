package com.recoversense.service;

import java.math.BigDecimal;

/**
 * Diagnosis/strategy facts needed to open a RecoveryDecision, supplied by the
 * caller. No AI/LLM call happens here - a future diagnosis module produces
 * these same fields; for now the caller (currently tests) provides them
 * directly.
 */
public record RecoveryDiagnosisInput(
        String diagnosisCategory,
        BigDecimal diagnosisConfidence,
        String diagnosisRaw,
        String strategy,
        String actionType
) {
}
