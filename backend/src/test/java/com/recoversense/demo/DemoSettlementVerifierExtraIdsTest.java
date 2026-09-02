package com.recoversense.demo;

import com.recoversense.settlement.SettlementState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * M1.26 Phase 6: proves the operator can explicitly designate one of their
 * own real synced Razorpay payment ids as a simulated-NOT_SETTLED success
 * case via demo.settlement.extra-not-settled-payment-ids, without that
 * config silently affecting any other payment - and that the original
 * hardcoded demo id keeps working unchanged alongside it.
 */
@SpringBootTest(properties = "demo.settlement.extra-not-settled-payment-ids=pay_real_operator_chosen, pay_another_one")
@ActiveProfiles("demo")
class DemoSettlementVerifierExtraIdsTest {

    @Autowired
    private DemoSettlementVerifier demoSettlementVerifier;

    @Test
    void configuredExtraId_answersNotSettled() {
        assertEquals(SettlementState.NOT_SETTLED, demoSettlementVerifier.checkSettlement("pay_real_operator_chosen"));
        assertEquals(SettlementState.NOT_SETTLED, demoSettlementVerifier.checkSettlement("pay_another_one"));
    }

    @Test
    void originalHardcodedDemoId_stillAnswersNotSettled() {
        assertEquals(SettlementState.NOT_SETTLED,
                demoSettlementVerifier.checkSettlement(DemoSettlementVerifier.DEMO_PAYMENT_LINK_EXTERNAL_ID));
    }

    @Test
    void unrelatedId_stillAnswersUnknown() {
        assertEquals(SettlementState.UNKNOWN, demoSettlementVerifier.checkSettlement("pay_not_configured_at_all"));
    }
}
