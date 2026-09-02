package com.recoversense.diagnosis;

import com.recoversense.domain.CustomerStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers classification only - strategy mapping for the same categories is
 * covered exhaustively by StrategyRouterTest since M1.15 separated the two.
 */
class DiagnosisEngineTest {

    private final DiagnosisEngine engine = new DiagnosisEngine();

    @Test
    void mandateRevokedReason_diagnosesMandateInvalid() {
        DiagnosisResult result = engine.diagnose(
                new DiagnosisContext("mandate_revoked", "active", CustomerStatus.ACTIVE, 0));

        assertEquals("MANDATE_INVALID", result.diagnosisCategory());
        assertEquals(DiagnosisEngine.HIGH_CONFIDENCE, result.confidence());
    }

    @Test
    void insufficientFundsReason_diagnosesInsufficientFunds() {
        DiagnosisResult result = engine.diagnose(
                new DiagnosisContext("insufficient_funds", "active", CustomerStatus.ACTIVE, 0));

        assertEquals("INSUFFICIENT_FUNDS", result.diagnosisCategory());
        assertEquals(DiagnosisEngine.HIGH_CONFIDENCE, result.confidence());
    }

    /**
     * M1.25: reason-text evidence for REPEATED_FAILURE, independent of
     * retry_count - mirrors how MANDATE_INVALID/INSUFFICIENT_FUNDS are
     * already reached purely from reason text. Not specific to any one
     * payment id: this must hold for any failureReason containing both
     * words.
     */
    @Test
    void repeatedFailureReasonText_diagnosesRepeatedFailure_regardlessOfRetryCount() {
        DiagnosisResult result = engine.diagnose(
                new DiagnosisContext("Repeated payment failure - card declined multiple times", "active", CustomerStatus.ACTIVE, 0));

        assertEquals("REPEATED_FAILURE", result.diagnosisCategory());
        assertEquals(DiagnosisEngine.HIGH_CONFIDENCE, result.confidence());
    }

    @Test
    void retryCountAtThreshold_diagnosesRepeatedFailure() {
        DiagnosisResult result = engine.diagnose(
                new DiagnosisContext("card_declined", "active", CustomerStatus.ACTIVE, 3));

        assertEquals("REPEATED_FAILURE", result.diagnosisCategory());
        assertEquals(DiagnosisEngine.HIGH_CONFIDENCE, result.confidence());
    }

    @Test
    void retryCountBelowThreshold_withUnmatchedReason_diagnosesTemporaryFailure() {
        DiagnosisResult result = engine.diagnose(
                new DiagnosisContext("card_declined", "active", CustomerStatus.ACTIVE, 1));

        assertEquals("TEMPORARY_FAILURE", result.diagnosisCategory());
        assertEquals(DiagnosisEngine.LOW_CONFIDENCE, result.confidence());
    }

    @Test
    void inactiveCustomer_diagnosesCustomerCancelled_regardlessOfReason() {
        DiagnosisResult result = engine.diagnose(
                new DiagnosisContext("mandate_revoked", "active", CustomerStatus.INACTIVE, 0));

        assertEquals("CUSTOMER_CANCELLED", result.diagnosisCategory());
        assertEquals(DiagnosisEngine.HIGH_CONFIDENCE, result.confidence());
    }

    @Test
    void noFailureReasonAndLowRetryCount_isUnknown_notGuessed() {
        DiagnosisResult result = engine.diagnose(
                new DiagnosisContext(null, "active", CustomerStatus.ACTIVE, 0));

        assertEquals("UNKNOWN", result.diagnosisCategory());
        assertEquals(DiagnosisEngine.UNKNOWN_CONFIDENCE, result.confidence());
    }

    @Test
    void blankFailureReason_isTreatedAsMissingEvidence() {
        DiagnosisResult result = engine.diagnose(
                new DiagnosisContext("   ", "active", CustomerStatus.ACTIVE, 0));

        assertEquals("UNKNOWN", result.diagnosisCategory());
    }

    @Test
    void allConfidenceValues_areWithinValidZeroToOneRange() {
        assertBetweenZeroAndOne(DiagnosisEngine.HIGH_CONFIDENCE);
        assertBetweenZeroAndOne(DiagnosisEngine.LOW_CONFIDENCE);
        assertBetweenZeroAndOne(DiagnosisEngine.UNKNOWN_CONFIDENCE);
    }

    private void assertBetweenZeroAndOne(BigDecimal value) {
        assertTrue(value.compareTo(BigDecimal.ZERO) >= 0, value + " should be >= 0");
        assertTrue(value.compareTo(BigDecimal.ONE) <= 0, value + " should be <= 1");
    }
}
