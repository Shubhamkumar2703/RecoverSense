package com.recoversense.diagnosis;

import java.math.BigDecimal;

/**
 * Structured diagnosis output - classification only. diagnosisCategory is
 * drawn from the closed taxonomy in docs/FAILURE_TAXONOMY.md - "UNKNOWN" is
 * the one explicit fallback outside that taxonomy, used only when evidence
 * is insufficient (see FAILURE_TAXONOMY.md's "Important" note).
 * <p>
 * Strategy selection is deliberately NOT part of this type - see
 * {@link StrategyRouter}. Diagnosis and strategy used to be fused here; M1.15
 * separated them so a real (untrusted) diagnosis provider can never carry an
 * authoritative strategy/action decision, only a classification.
 */
public record DiagnosisResult(
        String diagnosisCategory,
        BigDecimal confidence,
        String reasoning
) {
}
