package com.recoversense.diagnosis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exhaustively covers docs/STRATEGY_MATRIX.md - every existing failure type
 * must map to the exact same strategy it did before M1.15 separated
 * diagnosis from strategy (see DiagnosisEngineTest, which used to assert
 * these same pairs directly).
 */
class StrategyRouterTest {

    private final StrategyRouter router = new StrategyRouter();

    @Test
    void mandateInvalid_routesToReacquireMandate() {
        assertEquals("REACQUIRE_MANDATE", router.route("MANDATE_INVALID"));
    }

    @Test
    void insufficientFunds_routesToWaitRetry() {
        assertEquals("WAIT_RETRY", router.route("INSUFFICIENT_FUNDS"));
    }

    @Test
    void repeatedFailure_routesToPaymentLink() {
        assertEquals("PAYMENT_LINK", router.route("REPEATED_FAILURE"));
    }

    @Test
    void temporaryFailure_routesToWaitRetry() {
        assertEquals("WAIT_RETRY", router.route("TEMPORARY_FAILURE"));
    }

    @Test
    void customerCancelled_routesToStop() {
        assertEquals("STOP", router.route("CUSTOMER_CANCELLED"));
    }

    @Test
    void unknown_routesToEscalate() {
        assertEquals("ESCALATE", router.route("UNKNOWN"));
    }

    @Test
    void anyUnrecognizedCategory_failsClosedToEscalate() {
        // A provider (Claude included) returning a category outside the
        // closed taxonomy must never reach an executable strategy - this is
        // the fail-closed guarantee the taxonomy validation depends on.
        assertEquals("ESCALATE", router.route("SOMETHING_A_MODEL_MADE_UP"));
    }
}
