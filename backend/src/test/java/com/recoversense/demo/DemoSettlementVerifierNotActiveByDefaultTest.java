package com.recoversense.demo;

import com.recoversense.settlement.SettlementVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * M1.25: without the demo profile active (every existing test class, and
 * every default/production boot), DemoSettlementVerifier must not be the
 * wired bean at all - proves it doesn't leak simulated NOT_SETTLED evidence
 * outside the demo profile. Deliberately does not reference
 * UnavailableSettlementVerifier by name (it is package-private to
 * com.recoversense.settlement) - absence of DemoSettlementVerifier is
 * exactly what this needs to prove.
 */
@SpringBootTest
class DemoSettlementVerifierNotActiveByDefaultTest {

    @Autowired
    private SettlementVerifier settlementVerifier;

    @Test
    void defaultProfile_doesNotWireDemoSettlementVerifier() {
        assertFalse(settlementVerifier instanceof DemoSettlementVerifier);
    }
}
