package com.recoversense.service;

import java.math.BigDecimal;

/**
 * Diagnosis/strategy facts needed to open a RecoveryDecision. Produced by
 * DiagnosisService: diagnosisCategory/diagnosisConfidence/diagnosisRaw come
 * from a validated DiagnosisProvider result (Claude or the deterministic
 * simulated fallback - diagnosisRaw is tagged with which one), while
 * strategy/actionType are always derived deterministically by
 * StrategyRouter, never taken from the provider directly.
 */
public record RecoveryDiagnosisInput(
        String diagnosisCategory,
        BigDecimal diagnosisConfidence,
        String diagnosisRaw,
        String strategy,
        String actionType
) {
}
