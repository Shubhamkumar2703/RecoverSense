package com.recoversense.diagnosis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * M1.27: proves the truthfulness parser used by RecoveryResponse/
 * DashboardMetricsService to label a diagnosis "Claude" only when it
 * actually was, never guessing from an unrecognized or absent prefix.
 */
class DiagnosisSourceTest {

    @Test
    void claudePrefix_parsesAsClaude() {
        assertEquals(DiagnosisSource.CLAUDE, DiagnosisSource.parsePrefix("[CLAUDE] the reasoning text"));
    }

    @Test
    void simulatedPrefix_parsesAsSimulated() {
        assertEquals(DiagnosisSource.SIMULATED, DiagnosisSource.parsePrefix("[SIMULATED] the reasoning text"));
    }

    @Test
    void noPrefix_returnsNull_neverGuessed() {
        assertNull(DiagnosisSource.parsePrefix("raw"));
    }

    @Test
    void nullInput_returnsNull() {
        assertNull(DiagnosisSource.parsePrefix(null));
    }

    @Test
    void unrecognizedBracket_returnsNull_neverGuessed() {
        assertNull(DiagnosisSource.parsePrefix("[SOMETHING_ELSE] reasoning"));
    }
}
