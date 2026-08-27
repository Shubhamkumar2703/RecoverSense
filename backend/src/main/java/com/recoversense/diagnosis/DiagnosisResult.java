package com.recoversense.diagnosis;

import java.math.BigDecimal;

/**
 * Structured diagnosis output. diagnosisCategory and strategy are drawn
 * from the closed taxonomies in docs/FAILURE_TAXONOMY.md and
 * docs/STRATEGY_MATRIX.md - "UNKNOWN"/"ESCALATE" is the one explicit
 * fallback outside those taxonomies, used only when evidence is
 * insufficient (see FAILURE_TAXONOMY.md's "Important" note). actionType is
 * the strategy itself: docs/DECISION_LOGIC.md Stage 2 states "the selected
 * strategy becomes a proposed action" - there is no separate action
 * vocabulary in the specification.
 */
public record DiagnosisResult(
        String diagnosisCategory,
        BigDecimal confidence,
        String reasoning,
        String strategy,
        String actionType
) {
}
