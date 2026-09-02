package com.recoversense.demo;

import com.recoversense.settlement.SettlementState;
import com.recoversense.settlement.SettlementVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * M1.25: proves DemoSettlementVerifier activates as the single, unambiguous
 * SettlementVerifier bean under the demo profile (no NoUniqueBeanDefinitionException
 * against UnavailableSettlementVerifier, which stays unconditionally
 * registered), and answers SIMULATED NOT_SETTLED for exactly the one demo
 * payment id - UNKNOWN, same as production, for anything else (including
 * the *other* demo payment, pay_demo_mandate_revoked, which must still
 * legitimately block on policy).
 */
@SpringBootTest
@ActiveProfiles("demo")
class DemoSettlementVerifierTest {

    @Autowired
    private SettlementVerifier settlementVerifier;

    @Test
    void demoProfile_wiresDemoSettlementVerifierAsThePrimaryBean() {
        assertInstanceOf(DemoSettlementVerifier.class, settlementVerifier);
    }

    @Test
    void demoPaymentLinkExternalId_answersSimulatedNotSettled() {
        assertEquals(SettlementState.NOT_SETTLED,
                settlementVerifier.checkSettlement(DemoSettlementVerifier.DEMO_PAYMENT_LINK_EXTERNAL_ID));
    }

    @Test
    void anyOtherExternalId_answersUnknown_sameAsProduction() {
        assertEquals(SettlementState.UNKNOWN, settlementVerifier.checkSettlement("pay_some_other_payment"));
        assertEquals(SettlementState.UNKNOWN, settlementVerifier.checkSettlement("pay_demo_mandate_revoked"));
    }
}
