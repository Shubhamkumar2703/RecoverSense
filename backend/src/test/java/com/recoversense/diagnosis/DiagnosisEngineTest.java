package com.recoversense.diagnosis;

import com.recoversense.domain.CustomerStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosisEngineTest {

    private final DiagnosisEngine engine = new DiagnosisEngine();

    @Test
    void mandateRevokedReason_diagnosesMandateInvalid() {
        DiagnosisResult result = engine.diagnose(
                new DiagnosisContext("mandate_revoked", "active", CustomerStatus.ACTIVE, 0));

        assertEquals("MANDATE_INVALID", result.diagnosisCategory());
        assertEquals("REACQUIRE_MANDATE", result.strategy());
        assertEquals("REACQUIRE_MANDATE", result.actionType());
        assertEquals(DiagnosisEngine.HIGH_CONFIDENCE, result.confidence());
    }

    @Test
    void insufficientFundsReason_diagnosesInsufficientFunds() {
        DiagnosisResult result = engine.diagnose(
                new DiagnosisContext("insufficient_funds", "active", CustomerStatus.ACTIVE, 0));

        assertEquals("INSUFFICIENT_FUNDS", result.diagnosisCategory());
        assertEquals("WAIT_RETRY", result.strategy());
        assertEquals("WAIT_RETRY", result.actionType());
        assertEquals(DiagnosisEngine.HIGH_CONFIDENCE, result.confidence());
    }

    @Test
    void retryCountAtThreshold_diagnosesRepeatedFailure() {
        DiagnosisResult result = engine.diagnose(
                new DiagnosisContext("card_declined", "active", CustomerStatus.ACTIVE, 3));

        assertEquals("REPEATED_FAILURE", result.diagnosisCategory());
        assertEquals("PAYMENT_LINK", result.strategy());
        assertEquals("PAYMENT_LINK", result.actionType());
        assertEquals(DiagnosisEngine.HIGH_CONFIDENCE, result.confidence());
    }

    @Test
    void retryCountBelowThreshold_withUnmatchedReason_diagnosesTemporaryFailure() {
        DiagnosisResult result = engine.diagnose(
                new DiagnosisContext("card_declined", "active", CustomerStatus.ACTIVE, 1));

        assertEquals("TEMPORARY_FAILURE", result.diagnosisCategory());
        assertEquals("WAIT_RETRY", result.strategy());
        assertEquals("WAIT_RETRY", result.actionType());
        assertEquals(DiagnosisEngine.LOW_CONFIDENCE, result.confidence());
    }

    @Test
    void inactiveCustomer_diagnosesCustomerCancelled_regardlessOfReason() {
        DiagnosisResult result = engine.diagnose(
                new DiagnosisContext("mandate_revoked", "active", CustomerStatus.INACTIVE, 0));

        assertEquals("CUSTOMER_CANCELLED", result.diagnosisCategory());
        assertEquals("STOP", result.strategy());
        assertEquals("STOP", result.actionType());
        assertEquals(DiagnosisEngine.HIGH_CONFIDENCE, result.confidence());
    }

    @Test
    void noFailureReasonAndLowRetryCount_isUnknown_notGuessed() {
        DiagnosisResult result = engine.diagnose(
                new DiagnosisContext(null, "active", CustomerStatus.ACTIVE, 0));

        assertEquals("UNKNOWN", result.diagnosisCategory());
        assertEquals("ESCALATE", result.strategy());
        assertEquals("ESCALATE", result.actionType());
        assertEquals(DiagnosisEngine.UNKNOWN_CONFIDENCE, result.confidence());
    }

    @Test
    void blankFailureReason_isTreatedAsMissingEvidence() {
        DiagnosisResult result = engine.diagnose(
                new DiagnosisContext("   ", "active", CustomerStatus.ACTIVE, 0));

        assertEquals("UNKNOWN", result.diagnosisCategory());
        assertEquals("ESCALATE", result.actionType());
    }

    @Test
    void unknownCategory_neverProducesAnExecutableStrategy() {
        DiagnosisResult result = engine.diagnose(
                new DiagnosisContext(null, null, CustomerStatus.ACTIVE, 0));

        assertTrue(result.strategy().equals("ESCALATE") || result.strategy().equals("STOP"));
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
