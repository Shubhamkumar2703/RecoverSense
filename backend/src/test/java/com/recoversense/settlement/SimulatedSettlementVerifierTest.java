package com.recoversense.settlement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SimulatedSettlementVerifierTest {

    @Test
    void seededSettled_isReturned() {
        SimulatedSettlementVerifier verifier = new SimulatedSettlementVerifier()
                .seedSettlementState("pay_1", SettlementState.SETTLED);

        assertEquals(SettlementState.SETTLED, verifier.checkSettlement("pay_1"));
    }

    @Test
    void seededNotSettled_isReturned() {
        SimulatedSettlementVerifier verifier = new SimulatedSettlementVerifier()
                .seedSettlementState("pay_2", SettlementState.NOT_SETTLED);

        assertEquals(SettlementState.NOT_SETTLED, verifier.checkSettlement("pay_2"));
    }

    @Test
    void seededUnknown_isReturned() {
        SimulatedSettlementVerifier verifier = new SimulatedSettlementVerifier()
                .seedSettlementState("pay_3", SettlementState.UNKNOWN);

        assertEquals(SettlementState.UNKNOWN, verifier.checkSettlement("pay_3"));
    }

    @Test
    void unseededId_failsSafelyToUnknown_notNotSettled() {
        SimulatedSettlementVerifier verifier = new SimulatedSettlementVerifier();

        assertEquals(SettlementState.UNKNOWN, verifier.checkSettlement("pay_never_seeded"));
    }

    @Test
    void repeatedChecks_areDeterministic() {
        SimulatedSettlementVerifier verifier = new SimulatedSettlementVerifier()
                .seedSettlementState("pay_4", SettlementState.SETTLED);

        assertEquals(SettlementState.SETTLED, verifier.checkSettlement("pay_4"));
        assertEquals(SettlementState.SETTLED, verifier.checkSettlement("pay_4"));
        assertEquals(SettlementState.SETTLED, verifier.checkSettlement("pay_4"));
    }

    @Test
    void differentIds_areIndependentlyControlled() {
        SimulatedSettlementVerifier verifier = new SimulatedSettlementVerifier()
                .seedSettlementState("pay_settled", SettlementState.SETTLED)
                .seedSettlementState("pay_not_settled", SettlementState.NOT_SETTLED);

        assertEquals(SettlementState.SETTLED, verifier.checkSettlement("pay_settled"));
        assertEquals(SettlementState.NOT_SETTLED, verifier.checkSettlement("pay_not_settled"));
        assertEquals(SettlementState.UNKNOWN, verifier.checkSettlement("pay_untouched"));
    }
}
