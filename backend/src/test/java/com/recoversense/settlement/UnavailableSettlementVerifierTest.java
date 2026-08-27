package com.recoversense.settlement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnavailableSettlementVerifierTest {

    private final UnavailableSettlementVerifier verifier = new UnavailableSettlementVerifier();

    @Test
    void alwaysReturnsUnknown_regardlessOfInput() {
        assertEquals(SettlementState.UNKNOWN, verifier.checkSettlement("pay_123"));
        assertEquals(SettlementState.UNKNOWN, verifier.checkSettlement(null));
        assertEquals(SettlementState.UNKNOWN, verifier.checkSettlement(""));
    }
}
